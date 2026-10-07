#include "mse_host.h"
#include "mse_ffmpeg.h"
#include "audio_fx.h"

#include <limits.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#ifdef __APPLE__
#include <AudioToolbox/AudioToolbox.h>
#include <dispatch/dispatch.h>
#include <mach/mach_time.h>
#include <os/log.h>
#include <sys/sysctl.h>
#include <sys/types.h>
#include "mse_vt_present.h"
#endif
#ifdef __ANDROID__
#include <android/native_window.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
static void blit_window(ANativeWindow *win, int w, int h, const uint8_t *bgra);
#endif

#if defined(__APPLE__) || defined(__ANDROID__)
#define MSE_DAC 1
#endif

static void (*g_js_event)(const char *name);

void mse_host_set_js_event(void (*fn)(const char *name))
{
    g_js_event = fn;
}

struct MseHost {
    pthread_mutex_t mu;
    pthread_mutex_t frame_mu;
    pthread_cond_t cv;
    pthread_t dec;
    pthread_t vdec;
    int started;
    int released;
    int playing;
    int busy;
    int vbusy;
    int gate;
    int eos;
    int ended_sent;
    double hold;
    double origin_ns;
    double seek_base;
    double yt_dur;
    MseAv *av;
    MseFrameFn on_frame;
    MsePcmFn on_pcm;
    MseProgressFn on_prog;
    void *user;
    void *window;
    int vw;
    int vh;
    /* JVM hole. Survives mse_host_reset (new MseAv). 0×0 = decode size. */
    int blit_w;
    int blit_h;
    int frames;
    uint8_t *last_bgra; /* borrowed from MseAv ping-pong; do not free */
    int last_w;
    int last_h;
    int frame_gen;
    double vpts;
    int rate;
    int ch;
    float speed;
    int64_t samples_played;
    int64_t samples_pulled;
    AudioFx *fx;
    int16_t *fx_pcm;
    size_t fx_pcm_n;
#ifdef MSE_DAC
    uint8_t *ring;
    int ring_cap;
    int ring_r;
    int ring_w;
    int ring_n;
    /* Bumped by every ring flush; a chunk pulled before a flush must not be written after it. */
    unsigned ring_gen;
    int aq_started;
    int aq_buf_bytes;
    int flushing;
    double dac_tick_ns;
#endif
#ifdef __APPLE__
    AudioQueueRef aq;
    /* Pending fade-out pause (see aq_fade_fire). */
    struct AqFadeJob *aq_fade;
#endif
#ifdef __ANDROID__
    SLObjectItf sl_engine;
    SLObjectItf sl_mix;
    SLObjectItf sl_player;
    SLPlayItf sl_play;
    SLAndroidSimpleBufferQueueItf sl_bq;
    uint8_t *sl_bufs;
    int sl_nbufs;
    int sl_i;
#endif
};

static int16_t *fx_scratch(MseHost *h, int frames, int ch)
{
    size_t n;
    int16_t *p;
    if (frames <= 0 || ch <= 0)
        return NULL;
    n = (size_t)frames * (size_t)ch * sizeof(int16_t);
    if (h->fx_pcm && h->fx_pcm_n >= n)
        return h->fx_pcm;
    p = (int16_t *)realloc(h->fx_pcm, n);
    if (!p)
        return NULL;
    h->fx_pcm = p;
    h->fx_pcm_n = n;
    return p;
}

static void host_stretch(MseHost *h, int16_t **pcm, int *frames, int ch, int rate)
{
    int16_t *tmp;
    int nout, cap;
    if (!h->fx || !*pcm || *frames <= 0)
        return;
    audio_fx_set_rate(h->fx, rate);
    cap = *frames * 4 + 1024;
    if (cap < *frames)
        cap = *frames;
    tmp = fx_scratch(h, cap, ch);
    if (!tmp)
        return;
    nout = audio_fx_stretch(h->fx, *pcm, *frames, tmp, cap, ch);
    *pcm = tmp;
    *frames = nout;
}

static double now_ns(void)
{
#ifdef __APPLE__
    static mach_timebase_info_data_t tb;
    static int init;
    if (!init) {
        mach_timebase_info(&tb);
        init = 1;
    }
    return (double)mach_absolute_time() * (double)tb.numer / (double)tb.denom;
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1e9 + (double)ts.tv_nsec;
#endif
}

static double media_dur(MseHost *h)
{
    double d = mse_av_duration(h->av);
    if (h->yt_dur > d)
        d = h->yt_dur;
    return d;
}

static double play_speed(MseHost *h)
{
    float s = h->speed;
    if (s <= 0.f)
        return 1;
    if (s < 0.25f)
        return 0.25;
    if (s > 4.f)
        return 4;
    return (double)s;
}

/* h->mu held. Do not take MseAv locks: OpenSL's callback needs h->mu, and
   MediaCodec configure holds a->mu for tens of ms (audio-only → Auto). */
static double clock_with_span(MseHost *h, int have, double first, double last, int has_video)
{
    double t, hold;
    int out = 0;

    hold = h->hold < 0 ? 0 : h->hold;
    t = hold;
    have = have && last > first;
    /* Seek/hold outside leftover: keep currentTime at the seek so JS/SABR
       fetch that time. Clamping to `last` jumped the playhead to the
       buffered edge, then stalled (iOS) until leftover grew. */
    if (have)
        out = (hold > last + 0.25) || (hold + 0.25 < first);
    if (out || !have) {
        /* Empty leftover, or hold outside leftover: do not run the gold
           clock. Chrome currentTime stays at the seek until buffered
           covers it (log34: clock 0→6s with mse=[a: v:]). */
        if (h->playing)
            h->origin_ns = now_ns();
        t = hold;
    } else {
        if (h->playing && h->origin_ns > 0)
            t += (now_ns() - h->origin_ns) / 1e9 * play_speed(h);
#ifdef MSE_DAC
        /* Audio is the master clock while the DAC is running (Chrome
           AudioRendererImpl). Wall time was winning after sink-delay
           subtract because dac < wall, so video stayed ~100ms early. */
        if (h->aq_started && h->rate > 0 && h->samples_played > 0) {
            double sp = play_speed(h);
            double dac = h->seek_base + (double)h->samples_played / (double)h->rate * sp;
            double one = 0.02;
            int nbufs = 4;
            if (h->aq_buf_bytes > 0 && h->ch > 0)
                one = (double)h->aq_buf_bytes /
                      (double)(h->rate * (h->ch > 0 ? h->ch * 2 : 4));
#ifdef __ANDROID__
            if (h->sl_nbufs > 0)
                nbufs = h->sl_nbufs;
#endif
            dac -= one * (double)nbufs * sp;
            if (h->dac_tick_ns > 0) {
                double extra = (now_ns() - h->dac_tick_ns) / 1e9;
                if (extra > one)
                    extra = one;
                if (extra > 0)
                    dac += extra * sp;
            }
            t = dac;
        }
#endif
        /* Linear play: stay inside leftover. Sit 1s behind leftover end
           (live head of what we have) so SABR can fetch+parse the next
           second before we empty. Rebase origin on clamp or wall time
           accrues through the wait and the next chunk is recv-late. */
        if (have) {
            double cap = last;
            /* VOD catching the real end — not live leftover past a stale
               MediaSource duration (log23 recreate: yt_dur=17253, vf to 17266). */
            /* Audio-only leftover often ends ~0.5–1s before yt_dur (fake-144
               cover is 0..dur; audio last is short). 0.5s was too tight. */
            int near_eos = h->yt_dur > 0 && last + 1.5 >= h->yt_dur &&
                           last <= h->yt_dur + 0.75;
            /* 1s behind leftover end is for video readahead. Audio-only
               (no C video) must play to audio last or the clock freezes
               1s early and SABR audio never appends (log2: clock=570.884). */
            /* Live leftover is often ~2s. Keep 1s video readahead on long
               VOD; 0.35s on a short live window so we do not starve-empty. */
            if (!near_eos && has_video) {
                double span = last - first;
                double keep = span >= 4.0 ? 1.0 : 0.35;
                if (span > keep)
                    cap = last - keep;
            }
            if (t < first)
                t = first;
            else if (t > cap) {
                t = cap;
                h->hold = cap;
                h->origin_ns = now_ns();
#ifdef MSE_DAC
                h->seek_base = cap;
                h->samples_played = 0;
                h->dac_tick_ns = 0;
#endif
            }
        }
    }
    /* Live leftover grows past a snapshot duration. Chrome duration is
       Infinity; clamping currentTime there freezes the playhead (log23). */
    if (h->yt_dur > 0 && t > h->yt_dur && (!have || last <= h->yt_dur + 0.75))
        t = h->yt_dur;
    return t < 0 ? 0 : t;
}

