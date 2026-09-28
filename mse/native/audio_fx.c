#include "audio_fx.h"

#include <math.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#define AFX_OVL_MAX 1024
#define AFX_FIR 32
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
    float ovl[2][AFX_OVL_MAX];
    int have_ovl;
    int ovl_n;
    int prev_off;
    double skip_frac;
    int path;
    float fir_h[AFX_FIR];
    float fir_z[2][AFX_FIR];
    float fir_cut;
    float levels[AFX_LEVELS];
    int level_i;
    float level_acc;
    int level_acc_n;
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
    fx->ovl_n = 0;
    fx->prev_off = 0;
    fx->skip_frac = 0;
    fx->path = 0;
    fx->fir_cut = 0;
    memset(fx->ovl, 0, sizeof(fx->ovl));
    memset(fx->fir_z, 0, sizeof(fx->fir_z));
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
    memset(fx->levels, 0, sizeof(fx->levels));
    fx->level_i = 0;
    fx->level_acc = 0;
    fx->level_acc_n = 0;
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

static void feed_levels(AudioFx *fx, const int16_t *s16, int frames, int nch)
{
    int i;
    int hop;
    pthread_mutex_lock(&fx->mu);
    hop = fx->rate / 50;
    if (hop < 64)
        hop = 64;
    for (i = 0; i < frames; i++) {
        float l = (float)s16[i * nch];
        float r = nch > 1 ? (float)s16[i * nch + 1] : l;
        float a = 0.5f * (l + r);
        float rms;
        fx->level_acc += a * a;
        fx->level_acc_n++;
        if (fx->level_acc_n < hop)
            continue;
        rms = sqrtf(fx->level_acc / (float)fx->level_acc_n) / 32768.f;
        rms *= 2.6f;
        if (rms > 1.f)
            rms = 1.f;
        fx->levels[fx->level_i] = rms;
        fx->level_i = (fx->level_i + 1) % AFX_LEVELS;
        fx->level_acc = 0;
        fx->level_acc_n = 0;
    }
    pthread_mutex_unlock(&fx->mu);
}

int audio_fx_levels(AudioFx *fx, float *out, int n)
{
    int i, start, got;
    if (!fx || !out || n <= 0)
        return 0;
    got = n < AFX_LEVELS ? n : AFX_LEVELS;
    pthread_mutex_lock(&fx->mu);
    start = fx->level_i - got;
    if (start < 0)
        start += AFX_LEVELS;
    for (i = 0; i < got; i++)
        out[i] = fx->levels[(start + i) % AFX_LEVELS];
    pthread_mutex_unlock(&fx->mu);
    return got;
}

