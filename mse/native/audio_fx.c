#include "audio_fx.h"

#include <math.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#define AFX_WIN 1024
#define AFX_HOP 256
#define PI 3.14159265358979323846f

typedef struct {
    float b0, b1, b2, a1, a2;
    float z1[2], z2[2];
} Biquad;

struct AudioFx {
    pthread_mutex_t mu;
    int rate;
    float volume;
    float bands[AFX_BANDS];
    float bass_db;
    float width;
    float speed;
    float pitch;
    Biquad peak[AFX_BANDS];
    Biquad bass;
    int color_on;
    float *inbuf;
    int in_n;
    int in_cap;
    double in_frac;
    float *mid;
    int mid_n;
    int mid_cap;
    float ola[2][AFX_WIN];
    int ola_pos;
    float hann[AFX_WIN];
};

static const float k_hz[AFX_BANDS] = {
    31.f, 62.f, 125.f, 250.f, 500.f, 1000.f, 2000.f, 4000.f, 8000.f, 16000.f,
};

static float clampf(float x, float lo, float hi)
{
    if (x < lo)
        return lo;
    if (x > hi)
        return hi;
    return x;
}

static int16_t sat16(float x)
{
    if (x > 32767.f)
        return 32767;
    if (x < -32768.f)
        return -32768;
    return (int16_t)x;
}

static void bq_clear(Biquad *b)
{
    b->z1[0] = b->z1[1] = b->z2[0] = b->z2[1] = 0;
}

static void bq_peak(Biquad *b, float fs, float f0, float db, float q)
{
    float a, w0, c, s, alpha, b0, b1, b2, a0, a1, a2;
    bq_clear(b);
    if (fs < 1.f || f0 >= fs * 0.48f || fabsf(db) < 0.05f) {
        b->b0 = 1;
        b->b1 = b->b2 = b->a1 = b->a2 = 0;
        return;
    }
    a = powf(10.f, db / 40.f);
    w0 = 2.f * PI * f0 / fs;
    c = cosf(w0);
    s = sinf(w0);
    alpha = s / (2.f * q);
    b0 = 1.f + alpha * a;
    b1 = -2.f * c;
    b2 = 1.f - alpha * a;
    a0 = 1.f + alpha / a;
    a1 = -2.f * c;
    a2 = 1.f - alpha / a;
    b->b0 = b0 / a0;
    b->b1 = b1 / a0;
    b->b2 = b2 / a0;
    b->a1 = a1 / a0;
    b->a2 = a2 / a0;
}

static void bq_lowshelf(Biquad *b, float fs, float f0, float db)
{
    float a, w0, c, s, alpha, sq, b0, b1, b2, a0, a1, a2;
    bq_clear(b);
    if (fs < 1.f || fabsf(db) < 0.05f) {
        b->b0 = 1;
        b->b1 = b->b2 = b->a1 = b->a2 = 0;
        return;
    }
    a = powf(10.f, db / 40.f);
    w0 = 2.f * PI * f0 / fs;
    c = cosf(w0);
    s = sinf(w0);
    alpha = s / 2.f * sqrtf(2.f);
    sq = 2.f * sqrtf(a) * alpha;
    b0 = a * ((a + 1.f) - (a - 1.f) * c + sq);
    b1 = 2.f * a * ((a - 1.f) - (a + 1.f) * c);
    b2 = a * ((a + 1.f) - (a - 1.f) * c - sq);
    a0 = (a + 1.f) + (a - 1.f) * c + sq;
    a1 = -2.f * ((a - 1.f) + (a + 1.f) * c);
    a2 = (a + 1.f) + (a - 1.f) * c - sq;
    b->b0 = b0 / a0;
    b->b1 = b1 / a0;
    b->b2 = b2 / a0;
    b->a1 = a1 / a0;
    b->a2 = a2 / a0;
}