static double host_clock(MseHost *h)
{
    double first = 0, last = 0, t;
    int have, has_v;
    have = mse_av_span(h->av, &first, &last);
    has_v = mse_av_has_video(h->av);
    pthread_mutex_lock(&h->mu);
    t = clock_with_span(h, have, first, last, has_v);
    pthread_mutex_unlock(&h->mu);
    return t;
}

#ifdef MSE_DAC
static int frame_bytes(MseHost *h)
{
    return h->ch > 0 ? h->ch * 2 : 4;
}

static void ring_write(MseHost *h, const uint8_t *p, int n)
{
    while (n > 0) {
        int space, chunk;
        /* Do not wait while holding mu — AudioQueue cannot drain then. */
        if (h->ring_n >= h->ring_cap || h->released || h->flushing)
            return;
        space = h->ring_cap - h->ring_n;
        chunk = n < space ? n : space;
        if (h->ring_w + chunk > h->ring_cap) {
            int first = h->ring_cap - h->ring_w;
            memcpy(h->ring + h->ring_w, p, (size_t)first);
            memcpy(h->ring, p + first, (size_t)(chunk - first));
            h->ring_w = chunk - first;
        } else {
            memcpy(h->ring + h->ring_w, p, (size_t)chunk);
            h->ring_w += chunk;
        }
        h->ring_n += chunk;
        p += chunk;
        n -= chunk;
        pthread_cond_broadcast(&h->cv);
    }
}

static int ring_read(MseHost *h, uint8_t *p, int n)
{
    int got = 0;
    int fb = frame_bytes(h);
    if (fb > 1)
        n -= n % fb;
    while (got < n && h->ring_n > 0) {
        int chunk = n - got;
        if (chunk > h->ring_n)
            chunk = h->ring_n;
        if (h->ring_r + chunk > h->ring_cap) {
            int first = h->ring_cap - h->ring_r;
            memcpy(p + got, h->ring + h->ring_r, (size_t)first);
            memcpy(p + got + first, h->ring, (size_t)(chunk - first));
            h->ring_r = chunk - first;
        } else {
            memcpy(p + got, h->ring + h->ring_r, (size_t)chunk);
            h->ring_r += chunk;
        }
        h->ring_n -= chunk;
        got += chunk;
    }
    if (got > 0 && h->fx && h->ch > 0) {
        int fb = frame_bytes(h);
        if (fb > 0)
            audio_fx_color(h->fx, (int16_t *)p, got / fb, h->ch);
    }
    return got;
}

#ifdef __APPLE__
static void aq_callback(void *user, AudioQueueRef q, AudioQueueBufferRef buf)
{
    MseHost *h = (MseHost *)user;
    int n = 0;
    int cap;
    int live;
    pthread_mutex_lock(&h->mu);
    cap = h->aq_buf_bytes > 0 ? h->aq_buf_bytes : (int)buf->mAudioDataBytesCapacity;
    if (cap > (int)buf->mAudioDataBytesCapacity)
        cap = (int)buf->mAudioDataBytesCapacity;
    /* Always EnqueueBuffer. Returning without it leaves the buffer outstanding
       and AudioQueueStop(true) in aq_kill blocks ~20s (src="" MSE recycle). */
    if (h->released || h->flushing || !h->aq) {
        pthread_mutex_unlock(&h->mu);
        memset(buf->mAudioData, 0, (size_t)cap);
        buf->mAudioDataByteSize = (UInt32)cap;
        AudioQueueEnqueueBuffer(q, buf, 0, NULL);
        return;
    }
    /* Not gated on playing: the queue still calls back about one buffer after AudioQueuePause, and that
     * buffer plays right after the ones already queued on resume. Silence there was a ~43 ms hole
     * ~100 ms into every resume; the ring's next audio is what belongs in it. */
    live = h->ring != NULL;
    if (live)
        n = ring_read(h, (uint8_t *)buf->mAudioData, cap);
    if (n > 0 && h->rate > 0 && h->ch > 0) {
        h->samples_played += n / frame_bytes(h);
        h->dac_tick_ns = now_ns();
    }
    pthread_cond_broadcast(&h->cv);
    pthread_mutex_unlock(&h->mu);
    if (n <= 0) {
        memset(buf->mAudioData, 0, (size_t)cap);
        n = cap;
    }
    buf->mAudioDataByteSize = (UInt32)n;
    AudioQueueEnqueueBuffer(q, buf, 0, NULL);
}

/*
 * AudioQueuePause / Start cut the waveform mid-cycle, which clicks. Pause ramps the queue volume to 0 and
 * pauses it AQ_FADE_PAUSE_NS later from a dispatch job; play and every (re)start ramp back up. The job is
 * ref-counted (MseHost + dispatch) and cancelled before the queue is resumed, flushed or disposed, so it
 * never pauses a queue it no longer owns. Lock order: job->mu, then h->mu.
 */
#define AQ_FADE_S 0.015
#define AQ_FADE_PAUSE_NS (25 * 1000 * 1000)

typedef struct AqFadeJob {
    pthread_mutex_t mu;
    int refs;
    int cancelled;
    MseHost *h;
    AudioQueueRef aq;
} AqFadeJob;

static void aq_fade_release_locked(AqFadeJob *j)
{
    int last = --j->refs == 0;
    pthread_mutex_unlock(&j->mu);
    if (last) {
        pthread_mutex_destroy(&j->mu);
        free(j);
    }
}

/* Under h->mu: detach the pending job; pass it to aq_fade_cancel after unlocking. */
static AqFadeJob *aq_fade_take(MseHost *h)
{
    AqFadeJob *j = h->aq_fade;
    h->aq_fade = NULL;
    return j;
}

static void aq_fade_cancel(AqFadeJob *j)
{
    if (!j)
        return;
    pthread_mutex_lock(&j->mu);
    j->cancelled = 1;
    aq_fade_release_locked(j);
}

static void aq_fade_fire(void *ctx)
{
    AqFadeJob *j = (AqFadeJob *)ctx;
    pthread_mutex_lock(&j->mu);
    if (!j->cancelled) {
        MseHost *h = j->h;
        int owned;
        pthread_mutex_lock(&h->mu);
        owned = h->aq_fade == j;
        if (owned)
            h->aq_fade = NULL;
        pthread_mutex_unlock(&h->mu);
        AudioQueuePause(j->aq);
        if (owned)
            j->refs--;
    }
    aq_fade_release_locked(j);
}

/* Start (or keep running) at volume 0 and ramp up, so a resume never starts mid-waveform. */
static void aq_start_faded(AudioQueueRef aq, int running)
{
    if (!running) {
        AudioQueueSetParameter(aq, kAudioQueueParam_VolumeRampTime, 0);
        AudioQueueSetParameter(aq, kAudioQueueParam_Volume, 0);
        AudioQueueStart(aq, NULL);
    }
    AudioQueueSetParameter(aq, kAudioQueueParam_VolumeRampTime, AQ_FADE_S);
    AudioQueueSetParameter(aq, kAudioQueueParam_Volume, 1);
}