void audio_fx_color(AudioFx *fx, int16_t *s16, int frames, int ch)
{
    int i, c, nch;
    if (!fx || !s16 || frames <= 0)
        return;
    nch = ch < 1 ? 1 : (ch > 2 ? 2 : ch);
    if (fx->color_on) {
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
    feed_levels(fx, s16, frames, nch);
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

/* Catmull-Rom, SoundTouch cubic interpolator. */
static float hermite_ch(const float *buf, int n, int nch, int c, double src)
{
    int i;
    float f, y0, y1, y2, y3, a0, a1, a2;
    if (n <= 3)
        return lerp_ch(buf, n, nch, c, src);
    i = (int)src;
    f = (float)(src - (double)i);
    if (i < 1 || i + 2 >= n)
        return lerp_ch(buf, n, nch, c, src);
    y0 = buf[(i - 1) * nch + c];
    y1 = buf[i * nch + c];
    y2 = buf[(i + 1) * nch + c];
    y3 = buf[(i + 2) * nch + c];
    a0 = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3;
    a1 = y0 - 2.5f * y1 + 2.f * y2 - 0.5f * y3;
    a2 = -0.5f * y0 + 0.5f * y2;
    return ((a0 * f + a1) * f + a2) * f + y1;
}

static void fir_design(AudioFx *fx, float cutoff)
{
    int i, m;
    float sum = 0.f;
    if (cutoff > 0.49f)
        cutoff = 0.49f;
    if (cutoff < 0.04f)
        cutoff = 0.04f;
    if (fabsf(fx->fir_cut - cutoff) < 0.002f)
        return;
    fx->fir_cut = cutoff;
    m = AFX_FIR / 2;
    for (i = 0; i < AFX_FIR; i++) {
        float x = (float)(i - m);
        float w = 0.54f - 0.46f * cosf(2.f * PI * (float)i / (float)(AFX_FIR - 1));
        float s = (x == 0.f) ? (2.f * cutoff) : (sinf(2.f * PI * cutoff * x) / (PI * x));
        fx->fir_h[i] = s * w;
        sum += fx->fir_h[i];
    }
    if (sum > 1e-12f) {
        for (i = 0; i < AFX_FIR; i++)
            fx->fir_h[i] /= sum;
    }
    memset(fx->fir_z, 0, sizeof(fx->fir_z));
}

static float fir_tick(AudioFx *fx, int c, float x)
{
    int i;
    float y = 0.f;
    memmove(&fx->fir_z[c][1], &fx->fir_z[c][0], (AFX_FIR - 1) * sizeof(float));
    fx->fir_z[c][0] = x;
    for (i = 0; i < AFX_FIR; i++)
        y += fx->fir_h[i] * fx->fir_z[c][i];
    return y;
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

static void st_params(int rate, float tempo, int *ovl, int *seq, int *seek)
{
    float seq_ms, seek_ms;
    int ov, sq, sk;
    if (rate < 8000)
        rate = 48000;
    ov = ((rate * 8) / 1000 + 7) & ~7;
    if (ov < 64)
        ov = 64;
    if (ov > AFX_OVL_MAX)
        ov = AFX_OVL_MAX;
    /* SoundTouch auto sequence 50–125ms, seek 15–25ms. */
    seq_ms = 110.f - 20.f * tempo;
    if (seq_ms < 50.f)
        seq_ms = 50.f;
    if (seq_ms > 125.f)
        seq_ms = 125.f;
    seek_ms = 23.f - 2.5f * tempo;
    if (seek_ms < 15.f)
        seek_ms = 15.f;
    if (seek_ms > 25.f)
        seek_ms = 25.f;
    sq = ((int)((float)rate * seq_ms / 1000.f) + 7) & ~7;
    if (sq < ov * 3)
        sq = ov * 3;
    sk = ((int)((float)rate * seek_ms / 1000.f) + 7) & ~7;
    if (sk < 64)
        sk = 64;
    *ovl = ov;
    *seq = sq;
    *seek = sk;
}

/* SoundTouch RateTransposer: cubic + FIR cutoff 0.5/max(rate,1). */
static void rate_xfer(AudioFx *fx, float *src, int *src_n, double *frac, float **dst, int *dst_n,
                      int *dst_cap, float rate, int nch, int use_aa)
{
    int drop;
    if (rate < 0.25f)
        rate = 0.25f;
    if (use_aa)
        fir_design(fx, 0.45f / (rate > 1.f ? rate : 1.f));
    while ((int)*frac + 2 < *src_n) {
        int c;
        if (ensure_buf(dst, dst_cap, *dst_n + 8, nch) != 0)
            return;
        for (c = 0; c < nch; c++) {
            float y = hermite_ch(src, *src_n, nch, c, *frac);
            if (use_aa)
                y = fir_tick(fx, c, y);
            (*dst)[*dst_n * nch + c] = y;
        }
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

static int rate_to_s16(AudioFx *fx, float *src, int *src_n, double *frac, float rate, int16_t *out,
                       int out_cap, int nch, int use_aa)
{
    int o = 0, drop;
    if (rate < 0.25f)
        rate = 0.25f;
    if (use_aa)
        fir_design(fx, 0.45f / (rate > 1.f ? rate : 1.f));
    while ((int)*frac + 2 < *src_n && o < out_cap) {
        int c;
        for (c = 0; c < nch; c++) {
            float y = hermite_ch(src, *src_n, nch, c, *frac);
            if (use_aa)
                y = fir_tick(fx, c, y);
            out[o * nch + c] = sat16(y);
        }
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

static float xcorr(const float *src, int off, int nch, const float ovl[2][AFX_OVL_MAX], int ovl_n)
{
    float e = 0.f, nrm = 0.f;
    int t, c;
    for (t = 0; t < ovl_n; t++) {
        for (c = 0; c < nch; c++) {
            float x = src[(off + t) * nch + c];
            e += x * ovl[c][t];
            nrm += x * x;
        }
    }
    if (nrm < 1e-12f)
        return 0.f;
    return e / sqrtf(nrm);
}

static int best_off(AudioFx *fx, const float *src, int src_n, int nch, int ovl_n, int seek_n)
{
    int i, best = 0, max, lo, hi;
    float beste = -1e30f;
    max = seek_n;
    if (max + ovl_n > src_n)
        max = src_n - ovl_n;
    if (max < 0)
        return 0;
    for (i = 0; i <= max; i += 4) {
        float e = xcorr(src, i, nch, fx->ovl, ovl_n);
        if (i == fx->prev_off)
            e += 0.1f;
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
        float e = xcorr(src, i, nch, fx->ovl, ovl_n);
        if (e > beste) {
            beste = e;
            best = i;
        }
    }
    fx->prev_off = best;
    return best;
}

static void tdstretch(AudioFx *fx, float *src, int *src_n, float tempo, float *dst, int *dst_n,
                      int dst_cap, int nch, int ovl_n, int seq_n, int seek_n)
{
    int t, c, off, skip, midlen, req;
    float nom;
    if (tempo < 0.26f)
        tempo = 0.26f;
    if (ovl_n > AFX_OVL_MAX)
        ovl_n = AFX_OVL_MAX;
    nom = tempo * (float)(seq_n - ovl_n);
    if (nom < 1.f)
        nom = 1.f;
    req = (int)(nom + 0.5f) + ovl_n;
    if (req < seq_n + seek_n)
        req = seq_n + seek_n;
    if (!fx->have_ovl) {
        if (*src_n < ovl_n)
            return;
        for (t = 0; t < ovl_n; t++) {
            for (c = 0; c < nch; c++)
                fx->ovl[c][t] = src[t * nch + c];
        }
        fx->have_ovl = 1;
        fx->ovl_n = ovl_n;
        consume(src, src_n, ovl_n, nch);
    }
    while (*src_n >= req && *dst_n + (seq_n - ovl_n) <= dst_cap) {
        off = best_off(fx, src, *src_n, nch, ovl_n, seek_n);
        for (t = 0; t < ovl_n; t++) {
            float a = (float)t / (float)ovl_n;
            float b = 1.f - a;
            for (c = 0; c < nch; c++)
                dst[(*dst_n + t) * nch + c] = src[(off + t) * nch + c] * a + fx->ovl[c][t] * b;
        }
        *dst_n += ovl_n;
        midlen = seq_n - 2 * ovl_n;
        if (midlen > 0) {
            if (*dst_n + midlen > dst_cap)
                midlen = dst_cap - *dst_n;
            if (off + ovl_n + midlen > *src_n)
                midlen = *src_n - off - ovl_n;
            if (midlen > 0) {
                memcpy(dst + *dst_n * nch, src + (off + ovl_n) * nch,
                       (size_t)midlen * (size_t)nch * sizeof(float));
                *dst_n += midlen;
            }
        }
        if (off + seq_n <= *src_n) {
            for (t = 0; t < ovl_n; t++) {
                for (c = 0; c < nch; c++)
                    fx->ovl[c][t] = src[(off + seq_n - ovl_n + t) * nch + c];
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
            fx->prev_off = 0;
            fx->fir_cut = 0;
            memset(fx->fir_z, 0, sizeof(fx->fir_z));
            fx->path = path;
        }
    }
    {
        int ovl_n, seq_n, seek_n;
        st_params(fx->rate, tempo, &ovl_n, &seq_n, &seek_n);
        if (!do_tempo) {
            produced = rate_to_s16(fx, fx->inbuf, &fx->in_n, &fx->in_frac, pitch, out, out_cap, nch,
                                   1);
        } else if (!do_rate) {
            if (ensure_buf(&fx->mid, &fx->mid_cap, fx->mid_n + seq_n * 2, nch) != 0) {
                pthread_mutex_unlock(&fx->mu);
                return 0;
            }
            tdstretch(fx, fx->inbuf, &fx->in_n, tempo, fx->mid, &fx->mid_n, fx->mid_cap, nch, ovl_n,
                      seq_n, seek_n);
            produced = fifo_to_s16(fx->mid, &fx->mid_n, out, out_cap, nch);
        } else if (pitch > 1.f) {
            if (ensure_buf(&fx->mid, &fx->mid_cap, fx->mid_n + seq_n * 2, nch) != 0) {
                pthread_mutex_unlock(&fx->mu);
                return 0;
            }
            tdstretch(fx, fx->inbuf, &fx->in_n, tempo, fx->mid, &fx->mid_n, fx->mid_cap, nch, ovl_n,
                      seq_n, seek_n);
            produced = rate_to_s16(fx, fx->mid, &fx->mid_n, &fx->mid_frac, pitch, out, out_cap, nch,
                                   1);
        } else {
            rate_xfer(fx, fx->inbuf, &fx->in_n, &fx->in_frac, &fx->mid, &fx->mid_n, &fx->mid_cap,
                      pitch, nch, 1);
            if (ensure_buf(&fx->xf, &fx->xf_cap, fx->xf_n + seq_n * 2, nch) != 0) {
                pthread_mutex_unlock(&fx->mu);
                return 0;
            }
            tdstretch(fx, fx->mid, &fx->mid_n, tempo, fx->xf, &fx->xf_n, fx->xf_cap, nch, ovl_n,
                      seq_n, seek_n);
            produced = fifo_to_s16(fx->xf, &fx->xf_n, out, out_cap, nch);
        }
    }
    pthread_mutex_unlock(&fx->mu);
    return produced;
}