static float bq_tick(Biquad *b, int ch, float x)
{
    float y = b->b0 * x + b->z1[ch];
    b->z1[ch] = b->b1 * x - b->a1 * y + b->z2[ch];
    b->z2[ch] = b->b2 * x - b->a2 * y;
    return y;
}

static void rebuild(AudioFx *fx)
{
    int i;
    float fs = (float)(fx->rate > 0 ? fx->rate : 48000);
    fx->color_on = 0;
    for (i = 0; i < AFX_BANDS; i++) {
        bq_peak(&fx->peak[i], fs, k_hz[i], fx->bands[i], 1.4f);
        if (fabsf(fx->bands[i]) >= 0.05f)
            fx->color_on = 1;
    }
    bq_lowshelf(&fx->bass, fs, 90.f, fx->bass_db);
    if (fabsf(fx->bass_db) >= 0.05f)
        fx->color_on = 1;
    if (fabsf(fx->width - 1.f) >= 0.02f)
        fx->color_on = 1;
    if (fabsf(fx->volume - 1.f) >= 0.001f)
        fx->color_on = 1;
}

AudioFx *audio_fx_create(void)
{
    AudioFx *fx = (AudioFx *)calloc(1, sizeof(AudioFx));
    int i;
    if (!fx)
        return NULL;
    fx->rate = 48000;
    fx->volume = 1.f;
    fx->width = 1.f;
    fx->speed = 1.f;
    fx->pitch = 1.f;
    if (pthread_mutex_init(&fx->mu, NULL) != 0) {
        free(fx);
        return NULL;
    }
    for (i = 0; i < AFX_WIN; i++)
        fx->hann[i] = 0.5f - 0.5f * cosf(2.f * PI * (float)i / (float)(AFX_WIN - 1));
    rebuild(fx);
    return fx;
}

static void stretch_reset_unlocked(AudioFx *fx)
{
    fx->in_n = 0;
    fx->in_frac = 0;
    fx->mid_n = 0;
    fx->ola_pos = 0;
    memset(fx->ola, 0, sizeof(fx->ola));
}

void audio_fx_destroy(AudioFx *fx)
{
    if (!fx)
        return;
    pthread_mutex_lock(&fx->mu);
    free(fx->inbuf);
    fx->inbuf = NULL;
    free(fx->mid);
    fx->mid = NULL;
    pthread_mutex_unlock(&fx->mu);
    pthread_mutex_destroy(&fx->mu);
    free(fx);
}

void audio_fx_reset(AudioFx *fx)
{
    int i;
    if (!fx)
        return;
    pthread_mutex_lock(&fx->mu);
    stretch_reset_unlocked(fx);
    for (i = 0; i < AFX_BANDS; i++)
        bq_clear(&fx->peak[i]);
    bq_clear(&fx->bass);
    pthread_mutex_unlock(&fx->mu);
}

void audio_fx_set_rate(AudioFx *fx, int rate)
{
    if (!fx || rate <= 0)
        return;
    pthread_mutex_lock(&fx->mu);
    if (fx->rate == rate) {
        pthread_mutex_unlock(&fx->mu);
        return;
    }
    fx->rate = rate;
    rebuild(fx);
    stretch_reset_unlocked(fx);
    pthread_mutex_unlock(&fx->mu);
}

void audio_fx_set_volume(AudioFx *fx, float vol)
{
    if (!fx)
        return;
    fx->volume = clampf(vol, 0.f, 1.f);
    rebuild(fx);
}

void audio_fx_set_eq(AudioFx *fx, const float bands_db[AFX_BANDS])
{
    int i;
    if (!fx || !bands_db)
        return;
    for (i = 0; i < AFX_BANDS; i++)
        fx->bands[i] = clampf(bands_db[i], -12.f, 12.f);
    rebuild(fx);
}

void audio_fx_set_bass(AudioFx *fx, float db)
{
    if (!fx)
        return;
    fx->bass_db = clampf(db, 0.f, 12.f);
    rebuild(fx);
}

