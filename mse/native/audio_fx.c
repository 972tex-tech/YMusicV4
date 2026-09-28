#include "audio_fx.h"

#include <math.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#define AFX_OVL 256
#define AFX_SEEK 192
#define AFX_SEQ 1024
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
    double mid_frac;
    float *xf;
    int xf_n;
    int xf_cap;
    float ovl[2][AFX_OVL];
    int have_ovl;
    double skip_frac;
    int path;
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
    rebuild(fx);
    return fx;
}

static void stretch_reset_unlocked(AudioFx *fx)
{
    fx->in_n = 0;
    fx->in_frac = 0;
    fx->mid_n = 0;
    fx->mid_frac = 0;
    fx->xf_n = 0;
    fx->have_ovl = 0;
    fx->skip_frac = 0;
    fx->path = 0;
    memset(fx->ovl, 0, sizeof(fx->ovl));
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
    free(fx->xf);
    fx->xf = NULL;
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

static void consume(float *buf, int *n, int drop, int nch)
{
    if (drop <= 0)
        return;
    if (drop > *n)
        drop = *n;
    *n -= drop;
    if (*n > 0)
        memmove(buf, buf + drop * nch, (size_t)*n * (size_t)nch * sizeof(float));
}

/* SoundTouch RateTransposer: linear interpolate, step = rate. */
static void rate_xfer(float *src, int *src_n, double *frac, float **dst, int *dst_n, int *dst_cap,
                      float rate, int nch)
{
    int drop;
    if (rate < 0.25f)
        rate = 0.25f;
    while ((int)*frac + 1 < *src_n) {
        int c;
        if (ensure_buf(dst, dst_cap, *dst_n + 8, nch) != 0)
            return;
        for (c = 0; c < nch; c++)
            (*dst)[*dst_n * nch + c] = lerp_ch(src, *src_n, nch, c, *frac);
        (*dst_n)++;
        *frac += (double)rate;
    }
    drop = (int)*frac;
    if (drop > 0) {
        consume(src, src_n, drop, nch);
        *frac -= (double)drop;
        if (*frac < 0)
            *frac = 0;
    }
}

static int rate_to_s16(float *src, int *src_n, double *frac, float rate, int16_t *out, int out_cap,
                       int nch)
{
    int o = 0, drop;
    if (rate < 0.25f)
        rate = 0.25f;
    while ((int)*frac + 1 < *src_n && o < out_cap) {
        int c;
        for (c = 0; c < nch; c++)
            out[o * nch + c] = sat16(lerp_ch(src, *src_n, nch, c, *frac));
        o++;
        *frac += (double)rate;
    }
    drop = (int)*frac;
    if (drop > 0) {
        consume(src, src_n, drop, nch);
        *frac -= (double)drop;
        if (*frac < 0)
            *frac = 0;
    }
    return o;
}

static float xcorr(const float *src, int off, int nch, const float ovl[2][AFX_OVL])
{
    float e = 0;
    int t, c;
    for (t = 0; t < AFX_OVL; t++) {
        for (c = 0; c < nch; c++)
            e += src[(off + t) * nch + c] * ovl[c][t];
    }
    return e;
}

static int best_off(const float *src, int src_n, int nch, const float ovl[2][AFX_OVL])
{
    int i, best = 0, max, lo, hi;
    float beste = -1e30f;
    max = AFX_SEEK;
    if (max + AFX_OVL > src_n)
        max = src_n - AFX_OVL;
    if (max < 0)
        return 0;
    for (i = 0; i <= max; i += 4) {
        float e = xcorr(src, i, nch, ovl);
        if (e > beste) {
            beste = e;
            best = i;
        }
    }
    lo = best - 3;
    hi = best + 3;
    if (lo < 0)
        lo = 0;
    if (hi > max)
        hi = max;
    for (i = lo; i <= hi; i++) {
        float e = xcorr(src, i, nch, ovl);
        if (e > beste) {
            beste = e;
            best = i;
        }
    }
    return best;
}

/* SoundTouch Stretch: overlap-add with correlation seek, skip = tempo*(seq-ovl). */
static void tdstretch(AudioFx *fx, float *src, int *src_n, float tempo, float *dst, int *dst_n,
                      int dst_cap, int nch)
{
    int t, c, off, skip, midlen, req;
    float nom;
    if (tempo < 0.26f)
        tempo = 0.26f;
    nom = tempo * (float)(AFX_SEQ - AFX_OVL);
    if (nom < 1.f)
        nom = 1.f;
    req = (int)(nom + 0.5f) + AFX_OVL;
    if (req < AFX_SEQ + AFX_SEEK)
        req = AFX_SEQ + AFX_SEEK;
    if (!fx->have_ovl) {
        if (*src_n < AFX_OVL)
            return;
        for (t = 0; t < AFX_OVL; t++) {
            for (c = 0; c < nch; c++)
                fx->ovl[c][t] = src[t * nch + c];
        }
        fx->have_ovl = 1;
        consume(src, src_n, AFX_OVL, nch);
    }
    while (*src_n >= req && *dst_n + (AFX_SEQ - AFX_OVL) <= dst_cap) {
        off = best_off(src, *src_n, nch, fx->ovl);
        for (t = 0; t < AFX_OVL; t++) {
            float a = (float)t / (float)AFX_OVL;
            float b = 1.f - a;
            for (c = 0; c < nch; c++)
                dst[(*dst_n + t) * nch + c] =
                    src[(off + t) * nch + c] * a + fx->ovl[c][t] * b;
        }
        *dst_n += AFX_OVL;
        midlen = AFX_SEQ - 2 * AFX_OVL;
        if (midlen > 0) {
            if (*dst_n + midlen > dst_cap)
                midlen = dst_cap - *dst_n;
            if (off + AFX_OVL + midlen > *src_n)
                midlen = *src_n - off - AFX_OVL;
            if (midlen > 0) {
                memcpy(dst + *dst_n * nch, src + (off + AFX_OVL) * nch,
                       (size_t)midlen * (size_t)nch * sizeof(float));
                *dst_n += midlen;
            }
        }
        if (off + AFX_SEQ - AFX_OVL + AFX_OVL <= *src_n) {
            for (t = 0; t < AFX_OVL; t++) {
                for (c = 0; c < nch; c++)
                    fx->ovl[c][t] = src[(off + AFX_SEQ - AFX_OVL + t) * nch + c];
            }
        }
        fx->skip_frac += (double)nom;
        skip = (int)fx->skip_frac;
        fx->skip_frac -= (double)skip;
        consume(src, src_n, skip, nch);
    }
}

static int fifo_to_s16(float *src, int *src_n, int16_t *out, int out_cap, int nch)
{
    int n = *src_n < out_cap ? *src_n : out_cap;
    int i, c;
    for (i = 0; i < n; i++) {
        for (c = 0; c < nch; c++)
            out[i * nch + c] = sat16(src[i * nch + c]);
    }
    consume(src, src_n, n, nch);
    return n;
}

int audio_fx_stretch(AudioFx *fx, const int16_t *in, int in_frames, int16_t *out, int out_cap, int ch)
{
    float pitch, speed, tempo;
    int nch, i, c, produced = 0, do_rate, do_tempo;
    if (!fx || !out || out_cap <= 0)
        return 0;
    nch = ch < 1 ? 1 : (ch > 2 ? 2 : ch);
    pthread_mutex_lock(&fx->mu);
    speed = fx->speed;
    pitch = fx->pitch;
    if (speed < 0.26f)
        speed = 0.26f;
    if (pitch < 0.5f)
        pitch = 0.5f;
    if (pitch > 2.f)
        pitch = 2.f;
    /* SoundTouch: _rate = pitch, _tempo = speed/pitch. */
    tempo = speed / pitch;
    do_rate = fabsf(pitch - 1.f) >= 0.02f;
    do_tempo = fabsf(tempo - 1.f) >= 0.02f;
    if (!do_rate && !do_tempo) {
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
    {
        int path = !do_tempo ? 1 : !do_rate ? 2 : pitch > 1.f ? 3 : 4;
        if (path != fx->path) {
            fx->have_ovl = 0;
            fx->skip_frac = 0;
            fx->path = path;
        }
    }
    if (!do_tempo) {
        /* speed == pitch: resample only. */
        produced = rate_to_s16(fx->inbuf, &fx->in_n, &fx->in_frac, pitch, out, out_cap, nch);
    } else if (!do_rate) {
        /* pitch == 1: WSOLA only. */
        if (ensure_buf(&fx->mid, &fx->mid_cap, fx->mid_n + AFX_SEQ * 2, nch) != 0) {
            pthread_mutex_unlock(&fx->mu);
            return 0;
        }
        tdstretch(fx, fx->inbuf, &fx->in_n, tempo, fx->mid, &fx->mid_n, fx->mid_cap, nch);
        produced = fifo_to_s16(fx->mid, &fx->mid_n, out, out_cap, nch);
    } else if (pitch > 1.f) {
        /* SoundTouch rate>1: stretch then transpose. */
        if (ensure_buf(&fx->mid, &fx->mid_cap, fx->mid_n + AFX_SEQ * 2, nch) != 0) {
            pthread_mutex_unlock(&fx->mu);
            return 0;
        }
        tdstretch(fx, fx->inbuf, &fx->in_n, tempo, fx->mid, &fx->mid_n, fx->mid_cap, nch);
        produced = rate_to_s16(fx->mid, &fx->mid_n, &fx->mid_frac, pitch, out, out_cap, nch);
    } else {
        /* rate<=1: transpose then stretch. */
        rate_xfer(fx->inbuf, &fx->in_n, &fx->in_frac, &fx->mid, &fx->mid_n, &fx->mid_cap, pitch,
                  nch);
        if (ensure_buf(&fx->xf, &fx->xf_cap, fx->xf_n + AFX_SEQ * 2, nch) != 0) {
            pthread_mutex_unlock(&fx->mu);
            return 0;
        }
        tdstretch(fx, fx->mid, &fx->mid_n, tempo, fx->xf, &fx->xf_n, fx->xf_cap, nch);
        produced = fifo_to_s16(fx->xf, &fx->xf_n, out, out_cap, nch);
    }
    pthread_mutex_unlock(&fx->mu);
    return produced;
}