static int start_aq(MseHost *h, int rate, int ch)
{
    AudioStreamBasicDescription fmt;
    AudioQueueRef aq = NULL;
    uint8_t *ring = NULL;
    int i, buf_bytes, cap;
    if (h->aq)
        return 0;
    memset(&fmt, 0, sizeof(fmt));
    fmt.mSampleRate = rate;
    fmt.mFormatID = kAudioFormatLinearPCM;
    fmt.mFormatFlags = kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked |
                       kAudioFormatFlagsNativeEndian;
    fmt.mBytesPerPacket = (UInt32)(ch * 2);
    fmt.mFramesPerPacket = 1;
    fmt.mBytesPerFrame = (UInt32)(ch * 2);
    fmt.mChannelsPerFrame = (UInt32)ch;
    fmt.mBitsPerChannel = 16;
    cap = rate * ch * 2; /* 1s */
    buf_bytes = 2048 * ch * 2;
    pthread_mutex_unlock(&h->mu);
    if (AudioQueueNewOutput(&fmt, aq_callback, h, NULL, kCFRunLoopCommonModes, 0, &aq) != 0) {
        pthread_mutex_lock(&h->mu);
        return -1;
    }
    ring = (uint8_t *)calloc(1, (size_t)cap);
    if (!ring) {
        AudioQueueDispose(aq, true);
        pthread_mutex_lock(&h->mu);
        return -1;
    }
    pthread_mutex_lock(&h->mu);
    if (h->aq || h->released) {
        pthread_mutex_unlock(&h->mu);
        free(ring);
        AudioQueueDispose(aq, true);
        pthread_mutex_lock(&h->mu);
        return 0;
    }
    h->aq = aq;
    h->rate = rate;
    h->ch = ch;
    h->ring = ring;
    h->ring_cap = cap;
    h->ring_r = h->ring_w = h->ring_n = 0;
    h->ring_gen++;
    h->aq_buf_bytes = buf_bytes;
    pthread_mutex_unlock(&h->mu);
    for (i = 0; i < 4; i++) {
        AudioQueueBufferRef buf;
        if (AudioQueueAllocateBuffer(aq, (UInt32)buf_bytes, &buf) != 0)
            break;
        memset(buf->mAudioData, 0, buf->mAudioDataBytesCapacity);
        buf->mAudioDataByteSize = (UInt32)buf_bytes;
        AudioQueueEnqueueBuffer(aq, buf, 0, NULL);
    }
    pthread_mutex_lock(&h->mu);
    return 0;
}
#endif

#ifdef __ANDROID__
static void sl_destroy(MseHost *h)
{
    SLObjectItf player = h->sl_player;
    SLObjectItf mix = h->sl_mix;
    SLObjectItf eng = h->sl_engine;
    SLPlayItf play = h->sl_play;
    SLAndroidSimpleBufferQueueItf bq = h->sl_bq;
    uint8_t *bufs = h->sl_bufs;
    h->sl_player = NULL;
    h->sl_play = NULL;
    h->sl_bq = NULL;
    h->sl_mix = NULL;
    h->sl_engine = NULL;
    h->sl_bufs = NULL;
    h->sl_nbufs = 0;
    h->sl_i = 0;
    pthread_mutex_unlock(&h->mu);
    if (play)
        (*play)->SetPlayState(play, SL_PLAYSTATE_STOPPED);
    if (bq)
        (*bq)->Clear(bq);
    if (player)
        (*player)->Destroy(player);
    if (mix)
        (*mix)->Destroy(mix);
    if (eng)
        (*eng)->Destroy(eng);
    free(bufs);
    pthread_mutex_lock(&h->mu);
}

static void sl_callback(SLAndroidSimpleBufferQueueItf bq, void *user)
{
    MseHost *h = (MseHost *)user;
    uint8_t *dst;
    int n = 0, cap, idx;
    pthread_mutex_lock(&h->mu);
    cap = h->aq_buf_bytes;
    if (cap <= 0 || !h->sl_bufs || h->sl_nbufs <= 0) {
        pthread_mutex_unlock(&h->mu);
        return;
    }
    idx = h->sl_i;
    h->sl_i = (idx + 1) % h->sl_nbufs;
    dst = h->sl_bufs + idx * cap;
    if (h->released || h->flushing || !h->sl_bq) {
        pthread_mutex_unlock(&h->mu);
        return;
    }
    /* Same as aq_callback: a buffer requested around pause plays on resume, so it gets the ring's audio. */
    if (h->ring)
        n = ring_read(h, dst, cap);
    if (n > 0 && h->rate > 0 && h->ch > 0) {
        h->samples_played += n / frame_bytes(h);
        h->dac_tick_ns = now_ns();
    }
    if (n <= 0) {
        memset(dst, 0, (size_t)cap);
        n = cap;
    }
    pthread_cond_broadcast(&h->cv);
    pthread_mutex_unlock(&h->mu);
    (*bq)->Enqueue(bq, dst, (SLuint32)n);
}

static void sl_set_playing(MseHost *h, int playing)
{
    SLPlayItf play;
    pthread_mutex_lock(&h->mu);
    play = h->sl_play;
    pthread_mutex_unlock(&h->mu);
    if (play)
        (*play)->SetPlayState(play, playing ? SL_PLAYSTATE_PLAYING : SL_PLAYSTATE_PAUSED);
}

static int start_sl(MseHost *h, int rate, int ch)
{
    SLObjectItf engine = NULL, mix = NULL, player = NULL;
    SLEngineItf engItf = NULL;
    SLPlayItf play = NULL;
    SLAndroidSimpleBufferQueueItf bq = NULL;
    SLDataLocator_AndroidSimpleBufferQueue locBq;
    SLDataFormat_PCM fmt;
    SLDataLocator_OutputMix locMix;
    SLDataSource src;
    SLDataSink sink;
    SLInterfaceID ids[1];
    SLboolean req[1];
    uint8_t *ring = NULL, *bufs = NULL;
    int i, buf_bytes, cap, nbufs = 4;
    SLresult r;
    if (h->sl_player)
        return 0;
    cap = rate * ch * 2; /* 1s */
    buf_bytes = 2048 * ch * 2;
    pthread_mutex_unlock(&h->mu);
    r = slCreateEngine(&engine, 0, NULL, 0, NULL, NULL);
    if (r != SL_RESULT_SUCCESS) {
        pthread_mutex_lock(&h->mu);
        return -1;
    }
    (*engine)->Realize(engine, SL_BOOLEAN_FALSE);
    (*engine)->GetInterface(engine, SL_IID_ENGINE, &engItf);
    (*engItf)->CreateOutputMix(engItf, &mix, 0, NULL, NULL);
    (*mix)->Realize(mix, SL_BOOLEAN_FALSE);
    locBq.locatorType = SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE;
    locBq.numBuffers = (SLuint32)nbufs;
    memset(&fmt, 0, sizeof(fmt));
    fmt.formatType = SL_DATAFORMAT_PCM;
    fmt.numChannels = (SLuint32)ch;
    fmt.samplesPerSec = (SLuint32)rate * 1000;
    fmt.bitsPerSample = SL_PCMSAMPLEFORMAT_FIXED_16;
    fmt.containerSize = 16;
    fmt.channelMask = ch == 1 ? SL_SPEAKER_FRONT_CENTER
                              : (SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT);
    fmt.endianness = SL_BYTEORDER_LITTLEENDIAN;
    src.pLocator = &locBq;
    src.pFormat = &fmt;
    locMix.locatorType = SL_DATALOCATOR_OUTPUTMIX;
    locMix.outputMix = mix;
    sink.pLocator = &locMix;
    sink.pFormat = NULL;
    ids[0] = SL_IID_ANDROIDSIMPLEBUFFERQUEUE;
    req[0] = SL_BOOLEAN_TRUE;
    r = (*engItf)->CreateAudioPlayer(engItf, &player, &src, &sink, 1, ids, req);
    if (r != SL_RESULT_SUCCESS) {
        (*mix)->Destroy(mix);
        (*engine)->Destroy(engine);
        pthread_mutex_lock(&h->mu);
        return -1;
    }
    (*player)->Realize(player, SL_BOOLEAN_FALSE);
    (*player)->GetInterface(player, SL_IID_PLAY, &play);
    (*player)->GetInterface(player, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &bq);
    ring = (uint8_t *)calloc(1, (size_t)cap);
    bufs = (uint8_t *)calloc((size_t)nbufs, (size_t)buf_bytes);
    if (!ring || !bufs) {
        free(ring);
        free(bufs);
        (*player)->Destroy(player);
        (*mix)->Destroy(mix);
        (*engine)->Destroy(engine);
        pthread_mutex_lock(&h->mu);
        return -1;
    }
    pthread_mutex_lock(&h->mu);
    if (h->sl_player || h->released) {
        pthread_mutex_unlock(&h->mu);
        free(ring);
        free(bufs);
        (*player)->Destroy(player);
        (*mix)->Destroy(mix);
        (*engine)->Destroy(engine);
        pthread_mutex_lock(&h->mu);
        return 0;
    }
    h->sl_engine = engine;
    h->sl_mix = mix;
    h->sl_player = player;
    h->sl_play = play;
    h->sl_bq = bq;
    h->sl_bufs = bufs;
    h->sl_nbufs = nbufs;
    h->sl_i = 0;
    h->rate = rate;
    h->ch = ch;
    h->ring = ring;
    h->ring_cap = cap;
    h->ring_r = h->ring_w = h->ring_n = 0;
    h->ring_gen++;
    h->aq_buf_bytes = buf_bytes;
    pthread_mutex_unlock(&h->mu);
    (*bq)->RegisterCallback(bq, sl_callback, h);
    for (i = 0; i < nbufs; i++) {
        uint8_t *p = bufs + i * buf_bytes;
        memset(p, 0, (size_t)buf_bytes);
        (*bq)->Enqueue(bq, p, (SLuint32)buf_bytes);
    }
    pthread_mutex_lock(&h->mu);
    return 0;
}
#endif

