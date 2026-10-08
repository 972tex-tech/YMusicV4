#include "aq_pulse.h"

#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <pulse/simple.h>

#include "mse_ffmpeg.h"

#define AQP_MAX_BUFS 16
/* PipeWire's default quantum is 1024 frames @ 48k (~21ms); a target below ~2 quanta underruns. */
#define AQP_MIN_TARGET_S 0.040

typedef pa_simple *(*PaNewFn)(const char *, const char *, pa_stream_direction_t, const char *, const char *,
                              const pa_sample_spec *, const pa_channel_map *, const pa_buffer_attr *, int *);
typedef int (*PaWriteFn)(pa_simple *, const void *, size_t, int *);
typedef int (*PaFlushFn)(pa_simple *, int *);
typedef void (*PaFreeFn)(pa_simple *);

static struct {
    pthread_once_t once;
    PaNewFn new_;
    PaWriteFn write;
    PaFlushFn flush;
    PaFreeFn free_;
} g_pa = {PTHREAD_ONCE_INIT, NULL, NULL, NULL, NULL};

static void pa_load(void)
{
    void *lib = dlopen("libpulse-simple.so.0", RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        mse_critf(MSE_LOG_WARN, "aq-pulse: no libpulse-simple (%s), audio is silent", dlerror());
        return;
    }
    g_pa.new_ = (PaNewFn)dlsym(lib, "pa_simple_new");
    g_pa.write = (PaWriteFn)dlsym(lib, "pa_simple_write");
    g_pa.flush = (PaFlushFn)dlsym(lib, "pa_simple_flush");
    g_pa.free_ = (PaFreeFn)dlsym(lib, "pa_simple_free");
    if (!g_pa.new_ || !g_pa.write || !g_pa.flush || !g_pa.free_) {
        mse_critf(MSE_LOG_WARN, "aq-pulse: libpulse-simple lacks a symbol, audio is silent");
        g_pa.new_ = NULL;
    }
}

struct OpaqueAudioQueue {
    pthread_mutex_t mu;
    pthread_cond_t cv;
    pthread_t th;
    pa_simple *pa;
    AudioQueueOutputCallback cb;
    void *user;
    int rate;
    int ch;
    int frame_bytes;
    AudioQueueBufferRef bufs[AQP_MAX_BUFS];
    int nbufs;
    AudioQueueBufferRef fifo[AQP_MAX_BUFS];
    int fifo_r;
    int fifo_n;
    int running;
    int quit;
    int flush_req;
    float vol;
    float vol_target;
    float ramp_s;
    int ramp_left;
};

static void sleep_s(double s)
{
    struct timespec ts;
    if (s <= 0)
        return;
    ts.tv_sec = (time_t)s;
    ts.tv_nsec = (long)((s - (double)ts.tv_sec) * 1e9);
    nanosleep(&ts, NULL);
}

/* q->mu held. */
static void apply_gain(AudioQueueRef q, int16_t *s, int frames)
{
    int i, c;
    if (q->ramp_left <= 0 && q->vol >= 0.9999f)
        return;
    for (i = 0; i < frames; i++) {
        if (q->ramp_left > 0) {
            q->vol += (q->vol_target - q->vol) / (float)q->ramp_left;
            q->ramp_left--;
        }
        for (c = 0; c < q->ch; c++) {
            float v = (float)s[i * q->ch + c] * q->vol;
            s[i * q->ch + c] = (int16_t)(v > 32767.f ? 32767.f : (v < -32768.f ? -32768.f : v));
        }
    }
}