void audio_fx_set_virtualizer(AudioFx *fx, float width)
{
    if (!fx)
        return;
    fx->width = clampf(width, 0.f, 2.f);
    rebuild(fx);
}

void audio_fx_set_speed(AudioFx *fx, float speed)
{
    if (!fx)
        return;
    pthread_mutex_lock(&fx->mu);
    fx->speed = clampf(speed, 0.25f, 4.f);
    pthread_mutex_unlock(&fx->mu);
}

void audio_fx_set_pitch(AudioFx *fx, float pitch)
{
    if (!fx)
        return;
    pthread_mutex_lock(&fx->mu);
    fx->pitch = clampf(pitch, 0.5f, 2.f);
    pthread_mutex_unlock(&fx->mu);
}

void audio_fx_color(AudioFx *fx, int16_t *s16, int frames, int ch)
{
    int i, c, nch;
    if (!fx || !s16 || frames <= 0)
        return;
    nch = ch < 1 ? 1 : (ch > 2 ? 2 : ch);
    if (!fx->color_on)
        return;
    for (i = 0; i < frames; i++) {
        float l, r, mid, side, y;
        l = (float)s16[i * nch];
        r = nch > 1 ? (float)s16[i * nch + 1] : l;
        for (c = 0; c < AFX_BANDS; c++) {
            l = bq_tick(&fx->peak[c], 0, l);
            if (nch > 1)
                r = bq_tick(&fx->peak[c], 1, r);
        }
        l = bq_tick(&fx->bass, 0, l);
        if (nch > 1)
            r = bq_tick(&fx->bass, 1, r);
        if (nch > 1 && fabsf(fx->width - 1.f) >= 0.02f) {
            mid = 0.5f * (l + r);
            side = 0.5f * (l - r) * fx->width;
            l = mid + side;
            r = mid - side;
        }
        y = fx->volume;
        s16[i * nch] = sat16(l * y);
        if (nch > 1)
            s16[i * nch + 1] = sat16(r * y);
    }
}

static int ensure_buf(float **buf, int *cap, int need, int ch)
{
    float *nbuf;
    if (need <= *cap)
        return 0;
    if (need < 4096)
        need = 4096;
    nbuf = (float *)realloc(*buf, (size_t)need * (size_t)ch * sizeof(float));
    if (!nbuf)
        return -1;
    *buf = nbuf;
    *cap = need;
    return 0;
}

static float lerp_ch(const float *buf, int n, int nch, int c, double src)
{
    int i;
    float f;
    if (n <= 0)
        return 0;
    if (src <= 0.0)
        return buf[c];
    i = (int)src;
    f = (float)(src - (double)i);
    if (i >= n - 1)
        return buf[(n - 1) * nch + c];
    return buf[i * nch + c] * (1.f - f) + buf[(i + 1) * nch + c] * f;
}

/* SoundTouch RateTransposer: read original at increment `rate` (= virtualPitch). */
static void rate_to_mid(AudioFx *fx, float rate, int nch)
{
    int drop;
    if (rate < 0.25f)
        rate = 0.25f;
    while ((int)fx->in_frac + 1 < fx->in_n) {
        int c;
        if (ensure_buf(&fx->mid, &fx->mid_cap, fx->mid_n + 8, nch) != 0)
            return;
        for (c = 0; c < nch; c++)
            fx->mid[fx->mid_n * nch + c] = lerp_ch(fx->inbuf, fx->in_n, nch, c, fx->in_frac);
        fx->mid_n++;
        fx->in_frac += (double)rate;
    }
    drop = (int)fx->in_frac;
    if (drop > 0 && drop <= fx->in_n) {
        fx->in_n -= drop;
        fx->in_frac -= (double)drop;
        if (fx->in_n > 0)
            memmove(fx->inbuf, fx->inbuf + drop * nch, (size_t)fx->in_n * (size_t)nch * sizeof(float));
    }
}