static int ring_ms(MseHost *h)
{
    int bps;
    if (h->rate <= 0 || h->ch <= 0)
        return 0;
    bps = h->rate * h->ch * 2;
    if (bps <= 0)
        return 0;
    return (int)((int64_t)h->ring_n * 1000 / bps);
}
#endif

/* Decode thread pulls frames; JS thread only appends/seeks. on_frame/on_pcm
   run on the decode thread. Do not touch MseHost from a third thread. */
static void keep_bgra(MseHost *h, int w, int ht, uint8_t *bgra)
{
    if (!bgra || w <= 0 || ht <= 0)
        return;
    pthread_mutex_lock(&h->frame_mu);
    h->last_bgra = bgra;
    h->last_w = w;
    h->last_h = ht;
    h->frame_gen++;
    pthread_mutex_unlock(&h->frame_mu);
}

static void maybe_notify_ended(MseHost *h)
{
    int playing;
    void (*fn)(const char *name);
    if (!h)
        return;
    pthread_mutex_lock(&h->mu);
    playing = h->playing && !h->ended_sent;
    pthread_mutex_unlock(&h->mu);
    if (!playing)
        return;
    if (!mse_host_ended(h))
        return;
    pthread_mutex_lock(&h->mu);
    if (h->ended_sent || !h->playing) {
        pthread_mutex_unlock(&h->mu);
        return;
    }
    h->ended_sent = 1;
    pthread_mutex_unlock(&h->mu);
    fn = g_js_event;
    if (fn)
        fn("mse_ended");
    mse_critf(MSE_LOG_INFO, "mse ended notify");
}

static void *vid_loop(void *arg)
{
    MseHost *h = (MseHost *)arg;
    while (1) {
        double clock, dur;
        uint8_t *bgra = NULL;
        uint8_t *show = NULL;
        int w = 0, ht = 0, show_w = 0, show_ht = 0;
        double pts = 0;
        pthread_mutex_lock(&h->mu);
        h->vbusy = 0;
        pthread_cond_broadcast(&h->cv);
        for (;;) {
            while (!h->released && (!h->playing || h->gate))
                pthread_cond_wait(&h->cv, &h->mu);
            if (h->released) {
                pthread_mutex_unlock(&h->mu);
                return NULL;
            }
            pthread_mutex_unlock(&h->mu);
            /* v_rest is on a->mu. Do not nest under h->mu: rest-off's
               mc_close holds a->mu and OpenSL needs h->mu. */
            if (!mse_av_video_rested(h->av)) {
                pthread_mutex_lock(&h->mu);
                if (h->released) {
                    pthread_mutex_unlock(&h->mu);
                    return NULL;
                }
                if (!h->playing || h->gate)
                    continue;
                break;
            }
            pthread_mutex_lock(&h->mu);
            if (h->released) {
                pthread_mutex_unlock(&h->mu);
                return NULL;
            }
            if (!h->playing || h->gate)
                continue;
            pthread_cond_wait(&h->cv, &h->mu);
        }
        h->vbusy = 1;
        pthread_mutex_unlock(&h->mu);
        clock = host_clock(h);
        dur = mse_av_duration(h->av);

        if (mse_av_pull_video(h->av, clock, &bgra, &w, &ht, &pts) > 0) {
#ifdef __ANDROID__
            if (bgra) {
                ANativeWindow *win;
                pthread_mutex_lock(&h->mu);
                win = (ANativeWindow *)h->window;
                if (win)
                    ANativeWindow_acquire(win);
                pthread_mutex_unlock(&h->mu);
                if (win) {
                    blit_window(win, w, ht, bgra);
                    ANativeWindow_release(win);
                }
            }
#endif
            if (bgra) {
                keep_bgra(h, w, ht, bgra);
                show = bgra;
                show_w = w;
                show_ht = ht;
            }
            pthread_mutex_lock(&h->mu);
            h->vw = w;
            h->vh = ht;
            h->vpts = pts;
            h->frames++;
            pthread_mutex_unlock(&h->mu);
        }

        if (show && h->on_frame)
            h->on_frame(h->user, show_w, show_ht, show);
        if (h->on_prog)
            h->on_prog(h->user, clock, dur, w, ht);
        maybe_notify_ended(h);
        {
            struct timespec sl;
            sl.tv_sec = 0;
            sl.tv_nsec = 4 * 1000 * 1000;
            nanosleep(&sl, NULL);
        }
    }
}

static void *dec_loop(void *arg)
{
    MseHost *h = (MseHost *)arg;
    while (1) {
        double clock;
        int16_t *pcm = NULL;
        int frames = 0, rate = 0, ch = 0;
        double pts = 0;
#ifdef MSE_DAC
        int filled;
#ifdef __APPLE__
        AudioQueueRef start_q = NULL;
#endif
#ifdef __ANDROID__
        int start_sl_play = 0;
#endif
#endif
        pthread_mutex_lock(&h->mu);
        h->busy = 0;
        pthread_cond_broadcast(&h->cv);
        while (!h->released && (!h->playing || h->gate))
            pthread_cond_wait(&h->cv, &h->mu);
        if (h->released) {
            pthread_mutex_unlock(&h->mu);
            return NULL;
        }
        h->busy = 1;
        pthread_mutex_unlock(&h->mu);
        clock = host_clock(h);

#ifdef MSE_DAC
        /* Own thread: a late 4K GOP must not starve AudioQueue. */
        filled = 0;
        while (filled < 8) {
            int want;
            int dac = 0;
            unsigned gen;
            pthread_mutex_lock(&h->mu);
            want = ring_ms(h) < 400;
            gen = h->ring_gen;
            pthread_mutex_unlock(&h->mu);
            if (!want)
                break;
            pcm = NULL;
            if (mse_av_pull_audio(h->av, clock + 0.35, &pcm, &frames, &rate, &ch, &pts) <= 0 || !pcm)
                break;
            host_stretch(h, &pcm, &frames, ch, rate);
            if (frames <= 0) {
                pcm = NULL;
                filled++;
                continue;
            }
            {
                int nbytes = frames * ch * (int)sizeof(int16_t);
                        pthread_mutex_lock(&h->mu);
#ifdef __APPLE__
                if (h->aq && (h->rate != rate || h->ch != ch)) {
                    AudioQueueRef old = h->aq;
                    AqFadeJob *fade = aq_fade_take(h);
                    h->aq = NULL;
                    h->aq_started = 0;
                    free(h->ring);
                    h->ring = NULL;
                    pthread_mutex_unlock(&h->mu);
                    aq_fade_cancel(fade);
                    AudioQueueStop(old, true);
                    AudioQueueDispose(old, true);
                    pthread_mutex_lock(&h->mu);
                }
                if (!h->aq && rate > 0 && ch > 0)
                    start_aq(h, rate, ch);
                dac = h->aq != NULL;
#endif
#ifdef __ANDROID__
                if (h->sl_player && (h->rate != rate || h->ch != ch)) {
                    free(h->ring);
                    h->ring = NULL;
                    h->aq_started = 0;
                    sl_destroy(h);
                }
                if (!h->sl_player && rate > 0 && ch > 0)
                    start_sl(h, rate, ch);
                dac = h->sl_player != NULL;
#endif
                h->samples_pulled += frames;
                if (dac && !h->flushing && gen == h->ring_gen)
                    ring_write(h, (const uint8_t *)pcm, nbytes);
                if (dac && !h->aq_started && ring_ms(h) >= 160) {
                    h->aq_started = 1;
                    h->samples_played = 0;
                    h->dac_tick_ns = 0;
#ifdef __APPLE__
                    start_q = h->aq;
#endif
#ifdef __ANDROID__
                    start_sl_play = 1;
#endif
                }
                pthread_mutex_unlock(&h->mu);
            }
            pcm = NULL;
            filled++;
        }
#ifdef __APPLE__
        if (start_q)
            aq_start_faded(start_q, 0);
#endif
#ifdef __ANDROID__
        if (start_sl_play)
            sl_set_playing(h, 1);
#endif
#else
        if (mse_av_pull_audio(h->av, clock + 0.35, &pcm, &frames, &rate, &ch, &pts) > 0 && pcm) {
            int nbytes;
            host_stretch(h, &pcm, &frames, ch, rate);
            if (h->fx && frames > 0)
                audio_fx_color(h->fx, pcm, frames, ch);
            if (frames > 0) {
                nbytes = frames * ch * (int)sizeof(int16_t);
                if (h->on_pcm)
                    h->on_pcm(h->user, rate, ch, pcm, nbytes);
                pthread_mutex_lock(&h->mu);
                h->samples_pulled += frames;
                pthread_mutex_unlock(&h->mu);
            }
        }
#endif

        maybe_notify_ended(h);
        {
            struct timespec sl;
            /* Never 0: pull_audio returning 0 with packets still pending
               was while(true){}. 2ms is plenty for DAC fill. */
            sl.tv_sec = 0;
            sl.tv_nsec = 2 * 1000 * 1000;
            nanosleep(&sl, NULL);
        }
    }
}