static void *aqp_loop(void *arg)
{
    AudioQueueRef q = (AudioQueueRef)arg;
    pthread_mutex_lock(&q->mu);
    for (;;) {
        AudioQueueBufferRef buf;
        int flush, err = 0, size;
        while (!q->quit && (!q->running || q->fifo_n == 0))
            pthread_cond_wait(&q->cv, &q->mu);
        if (q->quit)
            break;
        buf = q->fifo[q->fifo_r];
        q->fifo_r = (q->fifo_r + 1) % AQP_MAX_BUFS;
        q->fifo_n--;
        flush = q->flush_req;
        q->flush_req = 0;
        size = (int)buf->mAudioDataByteSize;
        if (size > (int)buf->mAudioDataBytesCapacity)
            size = (int)buf->mAudioDataBytesCapacity;
        size -= size % q->frame_bytes;
        apply_gain(q, (int16_t *)buf->mAudioData, size / q->frame_bytes);
        pthread_mutex_unlock(&q->mu);
        if (flush && q->pa)
            g_pa.flush(q->pa, &err);
        if (size > 0) {
            /* Blocks until the server has room: this is what paces the callbacks. */
            if (!q->pa || g_pa.write(q->pa, buf->mAudioData, (size_t)size, &err) < 0)
                sleep_s((double)size / (double)(q->frame_bytes * q->rate));
        }
        pthread_mutex_lock(&q->mu);
        if (q->quit)
            break;
        pthread_mutex_unlock(&q->mu);
        q->cb(q->user, q, buf);
        pthread_mutex_lock(&q->mu);
    }
    pthread_mutex_unlock(&q->mu);
    return NULL;
}

OSStatus AudioQueueNewOutput(const AudioStreamBasicDescription *fmt, AudioQueueOutputCallback cb, void *user,
                             void *run_loop, const void *run_loop_mode, UInt32 flags, AudioQueueRef *out)
{
    AudioQueueRef q;
    pa_sample_spec ss;
    pa_buffer_attr attr;
    double target_s;
    int err = 0;
    (void)run_loop;
    (void)run_loop_mode;
    (void)flags;
    *out = NULL;
    if (!fmt || !cb || fmt->mBitsPerChannel != 16 || fmt->mChannelsPerFrame < 1 || fmt->mSampleRate <= 0)
        return -50;
    pthread_once(&g_pa.once, pa_load);
    if (!g_pa.new_)
        return -1;
    q = (AudioQueueRef)calloc(1, sizeof(*q));
    if (!q)
        return -108;
    q->cb = cb;
    q->user = user;
    q->rate = (int)fmt->mSampleRate;
    q->ch = (int)fmt->mChannelsPerFrame;
    q->frame_bytes = q->ch * 2;
    q->vol = q->vol_target = 1.f;
    ss.format = PA_SAMPLE_S16NE;
    ss.rate = (uint32_t)q->rate;
    ss.channels = (uint8_t)q->ch;
    target_s = AQP_MIN_TARGET_S;
    attr.maxlength = (uint32_t)-1;
    attr.tlength = (uint32_t)(target_s * q->rate) * (uint32_t)q->frame_bytes;
    attr.prebuf = (uint32_t)-1;
    attr.minreq = (uint32_t)-1;
    attr.fragsize = (uint32_t)-1;
    q->pa = g_pa.new_(NULL, "YMusic", PA_STREAM_PLAYBACK, NULL, "Playback", &ss, NULL, &attr, &err);
    if (!q->pa) {
        mse_critf(MSE_LOG_WARN, "aq-pulse: pa_simple_new failed (%d)", err);
        free(q);
        return -1;
    }
    pthread_mutex_init(&q->mu, NULL);
    pthread_cond_init(&q->cv, NULL);
    if (pthread_create(&q->th, NULL, aqp_loop, q) != 0) {
        g_pa.free_(q->pa);
        pthread_cond_destroy(&q->cv);
        pthread_mutex_destroy(&q->mu);
        free(q);
        return -1;
    }
    *out = q;
    return 0;
}

OSStatus AudioQueueAllocateBuffer(AudioQueueRef q, UInt32 bytes, AudioQueueBufferRef *out)
{
    AudioQueueBufferRef buf;
    *out = NULL;
    pthread_mutex_lock(&q->mu);
    if (q->nbufs >= AQP_MAX_BUFS) {
        pthread_mutex_unlock(&q->mu);
        return -1;
    }
    buf = (AudioQueueBufferRef)calloc(1, sizeof(*buf) + bytes);
    if (!buf) {
        pthread_mutex_unlock(&q->mu);
        return -108;
    }
    buf->mAudioData = buf + 1;
    buf->mAudioDataBytesCapacity = bytes;
    q->bufs[q->nbufs++] = buf;
    pthread_mutex_unlock(&q->mu);
    *out = buf;
    return 0;
}