/* SoundTouch Stretch (WSOLA, no cross-correlation seek). hop_in = HOP * tempo. */
static int wsola_from(AudioFx *fx, float *src, int *src_n, float hop_in, int16_t *out, int out_cap,
                      int nch, int o)
{
    int produced = o;
    while (*src_n >= AFX_WIN && produced + AFX_HOP <= out_cap) {
        int t, c2;
        int skip = (int)(hop_in + 0.5f);
        if (skip < 1)
            skip = 1;
        if (skip > *src_n)
            skip = *src_n;
        for (t = 0; t < AFX_WIN; t++) {
            float w = fx->hann[t];
            for (c2 = 0; c2 < nch; c2++)
                fx->ola[c2][t] += src[t * nch + c2] * w;
        }
        for (t = 0; t < AFX_HOP; t++) {
            for (c2 = 0; c2 < nch; c2++)
                out[(produced + t) * nch + c2] = sat16(fx->ola[c2][t]);
        }
        for (t = 0; t < AFX_WIN - AFX_HOP; t++) {
            for (c2 = 0; c2 < nch; c2++)
                fx->ola[c2][t] = fx->ola[c2][t + AFX_HOP];
        }
        for (t = AFX_WIN - AFX_HOP; t < AFX_WIN; t++)
            fx->ola[0][t] = fx->ola[1][t] = 0;
        *src_n -= skip;
        if (*src_n > 0)
            memmove(src, src + skip * nch, (size_t)*src_n * (size_t)nch * sizeof(float));
        produced += AFX_HOP;
    }
    return produced;
}

int audio_fx_stretch(AudioFx *fx, const int16_t *in, int in_frames, int16_t *out, int out_cap, int ch)
{
    float pitch, tempo, hop_in;
    int nch, i, c, produced = 0, do_pitch;
    if (!fx || !out || out_cap <= 0)
        return 0;
    nch = ch < 1 ? 1 : (ch > 2 ? 2 : ch);
    pthread_mutex_lock(&fx->mu);
    /* SoundTouch: _rate = virtualRate * virtualPitch, _tempo = virtualTempo / virtualPitch.
       virtualRate=1, virtualTempo=playbackRate (fx->speed), virtualPitch=fx->pitch. */
    tempo = fx->speed;
    pitch = fx->pitch;
    if (tempo < 0.26f)
        tempo = 0.26f;
    if (pitch < 0.5f)
        pitch = 0.5f;
    if (pitch > 2.f)
        pitch = 2.f;
    do_pitch = fabsf(pitch - 1.f) >= 0.02f;
    if (fabsf(tempo - 1.f) < 0.02f && !do_pitch) {
        int n = in_frames < out_cap ? in_frames : out_cap;
        if (in && n > 0)
            memcpy(out, in, (size_t)n * (size_t)nch * sizeof(int16_t));
        pthread_mutex_unlock(&fx->mu);
        return n;
    }
    if (in && in_frames > 0) {
        if (ensure_buf(&fx->inbuf, &fx->in_cap, fx->in_n + in_frames + 8, nch) != 0) {
            pthread_mutex_unlock(&fx->mu);
            return 0;
        }
        for (i = 0; i < in_frames; i++) {
            int dst = fx->in_n + i;
            for (c = 0; c < nch; c++)
                fx->inbuf[dst * nch + c] = (float)in[i * nch + c];
        }
        fx->in_n += in_frames;
    }
    if (do_pitch) {
        /* _rate = pitch, _tempo = speed/pitch. Resample then WSOLA (rate <= 1) or
           the same order is fine here: hop_in is applied on the resampled stream. */
        rate_to_mid(fx, pitch, nch);
        hop_in = (float)AFX_HOP * (tempo / pitch);
        produced = wsola_from(fx, fx->mid, &fx->mid_n, hop_in, out, out_cap, nch, 0);
    } else {
        hop_in = (float)AFX_HOP * tempo;
        produced = wsola_from(fx, fx->inbuf, &fx->in_n, hop_in, out, out_cap, nch, 0);
    }
    pthread_mutex_unlock(&fx->mu);
    return produced;
}