#ifdef __APPLE__
static MseHost *g_ios_host;
void mse_ios_log(const char *msg);
#endif

MseHost *mse_host_create(void)
{
    MseHost *h = (MseHost *)calloc(1, sizeof(MseHost));
    if (!h)
        return NULL;
    pthread_mutex_init(&h->mu, NULL);
    pthread_mutex_init(&h->frame_mu, NULL);
    pthread_cond_init(&h->cv, NULL);
    h->av = mse_av_create();
    h->fx = audio_fx_create();
    h->speed = 1.f;
    h->playing = 0;
    h->vpts = -1;
    if (!h->av) {
        free(h);
        return NULL;
    }
#ifdef __APPLE__
    g_ios_host = h;
#endif
    return h;
}

#ifdef MSE_DAC
#ifdef __APPLE__
/* video.src swap: Pause+Flush left in-flight callbacks free to Enqueue old PCM. */
static void aq_kill(MseHost *h)
{
    AudioQueueRef aq;
    uint8_t *ring;
    AqFadeJob *fade;
    pthread_mutex_lock(&h->mu);
    h->flushing = 1;
    h->aq_started = 0;
    fade = aq_fade_take(h);
    aq = h->aq;
    h->aq = NULL;
    ring = h->ring;
    h->ring = NULL;
    h->ring_cap = h->ring_r = h->ring_w = h->ring_n = 0;
    h->ring_gen++;
    pthread_cond_broadcast(&h->cv);
    pthread_mutex_unlock(&h->mu);
    aq_fade_cancel(fade);
    if (aq) {
        AudioQueueStop(aq, false);
        AudioQueueDispose(aq, true);
    }
    free(ring);
    pthread_mutex_lock(&h->mu);
    h->flushing = 0;
    pthread_cond_broadcast(&h->cv);
    pthread_mutex_unlock(&h->mu);
}
#endif

static void aq_pause_flush(MseHost *h)
{
#ifdef __APPLE__
    AudioQueueRef aq;
    AqFadeJob *fade;
#endif
#ifdef __ANDROID__
    SLPlayItf play;
    SLAndroidSimpleBufferQueueItf bq;
    uint8_t *bufs;
    int i, nbufs, buf_bytes;
#endif
    pthread_mutex_lock(&h->mu);
    h->flushing = 1;
    h->aq_started = 0;
    h->ring_r = h->ring_w = h->ring_n = 0;
    h->ring_gen++;
    pthread_cond_broadcast(&h->cv);
#ifdef __APPLE__
    aq = h->aq;
    fade = aq_fade_take(h);
#endif
#ifdef __ANDROID__
    play = h->sl_play;
    bq = h->sl_bq;
    bufs = h->sl_bufs;
    nbufs = h->sl_nbufs;
    buf_bytes = h->aq_buf_bytes;
#endif
    pthread_mutex_unlock(&h->mu);
#ifdef __APPLE__
    aq_fade_cancel(fade);
    if (aq) {
        AudioQueuePause(aq);
        AudioQueueFlush(aq);
    }
#endif
#ifdef __ANDROID__
    if (play)
        (*play)->SetPlayState(play, SL_PLAYSTATE_PAUSED);
    if (bq) {
        (*bq)->Clear(bq);
        for (i = 0; i < nbufs && bufs && buf_bytes > 0; i++) {
            uint8_t *p = bufs + i * buf_bytes;
            memset(p, 0, (size_t)buf_bytes);
            (*bq)->Enqueue(bq, p, (SLuint32)buf_bytes);
        }
    }
#endif
    pthread_mutex_lock(&h->mu);
    h->flushing = 0;
    pthread_cond_broadcast(&h->cv);
    pthread_mutex_unlock(&h->mu);
}
#endif

static void wait_idle(MseHost *h)
{
    while ((h->busy || h->vbusy) && !h->released)
        pthread_cond_wait(&h->cv, &h->mu);
}

void mse_host_destroy(MseHost *h)
{
    if (!h)
        return;
    pthread_mutex_lock(&h->mu);
    h->released = 1;
    h->playing = 0;
#ifdef MSE_DAC
    h->flushing = 1;
#endif
    pthread_cond_broadcast(&h->cv);
    pthread_mutex_unlock(&h->mu);
    if (h->started) {
        pthread_join(h->dec, NULL);
        pthread_join(h->vdec, NULL);
    }
#ifdef __APPLE__
    if (g_ios_host == h)
        g_ios_host = NULL;
    pthread_mutex_lock(&h->mu);
    {
        AqFadeJob *fade = aq_fade_take(h);
        pthread_mutex_unlock(&h->mu);
        aq_fade_cancel(fade);
    }
    if (h->aq) {
        AudioQueueStop(h->aq, true);
        AudioQueueDispose(h->aq, true);
        h->aq = NULL;
    }
#endif
#ifdef __ANDROID__
    pthread_mutex_lock(&h->mu);
    sl_destroy(h);
    pthread_mutex_unlock(&h->mu);
#endif
#ifdef MSE_DAC
    free(h->ring);
    h->ring = NULL;
#endif
#ifdef __ANDROID__
    mse_av_set_window(h->av, NULL);
    if (h->window)
        ANativeWindow_release((ANativeWindow *)h->window);
    h->window = NULL;
#endif
#ifdef __APPLE__
    mse_av_set_window(h->av, NULL);
    if (h->window) {
        mse_vt_detach(h->window);
        h->window = NULL;
    }
#endif
    pthread_mutex_lock(&h->frame_mu);
    h->last_bgra = NULL;
    h->last_w = 0;
    h->last_h = 0;
    pthread_mutex_unlock(&h->frame_mu);
    mse_av_destroy(h->av);
    audio_fx_destroy(h->fx);
    free(h->fx_pcm);
    pthread_mutex_destroy(&h->frame_mu);
    pthread_mutex_destroy(&h->mu);
    pthread_cond_destroy(&h->cv);
    free(h);
}

void mse_host_set_video_surface(MseHost *h, void *window)
{
    void *old;
    if (!h)
        return;
    pthread_mutex_lock(&h->mu);
    old = h->window;
    h->window = window;
    pthread_mutex_unlock(&h->mu);
#ifdef __ANDROID__
    mse_av_set_window(h->av, window);
    if (old)
        ANativeWindow_release((ANativeWindow *)old);
#elif defined(__APPLE__)
    mse_av_set_window(h->av, window);
    if (old && old != window)
        mse_vt_detach(old);
#else
    (void)window;
    (void)old;
#endif
}

#ifdef __ANDROID__
/* Fallback when MediaCodec cannot take the TextureView Surface. */
static void blit_window(ANativeWindow *win, int w, int h, const uint8_t *bgra)
{
    ANativeWindow_Buffer buf;
    int y, stride;
    if (!win || !bgra || w <= 0 || h <= 0)
        return;
    if (ANativeWindow_setBuffersGeometry(win, w, h, WINDOW_FORMAT_RGBX_8888) != 0)
        return;
    if (ANativeWindow_lock(win, &buf, NULL) != 0)
        return;
    stride = buf.stride;
    for (y = 0; y < h && y < buf.height; y++) {
        uint8_t *dst = (uint8_t *)buf.bits + y * stride * 4;
        const uint8_t *src = bgra + y * w * 4;
        int x, n = w < buf.width ? w : buf.width;
        for (x = 0; x < n; x++) {
            dst[x * 4 + 0] = src[x * 4 + 2];
            dst[x * 4 + 1] = src[x * 4 + 1];
            dst[x * 4 + 2] = src[x * 4 + 0];
            dst[x * 4 + 3] = 255;
        }
    }
    ANativeWindow_unlockAndPost(win);
}
#endif