OSStatus AudioQueueEnqueueBuffer(AudioQueueRef q, AudioQueueBufferRef buf, UInt32 n_packet_descs,
                                 const void *packet_descs)
{
    (void)n_packet_descs;
    (void)packet_descs;
    pthread_mutex_lock(&q->mu);
    if (q->fifo_n >= AQP_MAX_BUFS) {
        pthread_mutex_unlock(&q->mu);
        return -1;
    }
    q->fifo[(q->fifo_r + q->fifo_n) % AQP_MAX_BUFS] = buf;
    q->fifo_n++;
    pthread_cond_broadcast(&q->cv);
    pthread_mutex_unlock(&q->mu);
    return 0;
}

OSStatus AudioQueueStart(AudioQueueRef q, const void *start_time)
{
    (void)start_time;
    pthread_mutex_lock(&q->mu);
    q->running = 1;
    pthread_cond_broadcast(&q->cv);
    pthread_mutex_unlock(&q->mu);
    return 0;
}

OSStatus AudioQueuePause(AudioQueueRef q)
{
    pthread_mutex_lock(&q->mu);
    q->running = 0;
    pthread_mutex_unlock(&q->mu);
    return 0;
}

OSStatus AudioQueueFlush(AudioQueueRef q)
{
    int i;
    pthread_mutex_lock(&q->mu);
    for (i = 0; i < q->fifo_n; i++) {
        AudioQueueBufferRef buf = q->fifo[(q->fifo_r + i) % AQP_MAX_BUFS];
        memset(buf->mAudioData, 0, buf->mAudioDataBytesCapacity);
    }
    q->flush_req = 1;
    pthread_mutex_unlock(&q->mu);
    return 0;
}

OSStatus AudioQueueStop(AudioQueueRef q, bool immediate)
{
    if (immediate)
        AudioQueueFlush(q);
    return AudioQueuePause(q);
}

OSStatus AudioQueueDispose(AudioQueueRef q, bool immediate)
{
    int i, err = 0;
    (void)immediate;
    if (!q)
        return 0;
    pthread_mutex_lock(&q->mu);
    q->quit = 1;
    pthread_cond_broadcast(&q->cv);
    pthread_mutex_unlock(&q->mu);
    pthread_join(q->th, NULL);
    g_pa.flush(q->pa, &err);
    g_pa.free_(q->pa);
    for (i = 0; i < q->nbufs; i++)
        free(q->bufs[i]);
    pthread_cond_destroy(&q->cv);
    pthread_mutex_destroy(&q->mu);
    free(q);
    return 0;
}

OSStatus AudioQueueSetParameter(AudioQueueRef q, AudioQueueParameterID id, AudioQueueParameterValue v)
{
    pthread_mutex_lock(&q->mu);
    if (id == kAudioQueueParam_VolumeRampTime) {
        q->ramp_s = v > 0 ? v : 0;
    } else if (id == kAudioQueueParam_Volume) {
        q->vol_target = v < 0 ? 0 : (v > 1 ? 1 : v);
        q->ramp_left = (int)(q->ramp_s * (float)q->rate);
        if (q->ramp_left <= 0)
            q->vol = q->vol_target;
    }
    pthread_mutex_unlock(&q->mu);
    return 0;
}

dispatch_time_t dispatch_time(dispatch_time_t when, int64_t delta_ns)
{
    return when + (dispatch_time_t)(delta_ns > 0 ? delta_ns : 0);
}

dispatch_queue_t dispatch_get_global_queue(long identifier, unsigned long flags)
{
    (void)identifier;
    (void)flags;
    return NULL;
}

typedef struct {
    dispatch_time_t delay_ns;
    void *ctx;
    dispatch_function_t fn;
} AfterJob;

static void *after_loop(void *arg)
{
    AfterJob job = *(AfterJob *)arg;
    free(arg);
    sleep_s((double)job.delay_ns / 1e9);
    job.fn(job.ctx);
    return NULL;
}

void dispatch_after_f(dispatch_time_t when, dispatch_queue_t queue, void *ctx, dispatch_function_t fn)
{
    pthread_t th;
    pthread_attr_t attr;
    AfterJob *job = (AfterJob *)malloc(sizeof(AfterJob));
    (void)queue;
    if (!job) {
        fn(ctx);
        return;
    }
    job->delay_ns = when;
    job->ctx = ctx;
    job->fn = fn;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&th, &attr, after_loop, job) != 0) {
        free(job);
        fn(ctx);
    }
    pthread_attr_destroy(&attr);
}