void mse_host_set_sinks(MseHost *h, MseFrameFn on_frame, MsePcmFn on_pcm, MseProgressFn on_prog, void *user)
{
    if (!h)
        return;
    pthread_mutex_lock(&h->mu);
    h->on_frame = on_frame;
    h->on_pcm = on_pcm;
    h->on_prog = on_prog;
    h->user = user;
    pthread_mutex_unlock(&h->mu);
}

void mse_host_set_blit_size(MseHost *h, int w, int ht)
{
    if (!h)
        return;
    if (w < 0)
        w = 0;
    if (ht < 0)
        ht = 0;
    pthread_mutex_lock(&h->mu);
    h->blit_w = w;
    h->blit_h = ht;
    mse_av_set_blit_size(h->av, w, ht);
    pthread_mutex_unlock(&h->mu);
}

static void ensure_thread(MseHost *h)
{
    if (h->started)
        return;
    h->started = 1;
    pthread_create(&h->dec, NULL, dec_loop, h);
    pthread_create(&h->vdec, NULL, vid_loop, h);
}

void mse_host_play(MseHost *h)
{
    double dur, clock;
#ifdef __APPLE__
    AudioQueueRef aq = NULL;
    AqFadeJob *fade;
    int started;
#endif
#ifdef __ANDROID__
    int started;
#endif
    if (!h)
        return;
    dur = mse_av_duration(h->av);
    {
        double first = 0, last = 0;
        int have = mse_av_span(h->av, &first, &last);
        int has_v = mse_av_has_video(h->av);
        pthread_mutex_lock(&h->mu);
        clock = clock_with_span(h, have, first, last, has_v);
    }
    if (h->eos && dur > 0 && clock >= dur - 0.05) {
        h->hold = 0;
        h->seek_base = 0;
        h->eos = 0;
        h->ended_sent = 0;
#ifdef MSE_DAC
        h->samples_played = 0;
        h->aq_started = 0;
        h->dac_tick_ns = 0;
#endif
        pthread_mutex_unlock(&h->mu);
        mse_av_seek(h->av, 0);
        pthread_mutex_lock(&h->mu);
    }
    if (!h->playing) {
        h->origin_ns = now_ns();
        h->playing = 1;
    }
    ensure_thread(h);
    pthread_cond_broadcast(&h->cv);
#ifdef __APPLE__
    aq = h->aq;
    started = h->aq_started;
    fade = aq_fade_take(h);
#endif
#ifdef __ANDROID__
    started = h->aq_started;
#endif
    pthread_mutex_unlock(&h->mu);
#ifdef __APPLE__
    /* A pause still fading out never stopped the queue: just ramp back up from where it is. */
    aq_fade_cancel(fade);
    if (aq && started)
        aq_start_faded(aq, fade != NULL);
#endif
#ifdef __ANDROID__
    if (started)
        sl_set_playing(h, 1);
#endif
}

void mse_host_pause(MseHost *h)
{
    double t;
#ifdef __APPLE__
    AudioQueueRef aq;
#endif
    if (!h)
        return;
    t = host_clock(h);
    /* Stop the device before clearing playing: a callback that sees playing == 0 while the device still
     * runs enqueues silence, which then plays ahead of the music on resume (an audible gap). */
#ifdef __APPLE__
    {
        AqFadeJob *prev, *job = NULL;
        pthread_mutex_lock(&h->mu);
        aq = h->aq;
        prev = aq_fade_take(h);
        if (aq && h->playing && h->aq_started) {
            job = (AqFadeJob *)calloc(1, sizeof(AqFadeJob));
            if (job) {
                pthread_mutex_init(&job->mu, NULL);
                job->refs = 2;
                job->h = h;
                job->aq = aq;
                h->aq_fade = job;
            }
        }
        pthread_mutex_unlock(&h->mu);
        aq_fade_cancel(prev);
        if (job) {
            AudioQueueSetParameter(aq, kAudioQueueParam_VolumeRampTime, AQ_FADE_S);
            AudioQueueSetParameter(aq, kAudioQueueParam_Volume, 0);
            dispatch_after_f(dispatch_time(DISPATCH_TIME_NOW, AQ_FADE_PAUSE_NS),
                             dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), job, aq_fade_fire);
        } else if (aq) {
            AudioQueuePause(aq);
        }
    }
#endif
#ifdef __ANDROID__
    sl_set_playing(h, 0);
#endif
    pthread_mutex_lock(&h->mu);
    if (h->playing) {
        h->hold = t;
        h->playing = 0;
    }
    pthread_cond_broadcast(&h->cv);
    pthread_mutex_unlock(&h->mu);
}

static void seek_common(MseHost *h, double seconds)
{
    double prev;
    mse_logf("mse seek t=%.3f", seconds);
    pthread_mutex_lock(&h->mu);
    prev = h->playing ? 0 : (h->hold < 0 ? 0 : h->hold);
    pthread_mutex_unlock(&h->mu);
    if (h->playing)
        prev = host_clock(h);
#ifdef MSE_DAC
    /* Far seek: Pause+Flush leaves the same AudioQueue; in-flight buffers
       are the previous playhead (~0.5s smash after a few jumps). */
    if (seconds > prev + 1.0 || prev > seconds + 1.0) {
#ifdef __APPLE__
        aq_kill(h);
#endif
#ifdef __ANDROID__
        pthread_mutex_lock(&h->mu);
        h->flushing = 1;
        h->aq_started = 0;
        sl_destroy(h);
        free(h->ring);
        h->ring = NULL;
        h->ring_cap = h->ring_r = h->ring_w = h->ring_n = 0;
        h->ring_gen++;
        h->flushing = 0;
        pthread_mutex_unlock(&h->mu);
#endif
    } else {
        aq_pause_flush(h);
    }
#endif
    pthread_mutex_lock(&h->mu);
    h->gate = 1;
    pthread_cond_broadcast(&h->cv);
    wait_idle(h);
    h->hold = seconds;
    h->seek_base = seconds;
    h->origin_ns = now_ns();
    h->eos = 0;
    h->ended_sent = 0;
#ifdef MSE_DAC
    h->samples_played = 0;
    h->aq_started = 0;
    h->dac_tick_ns = 0;
    h->ring_r = h->ring_w = h->ring_n = 0;
    h->ring_gen++;
#endif
    h->vpts = -1;
    pthread_mutex_unlock(&h->mu);
    mse_av_seek(h->av, seconds);
    pthread_mutex_lock(&h->mu);
    h->gate = 0;
    pthread_cond_broadcast(&h->cv);
    pthread_mutex_unlock(&h->mu);
}

void mse_host_reset(MseHost *h)
{
    if (!h)
        return;
#ifdef MSE_DAC
#ifdef __APPLE__
    aq_kill(h);
#endif
#ifdef __ANDROID__
    pthread_mutex_lock(&h->mu);
    h->playing = 0;
    h->flushing = 1;
    h->aq_started = 0;
    sl_destroy(h);
    free(h->ring);
    h->ring = NULL;
    h->ring_cap = h->ring_r = h->ring_w = h->ring_n = 0;
    h->ring_gen++;
    h->flushing = 0;
    pthread_mutex_unlock(&h->mu);
#endif
#endif
    pthread_mutex_lock(&h->mu);
    h->playing = 0;
    h->gate = 1;
    pthread_cond_broadcast(&h->cv);
    wait_idle(h);
    h->gate = 0;
    h->hold = 0;
    h->seek_base = 0;
    h->yt_dur = 0;
    h->origin_ns = 0;
    h->eos = 0;
    h->ended_sent = 0;
    h->vw = 0;
    h->vh = 0;
#ifdef MSE_DAC
    h->samples_played = 0;
    h->aq_started = 0;
    h->dac_tick_ns = 0;
    h->ring_r = h->ring_w = h->ring_n = 0;
    h->ring_gen++;
#endif
    h->vpts = -1;
    pthread_mutex_lock(&h->frame_mu);
    h->last_bgra = NULL;
    h->last_w = 0;
    h->last_h = 0;
    pthread_mutex_unlock(&h->frame_mu);
    mse_av_destroy(h->av);
    h->av = mse_av_create();
    /* MediaSource reset drops MseAv (and mc_win / VT layer / blit size).
       Keep the attached window so AV1/MediaCodec can open after load.
       Keep the hole so 4K does not sws 3840×2160 (log28 hole=0x0, ~1 fps). */
    mse_av_set_window(h->av, h->window);
    mse_av_set_blit_size(h->av, h->blit_w, h->blit_h);
    pthread_cond_broadcast(&h->cv);
    pthread_mutex_unlock(&h->mu);
}

int mse_host_paused(MseHost *h)
{
    int p;
    if (!h)
        return 1;
    pthread_mutex_lock(&h->mu);
    p = !h->playing;
    pthread_mutex_unlock(&h->mu);
    return p;
}

int mse_host_ready_state(MseHost *h)
{
    double d;
    if (!h)
        return 0;
    d = mse_av_duration(h->av);
    if (d > 0)
        return 4;
    return 1;
}

double mse_host_current_time(MseHost *h)
{
    if (!h)
        return 0;
    return host_clock(h);
}

void mse_host_set_current_time(MseHost *h, double seconds)
{
    double now, d;
    if (!h)
        return;
    if (seconds < 0)
        seconds = 0;
    now = host_clock(h);
    pthread_mutex_lock(&h->mu);
    d = seconds - now;
    if (d < 0)
        d = -d;
    pthread_mutex_unlock(&h->mu);
    if (d < 0.05)
        return;
    seek_common(h, seconds);
}

double mse_host_duration(MseHost *h)
{
    if (!h)
        return 0;
    return media_dur(h);
}

void mse_host_set_duration(MseHost *h, double seconds)
{
    if (!h)
        return;
    pthread_mutex_lock(&h->mu);
    if (seconds <= 0)
        h->yt_dur = 0;
    else if (seconds > h->yt_dur)
        h->yt_dur = seconds;
    pthread_mutex_unlock(&h->mu);
}

int mse_host_ended(MseHost *h)
{
    int eos, ring_empty, near_dur;
    double last, yt_dur;
    if (!h)
        return 0;
    if (mse_av_audio_pending(h->av))
        return 0;
    last = mse_av_duration(h->av);
    pthread_mutex_lock(&h->mu);
    eos = h->eos;
    yt_dur = h->yt_dur;
#ifdef MSE_DAC
    ring_empty = !h->ring || h->ring_n <= 0;
#else
    ring_empty = 1;
#endif
    pthread_mutex_unlock(&h->mu);
    if (!ring_empty)
        return 0;
    /* Same window as clock_with_span near_eos. Miller skips endOfStream on
       VOD audio-only (40=1 + fake 144p); last ≈ duration is Chrome's other
       IsEnded path. SABR starve (last ≪ yt_dur) stays waiting. */
    near_dur = yt_dur > 0 && last + 1.5 >= yt_dur && last <= yt_dur + 0.75;
    return eos || near_dur;
}

void mse_host_end_of_stream(MseHost *h)
{
    if (!h)
        return;
    pthread_mutex_lock(&h->mu);
    h->eos = 1;
    pthread_mutex_unlock(&h->mu);
    maybe_notify_ended(h);
}

int mse_host_video_size(MseHost *h, int *w, int *ht)
{
    if (!h || !w || !ht)
        return 0;
    pthread_mutex_lock(&h->mu);
    *w = h->vw;
    *ht = h->vh;
    pthread_mutex_unlock(&h->mu);
    return *w > 0 && *ht > 0;
}

int mse_host_copy_frame(MseHost *h, uint8_t *dst, int cap, int *w, int *ht, int *gen)
{
    int n;
    if (!h)
        return 0;
    pthread_mutex_lock(&h->frame_mu);
    if (!h->last_bgra || h->last_w <= 0 || h->last_h <= 0) {
        pthread_mutex_unlock(&h->frame_mu);
        return 0;
    }
    if ((uint64_t)h->last_w * (uint64_t)h->last_h > (uint64_t)(INT_MAX / 4)) {
        pthread_mutex_unlock(&h->frame_mu);
        return 0;
    }
    n = h->last_w * h->last_h * 4;
    if (w)
        *w = h->last_w;
    if (ht)
        *ht = h->last_h;
    if (gen)
        *gen = h->frame_gen;
    if (dst && cap >= n)
        memcpy(dst, h->last_bgra, (size_t)n);
    pthread_mutex_unlock(&h->frame_mu);
    return n;
}

int mse_host_frames(MseHost *h)
{
    int n;
    if (!h)
        return 0;
    pthread_mutex_lock(&h->mu);
    n = h->frames;
    pthread_mutex_unlock(&h->mu);
    return n;
}

int64_t mse_host_samples_played(MseHost *h)
{
    int64_t n;
    if (!h)
        return 0;
    pthread_mutex_lock(&h->mu);
    n = h->samples_pulled;
    pthread_mutex_unlock(&h->mu);
    return n;
}

#define MSE_MIB ((size_t)1024 * 1024)

/* Chromium demuxer_memory_limit: default 12/150, medium 5/80, low 2/30, very-low 1/15. */
static size_t phys_mib(void)
{
#ifdef __APPLE__
    uint64_t mem = 0;
    size_t len = sizeof(mem);
    if (sysctlbyname("hw.memsize", &mem, &len, NULL, 0) != 0 || mem == 0)
        return 8192;
    return (size_t)(mem / MSE_MIB);
#else
    long pages = sysconf(_SC_PHYS_PAGES);
    long psz = sysconf(_SC_PAGE_SIZE);
    if (pages <= 0 || psz <= 0)
        return 4096;
    return (size_t)(((uint64_t)pages * (uint64_t)psz) / MSE_MIB);
#endif
}

static void mse_quota_caps(size_t *audio, size_t *video)
{
    static size_t a, v;
    static int once;
    size_t mib;
    if (once) {
        *audio = a;
        *video = v;
        return;
    }
    mib = phys_mib();
    /* Chrome Android: <=512 Go very-low; IsLowEndDevice (~1GiB) low;
       PartialLowEndModeOn3GbDevices medium; else default. */
    if (mib <= 512) {
        a = 1 * MSE_MIB;
        v = 15 * MSE_MIB;
    } else if (mib <= 1024) {
        a = 2 * MSE_MIB;
        v = 30 * MSE_MIB;
    } else if (mib <= 3072) {
        a = 5 * MSE_MIB;
        v = 80 * MSE_MIB;
    } else {
        a = 12 * MSE_MIB;
        v = 150 * MSE_MIB;
    }
    once = 1;
    mse_critf(MSE_LOG_INFO, "mse quota class ram=%zuMiB audio=%zu video=%zu", mib, a, v);
    *audio = a;
    *video = v;
}

int mse_host_prepare_append(MseHost *h, int audio, int n)
{
    size_t cap, have, qa, qv;
    double t;
    if (!h)
        return 0;
    if (n <= 0)
        return 1;
    mse_quota_caps(&qa, &qv);
    cap = audio ? qa : qv;
    have = mse_av_leftover_bytes(h->av, audio);
    if (have + (size_t)n <= cap)
        return 1;
    t = mse_host_current_time(h);
    if (t > 1.0)
        mse_av_evict_before(h->av, audio, t - 1.0, have + (size_t)n - cap);
    have = mse_av_leftover_bytes(h->av, audio);
    if (have + (size_t)n <= cap)
        return 1;
    mse_critf(MSE_LOG_WARN, "mse quota a=%d have=%zu need=%d cap=%zu clock=%.3f", audio, have, n,
              cap, t);
    return 0;
}

int mse_host_append(MseHost *h, int audio, const uint8_t *p, int n, double ts_off, double win_start, double win_end)
{
    MseAvIngest r;
    if (!h || !p)
        return 0;
    if (n <= 0)
        return 1;
    r = mse_av_append(h->av, audio, p, n, ts_off, win_start, win_end);
    mse_logf(
            "mse append a=%d bytes=%d n=%d init=%d first=%.3f last=%.3f tsOff=%.3f win=%.3g..%.3g clock=%.3f",
            audio, n, r.n, r.is_init, r.first, r.last, ts_off, win_start, win_end,
            mse_host_current_time(h));
    return r.n >= 0 ? 1 : 0;
}

void mse_host_remove(MseHost *h, int audio, double start, double end)
{
    double pairs[2];
    if (!h)
        return;
    mse_av_remove(h->av, audio, start, end);
    /* Audio-only (or a full video wipe) — stop showing the last decoded frame. */
    if (!audio && mse_av_buffered(h->av, 0, pairs, 1) <= 0) {
        uint8_t *black = NULL;
        int w = 0, ht = 0;
        pthread_mutex_lock(&h->frame_mu);
        if (h->last_bgra && h->last_w > 0 && h->last_h > 0 &&
            (uint64_t)h->last_w * (uint64_t)h->last_h <= (uint64_t)(INT_MAX / 4)) {
            memset(h->last_bgra, 0, (size_t)h->last_w * (size_t)h->last_h * 4);
            black = h->last_bgra;
            w = h->last_w;
            ht = h->last_h;
            h->frame_gen++;
        }
        pthread_mutex_unlock(&h->frame_mu);
        if (black) {
#ifdef __ANDROID__
            {
                ANativeWindow *win;
                pthread_mutex_lock(&h->mu);
                win = (ANativeWindow *)h->window;
                if (win)
                    ANativeWindow_acquire(win);
                pthread_mutex_unlock(&h->mu);
                if (win) {
                    blit_window(win, w, ht, black);
                    ANativeWindow_release(win);
                }
            }
#endif
            if (h->on_frame)
                h->on_frame(h->user, w, ht, black);
        }
        pthread_mutex_lock(&h->frame_mu);
        h->last_bgra = NULL;
        h->last_w = 0;
        h->last_h = 0;
        pthread_mutex_unlock(&h->frame_mu);
        pthread_mutex_lock(&h->mu);
        h->vw = 0;
        h->vh = 0;
        pthread_mutex_unlock(&h->mu);
    }
}

void mse_host_abort(MseHost *h, int audio)
{
    if (h)
        mse_av_abort(h->av, audio);
}

void mse_host_change_type(MseHost *h, int audio)
{
    if (h)
        mse_av_change_type(h->av, audio);
}

static void show_black(MseHost *h)
{
    uint8_t *black = NULL;
    int w = 0, ht = 0;
    if (!h)
        return;
    pthread_mutex_lock(&h->frame_mu);
    if (!h->last_bgra || h->last_w <= 0 || h->last_h <= 0) {
        pthread_mutex_unlock(&h->frame_mu);
        return;
    }
    memset(h->last_bgra, 0, (size_t)h->last_w * (size_t)h->last_h * 4);
    w = h->last_w;
    ht = h->last_h;
    black = h->last_bgra;
    if (black)
        h->frame_gen++;
    pthread_mutex_unlock(&h->frame_mu);
    if (!black)
        return;
#ifdef __ANDROID__
    {
        ANativeWindow *win;
        pthread_mutex_lock(&h->mu);
        win = (ANativeWindow *)h->window;
        if (win)
            ANativeWindow_acquire(win);
        pthread_mutex_unlock(&h->mu);
        if (win) {
            blit_window(win, w, ht, black);
            ANativeWindow_release(win);
        }
    }
#endif
    if (h->on_frame)
        h->on_frame(h->user, w, ht, black);
    pthread_mutex_lock(&h->mu);
    h->vw = w;
    h->vh = ht;
    pthread_mutex_unlock(&h->mu);
}

void mse_host_rest_video(MseHost *h, int on)
{
    double clock = 0;
    if (!h)
        return;
    clock = host_clock(h);
    mse_av_rest_video(h->av, on, clock);
    pthread_mutex_lock(&h->mu);
    pthread_cond_broadcast(&h->cv);
    pthread_mutex_unlock(&h->mu);
    if (on)
        show_black(h);
}

int mse_host_buffered(MseHost *h, int audio, double *pairs, int max_pairs)
{
    if (!h)
        return 0;
    return mse_av_buffered(h->av, audio, pairs, max_pairs);
}

void mse_host_set_volume(MseHost *h, float vol)
{
    if (h && h->fx)
        audio_fx_set_volume(h->fx, vol);
}

float mse_host_playback_rate(MseHost *h)
{
    if (!h)
        return 1.f;
    return h->speed > 0.f ? h->speed : 1.f;
}

void mse_host_set_speed(MseHost *h, float speed, float tempo)
{
    float old;
    if (!h)
        return;
    if (speed < 0.25f)
        speed = 0.25f;
    if (speed > 4.f)
        speed = 4.f;
    if (tempo < 0.25f)
        tempo = 0.25f;
    if (tempo > 4.f)
        tempo = 4.f;
    pthread_mutex_lock(&h->mu);
    old = h->speed > 0.f ? h->speed : 1.f;
    if (old == speed) {
        if (h->fx)
            audio_fx_set_speed(h->fx, tempo);
        pthread_mutex_unlock(&h->mu);
        return;
    }
    h->speed = speed;
#ifdef MSE_DAC
    /* Keep currentTime continuous: samples * new_rate = samples * old_rate. */
    if (h->samples_played > 0)
        h->samples_played = (int64_t)((double)h->samples_played * (double)old / (double)speed);
#else
    if (h->origin_ns > 0) {
        double n = now_ns();
        h->origin_ns = n - (n - h->origin_ns) * (double)old / (double)speed;
    }
#endif
    if (h->fx)
        audio_fx_set_speed(h->fx, tempo);
    pthread_mutex_unlock(&h->mu);
}

void mse_host_set_pitch(MseHost *h, float pitch)
{
    if (h && h->fx)
        audio_fx_set_pitch(h->fx, pitch);
}

void mse_host_set_eq(MseHost *h, const float bands_db[10])
{
    if (h && h->fx)
        audio_fx_set_eq(h->fx, bands_db);
}

void mse_host_set_bass(MseHost *h, float db)
{
    if (h && h->fx)
        audio_fx_set_bass(h->fx, db);
}

void mse_host_set_preamp(MseHost *h, float db)
{
    if (h && h->fx)
        audio_fx_set_preamp(h->fx, db);
}

void mse_host_set_virtualizer(MseHost *h, float width)
{
    if (h && h->fx)
        audio_fx_set_virtualizer(h->fx, width);
}

/* Frames colored at the DAC pull but still queued ahead of the playhead (same model as the clock). */
static int vis_delay_frames(MseHost *h)
{
    int delay = 0;
#ifdef MSE_DAC
    pthread_mutex_lock(&h->mu);
    if (h->aq_started && h->rate > 0 && h->aq_buf_bytes > 0) {
        int one = h->aq_buf_bytes / frame_bytes(h);
        int nbufs = 4;
        int extra = 0;
#ifdef __ANDROID__
        if (h->sl_nbufs > 0)
            nbufs = h->sl_nbufs;
#endif
        if (h->dac_tick_ns > 0)
            extra = (int)((now_ns() - h->dac_tick_ns) / 1e9 * h->rate);
        if (extra > one)
            extra = one;
        delay = one * nbufs - (extra > 0 ? extra : 0);
    }
    pthread_mutex_unlock(&h->mu);
#endif
    return delay;
}

int mse_host_pcm_levels(MseHost *h, float *out, int n)
{
    if (!h || !h->fx)
        return 0;
    return audio_fx_levels(h->fx, out, n, vis_delay_frames(h));
}

void mse_ios_log(const char *msg)
{
    if (!msg)
        return;
    mse_critf(MSE_LOG_INFO, "%s", msg);
#ifdef __APPLE__
    os_log_with_type(os_log_create("com.ymusic.mse", "v"), OS_LOG_TYPE_INFO, "%{public}s", msg);
#endif
}

#ifdef __APPLE__
void mse_ios_park_video(void)
{
    if (!g_ios_host || !g_ios_host->av)
        return;
    mse_ios_log("mse-v park");
    mse_av_park_video(g_ios_host->av);
}

void mse_ios_recover_playback(void)
{
    void *layer;
    double clock;
    if (!g_ios_host || !g_ios_host->av)
        return;
    layer = g_ios_host->window;
    if (mse_av_video_resting(g_ios_host->av)) {
        mse_ios_log("mse-v recover-skip audio-only rest");
        return;
    }
    /* Enqueue still succeeds on a frozen background layer, so picture-ok is
       true after a failed PiP leave. Parked sessions must unpark anyway. */
    if (!mse_av_video_parked(g_ios_host->av) && mse_vt_picture_ok(layer)) {
        mse_ios_log("mse-v recover-skip picture-ok");
        return;
    }
    mse_ios_log("mse-v recover bg-unpark");
    clock = host_clock(g_ios_host);
    mse_av_recover_display(g_ios_host->av, clock);
}
#endif
