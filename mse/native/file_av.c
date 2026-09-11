#include "file_av.h"

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/frame.h>
#include <libavutil/imgutils.h>
#include <libavutil/hwcontext.h>
#include <libavutil/opt.h>
#include <libavutil/pixdesc.h>
#include <libavutil/pixfmt.h>
#include <libavutil/rational.h>
#include <libavutil/samplefmt.h>
#include <libavutil/time.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifdef __APPLE__
#include <mach/mach_time.h>
#endif

#define OUT_RATE 48000
#define OUT_CH 2


enum { ST_AUDIO = 0, ST_VIDEO = 1, ST_TEXT = 2 };

typedef struct {
    int kind;
    int index;
    int w, h;
    char lang[16];
    char codec[32];
} StreamInfo;

typedef struct {
    AVPacket **p;
    int n, cap, rd;
} Pq;

struct FileAv {
    pthread_mutex_t mu;
    pthread_cond_t cv;
    pthread_t th;
    int th_on;
    int stop;
    int opened;
    int playing;
    int in_pump;
    int ended;
    int want_video;
    int audio_index; /* -1 = first audio */
    int blit_w;
    int blit_h;
    double speed;
    double hold;
    double origin_ns;
    double duration;
    double last_prog_ns;
    char err[256];

    AVFormatContext *fmt;
    AVCodecContext *adec;
    AVCodecContext *vdec;
    int a_st, v_st;
    SwrContext *swr;
    SwrContext *swr_speed;
    double swr_speed_in;
    struct SwsContext *sws;
    int sws_dw, sws_dh, sws_sw, sws_sh, sws_fmt;
    uint8_t *bgra;
    int bgra_n;
    int vw, vh;
    Pq aq, vq;
    AVFrame *afr, *vfr, *vhold, *vsw;
    StreamInfo *streams;
    int nstreams;

    FileAvFrameFn on_frame;
    FileAvPcmFn on_pcm;
    FileAvProgressFn on_prog;
    FileAvEventFn on_event;
    void *user;
};

static double now_ns(void)
{
#ifdef __APPLE__
    static mach_timebase_info_data_t tb;
    static int once;
    if (!once) {
        mach_timebase_info(&tb);
        once = 1;
    }
    return (double)mach_absolute_time() * (double)tb.numer / (double)tb.denom;
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1e9 + (double)ts.tv_nsec;
#endif
}

static void set_err(FileAv *p, const char *msg)
{
    snprintf(p->err, sizeof(p->err), "%s", msg ? msg : "");
}

static void pq_clear(Pq *q)
{
    int i;
    /* Popped slots (0..rd-1) were already av_packet_free'd. Freeing them
       again is the seek SIGSEGV in av_freep. */
    for (i = q->rd; i < q->n; i++)
        av_packet_free(&q->p[i]);
    free(q->p);
    q->p = NULL;
    q->n = q->cap = q->rd = 0;
}

static int pq_push(Pq *q, AVPacket *pkt)
{
    if (q->n >= q->cap) {
        int cap = q->cap ? q->cap * 2 : 16;
        AVPacket **n = realloc(q->p, (size_t)cap * sizeof(*n));
        if (!n)
            return -1;
        q->p = n;
        q->cap = cap;
    }
    q->p[q->n++] = pkt;
    return 0;
}

static AVPacket *pq_pop(Pq *q)
{
    AVPacket *pkt;
    if (q->rd >= q->n)
        return NULL;
    pkt = q->p[q->rd];
    q->p[q->rd] = NULL;
    q->rd++;
    if (q->rd == q->n)
        q->rd = q->n = 0;
    return pkt;
}

static const AVCodec *find_vdec(enum AVCodecID id)
{
    const AVCodec *c;
    if (id == AV_CODEC_ID_VP9) {
        c = avcodec_find_decoder_by_name("libvpx-vp9");
        return c;
    }
    if (id == AV_CODEC_ID_VP8)
        return avcodec_find_decoder_by_name("libvpx");
    if (id == AV_CODEC_ID_AV1) {
        c = avcodec_find_decoder_by_name("libdav1d");
        if (c)
            return c;
    }
    return avcodec_find_decoder(id);
}

/* Prefer VideoToolbox/etc so 4K HEVC can keep up; blit still goes through swscale
 * after av_hwframe_transfer_data. Fall back to the first software pixfmt. */
static enum AVPixelFormat get_format_prefer_hw(AVCodecContext *s, const enum AVPixelFormat *fmt)
{
    const enum AVPixelFormat *p;
    enum AVPixelFormat sw = AV_PIX_FMT_NONE;
    (void)s;
    for (p = fmt; *p != AV_PIX_FMT_NONE; p++) {
        const AVPixFmtDescriptor *d = av_pix_fmt_desc_get(*p);
        if (!d)
            continue;
        if (d->flags & AV_PIX_FMT_FLAG_HWACCEL)
            return *p;
        if (sw == AV_PIX_FMT_NONE)
            sw = *p;
    }
    return sw;
}

static AVCodecContext *open_codec(AVStream *st, int video)
{
    const AVCodec *c = video ? find_vdec(st->codecpar->codec_id)
                             : avcodec_find_decoder(st->codecpar->codec_id);
    AVCodecContext *ctx;
    if (!c)
        return NULL;
    ctx = avcodec_alloc_context3(c);
    if (!ctx)
        return NULL;
    if (avcodec_parameters_to_context(ctx, st->codecpar) < 0) {
        avcodec_free_context(&ctx);
        return NULL;
    }
    ctx->pkt_timebase = st->time_base;
    if (video) {
        AVBufferRef *hw = NULL;
        ctx->get_format = get_format_prefer_hw;
        if (av_hwdevice_ctx_create(&hw, AV_HWDEVICE_TYPE_VIDEOTOOLBOX, NULL, NULL, 0) == 0)
            ctx->hw_device_ctx = hw;
        else
            ctx->thread_count = 0;
    }
    if (avcodec_open2(ctx, c, NULL) < 0) {
        avcodec_free_context(&ctx);
        return NULL;
    }
    return ctx;
}

static double frame_pts(AVStream *st, AVFrame *fr, double fallback)
{
    int64_t t = fr->best_effort_timestamp;
    if (t == AV_NOPTS_VALUE)
        t = fr->pts;
    if (t == AV_NOPTS_VALUE || !st)
        return fallback;
    return t * av_q2d(st->time_base);
}

static double media_time_locked(FileAv *p)
{
    double t = p->hold;
    if (p->playing && p->origin_ns > 0)
        t += (now_ns() - p->origin_ns) / 1e9 * p->speed;
    if (t < 0)
        t = 0;
    if (p->duration > 0 && t > p->duration)
        t = p->duration;
    return t;
}

static void free_codecs(FileAv *p)
{
    avcodec_free_context(&p->adec);
    avcodec_free_context(&p->vdec);
    swr_free(&p->swr);
    swr_free(&p->swr_speed);
    p->swr_speed_in = 0;
    if (p->sws) {
        sws_freeContext(p->sws);
        p->sws = NULL;
    }
    p->sws_dw = p->sws_dh = p->sws_sw = p->sws_sh = 0;
    p->sws_fmt = -1;
    av_freep(&p->bgra);
    p->bgra_n = 0;
    pq_clear(&p->aq);
    pq_clear(&p->vq);
    av_frame_free(&p->vhold);
    av_frame_free(&p->vsw);
}

static void collect_streams(FileAv *p)
{
    unsigned i;
    free(p->streams);
    p->streams = NULL;
    p->nstreams = 0;
    if (!p->fmt)
        return;
    p->streams = calloc(p->fmt->nb_streams, sizeof(StreamInfo));
    if (!p->streams)
        return;
    for (i = 0; i < p->fmt->nb_streams; i++) {
        AVStream *st = p->fmt->streams[i];
        AVDictionaryEntry *lang;
        StreamInfo *s = &p->streams[p->nstreams];
        const char *name = avcodec_get_name(st->codecpar->codec_id);
        if (st->codecpar->codec_type == AVMEDIA_TYPE_AUDIO)
            s->kind = ST_AUDIO;
        else if (st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            if (st->disposition & AV_DISPOSITION_ATTACHED_PIC)
                continue;
            s->kind = ST_VIDEO;
            s->w = st->codecpar->width;
            s->h = st->codecpar->height;
        } else if (st->codecpar->codec_type == AVMEDIA_TYPE_SUBTITLE)
            s->kind = ST_TEXT;
        else
            continue;
        s->index = (int)i;
        lang = av_dict_get(st->metadata, "language", NULL, 0);
        if (lang && lang->value)
            snprintf(s->lang, sizeof(s->lang), "%s", lang->value);
        if (name)
            snprintf(s->codec, sizeof(s->codec), "%s", name);
        p->nstreams++;
    }
}

static int pick_audio(FileAv *p)
{
    int i, first = -1;
    for (i = 0; i < p->nstreams; i++) {
        if (p->streams[i].kind != ST_AUDIO)
            continue;
        if (first < 0)
            first = p->streams[i].index;
        if (p->audio_index >= 0 && p->streams[i].index == p->audio_index)
            return p->streams[i].index;
    }
    return first;
}

static int pick_video(FileAv *p)
{
    int i, best = -1, best_area = -1;
    for (i = 0; i < p->nstreams; i++) {
        int area;
        if (p->streams[i].kind != ST_VIDEO)
            continue;
        area = p->streams[i].w * p->streams[i].h;
        if (area > best_area) {
            best_area = area;
            best = p->streams[i].index;
        }
    }
    return best;
}

/* Keep the video decoder warm so audio→video does not seek back to a keyframe. */
static int need_video(FileAv *p)
{
    return p->vdec != NULL;
}

static int setup_decoders(FileAv *p)
{
    p->a_st = pick_audio(p);
    p->v_st = pick_video(p);
    if (p->a_st >= 0) {
        p->adec = open_codec(p->fmt->streams[p->a_st], 0);
        if (!p->adec) {
            set_err(p, "audio decoder");
            return -1;
        }
    }
    if (p->v_st >= 0) {
        p->vdec = open_codec(p->fmt->streams[p->v_st], 1);
        if (!p->vdec) {
            set_err(p, "video decoder");
            return -1;
        }
        p->vw = p->fmt->streams[p->v_st]->codecpar->width;
        p->vh = p->fmt->streams[p->v_st]->codecpar->height;
    }
    if (p->a_st < 0 && p->v_st < 0) {
        set_err(p, "no audio or video stream");
        return -1;
    }
    return 0;
}

static int fill_packets(FileAv *p)
{
    int i;
    for (i = 0; i < 24; i++) {
        AVPacket *pkt;
        if (p->aq.n - p->aq.rd > 8 && (p->v_st < 0 || p->vq.n - p->vq.rd > 8))
            return 0;
        pkt = av_packet_alloc();
        if (!pkt)
            return -1;
        if (av_read_frame(p->fmt, pkt) < 0) {
            av_packet_free(&pkt);
            return 1; /* eof */
        }
        if (pkt->stream_index == p->a_st) {
            if (pq_push(&p->aq, pkt) < 0)
                av_packet_free(&pkt);
        } else if (pkt->stream_index == p->v_st && need_video(p)) {
            if (pq_push(&p->vq, pkt) < 0)
                av_packet_free(&pkt);
        } else {
            av_packet_free(&pkt);
        }
    }
    return 0;
}

static int ensure_swr(FileAv *p, AVFrame *fr)
{
    AVChannelLayout out_ch;
    int in_rate = fr->sample_rate > 0 ? fr->sample_rate : OUT_RATE;
    if (p->swr)
        return 0;
    memset(&out_ch, 0, sizeof(out_ch));
    av_channel_layout_default(&out_ch, OUT_CH);
    if (swr_alloc_set_opts2(&p->swr, &out_ch, AV_SAMPLE_FMT_S16, OUT_RATE,
                            &fr->ch_layout, (enum AVSampleFormat)fr->format,
                            in_rate, 0, NULL) < 0 ||
        swr_init(p->swr) < 0) {
        av_channel_layout_uninit(&out_ch);
        swr_free(&p->swr);
        return -1;
    }
    av_channel_layout_uninit(&out_ch);
    return 0;
}

static int ensure_speed_swr(FileAv *p, double speed)
{
    AVChannelLayout ch;
    double in_rate;
    if (speed < 0.05)
        speed = 0.05;
    if (speed > 4)
        speed = 4;
    in_rate = (double)OUT_RATE * speed;
    if (p->swr_speed && p->swr_speed_in == in_rate)
        return 0;
    swr_free(&p->swr_speed);
    p->swr_speed_in = 0;
    if (speed > 0.99 && speed < 1.01)
        return 0;
    memset(&ch, 0, sizeof(ch));
    av_channel_layout_default(&ch, OUT_CH);
    if (swr_alloc_set_opts2(&p->swr_speed, &ch, AV_SAMPLE_FMT_S16, OUT_RATE,
                            &ch, AV_SAMPLE_FMT_S16, (int)(in_rate + 0.5),
                            0, NULL) < 0 ||
        swr_init(p->swr_speed) < 0) {
        av_channel_layout_uninit(&ch);
        swr_free(&p->swr_speed);
        return -1;
    }
    av_channel_layout_uninit(&ch);
    p->swr_speed_in = in_rate;
    return 0;
}

static void blit_size(FileAv *p, int src_w, int src_h, int *dw, int *dh)
{
    int box_w = p->blit_w, box_h = p->blit_h;
    *dw = src_w;
    *dh = src_h;
    if (src_w <= 0 || src_h <= 0)
        return;
    if (box_w <= 0 && box_h <= 0)
        return;
    if (box_w <= 0) {
        if (src_h <= box_h)
            return;
        *dh = box_h;
        *dw = src_w * box_h / src_h;
    } else if (box_h <= 0) {
        if (src_w <= box_w)
            return;
        *dw = box_w;
        *dh = src_h * box_w / src_w;
    } else if (src_w > box_w || src_h > box_h) {
        if ((int64_t)src_w * box_h > (int64_t)box_w * src_h) {
            *dw = box_w;
            *dh = src_h * box_w / src_w;
        } else {
            *dh = box_h;
            *dw = src_w * box_h / src_h;
        }
    }
    if (*dw < 2)
        *dw = 2;
    if (*dh < 2)
        *dh = 2;
}

static AVFrame *sw_video_frame(FileAv *p, AVFrame *fr)
{
    const AVPixFmtDescriptor *d;
    if (!fr)
        return NULL;
    if (!fr->hw_frames_ctx)
        return fr;
    if (!p->vsw)
        p->vsw = av_frame_alloc();
    if (!p->vsw)
        return NULL;
    av_frame_unref(p->vsw);
    if (av_hwframe_transfer_data(p->vsw, fr, 0) < 0)
        return NULL;
    p->vsw->pts = fr->pts;
    p->vsw->best_effort_timestamp = fr->best_effort_timestamp;
    p->vsw->colorspace = fr->colorspace;
    p->vsw->color_range = fr->color_range;
    p->vsw->color_primaries = fr->color_primaries;
    p->vsw->color_trc = fr->color_trc;
    d = av_pix_fmt_desc_get((enum AVPixelFormat)p->vsw->format);
    if (d && (d->flags & AV_PIX_FMT_FLAG_HWACCEL))
        return NULL;
    return p->vsw;
}

static int sws_cs(const AVFrame *fr)
{
    switch (fr->colorspace) {
    case AVCOL_SPC_BT709:
        return SWS_CS_ITU709;
    case AVCOL_SPC_BT2020_NCL:
    case AVCOL_SPC_BT2020_CL:
        return SWS_CS_BT2020;
    case AVCOL_SPC_SMPTE170M:
    case AVCOL_SPC_BT470BG:
        return SWS_CS_ITU601;
    default:
        return fr->height > 576 ? SWS_CS_ITU709 : SWS_CS_ITU601;
    }
}

static void sws_apply_color(struct SwsContext *sws, const AVFrame *fr)
{
    int src_range = fr->color_range == AVCOL_RANGE_JPEG ? 1 : 0;
    const int *src = sws_getCoefficients(sws_cs(fr));
    const int *dst = sws_getCoefficients(SWS_CS_DEFAULT);
    sws_setColorspaceDetails(sws, src, src_range, dst, 1, 0, 1 << 16, 1 << 16);
}

static int present_video(FileAv *p, AVFrame *fr)
{
    uint8_t *dst[4] = {0};
    int dst_ls[4] = {0};
    int dw, dh, n;
    const AVPixFmtDescriptor *d;
    if (!p->want_video)
        return 0;
    fr = sw_video_frame(p, fr);
    if (!fr || fr->width <= 0 || fr->height <= 0 || !fr->data[0])
        return -1;
    d = av_pix_fmt_desc_get((enum AVPixelFormat)fr->format);
    if (d && (d->flags & AV_PIX_FMT_FLAG_HWACCEL))
        return -1;
    blit_size(p, fr->width, fr->height, &dw, &dh);
    if (!p->sws || p->sws_dw != dw || p->sws_dh != dh ||
        p->sws_sw != fr->width || p->sws_sh != fr->height || p->sws_fmt != fr->format) {
        if (p->sws)
            sws_freeContext(p->sws);
        p->sws = sws_getContext(fr->width, fr->height, (enum AVPixelFormat)fr->format,
                                dw, dh, AV_PIX_FMT_BGRA, SWS_BILINEAR, NULL, NULL, NULL);
        p->sws_dw = dw;
        p->sws_dh = dh;
        p->sws_sw = fr->width;
        p->sws_sh = fr->height;
        p->sws_fmt = fr->format;
        if (p->sws)
            sws_apply_color(p->sws, fr);
    }
    if (!p->sws)
        return -1;
    n = dw * dh * 4;
    if (n > p->bgra_n) {
        uint8_t *b = av_realloc(p->bgra, (size_t)n);
        if (!b)
            return -1;
        p->bgra = b;
        p->bgra_n = n;
    }
    dst[0] = p->bgra;
    dst_ls[0] = dw * 4;
    if (sws_scale(p->sws, (const uint8_t *const *)fr->data, fr->linesize, 0, fr->height, dst, dst_ls) < 0)
        return -1;
    p->vw = dw;
    p->vh = dh;
    if (p->on_frame)
        p->on_frame(p->user, dw, dh, p->bgra);
    return 0;
}

static int pump_audio(FileAv *p, double clock)
{
    AVPacket *pkt;
    int got, max_out;
    uint8_t *tmp = NULL, *sped = NULL;
    uint8_t *dsts[1];
    int nbytes;
    if (!p->adec)
        return 0;
    for (;;) {
        got = avcodec_receive_frame(p->adec, p->afr);
        if (got == AVERROR(EAGAIN)) {
            pkt = pq_pop(&p->aq);
            if (!pkt)
                return 0;
            if (avcodec_send_packet(p->adec, pkt) < 0) {
                av_packet_free(&pkt);
                return 0;
            }
            av_packet_free(&pkt);
            continue;
        }
        if (got < 0)
            return 0;
        if (ensure_swr(p, p->afr) < 0) {
            av_frame_unref(p->afr);
            return -1;
        }
        max_out = (int)swr_get_out_samples(p->swr, p->afr->nb_samples) + 256;
        if (max_out < 1)
            max_out = p->afr->nb_samples + 256;
        tmp = malloc((size_t)max_out * OUT_CH * 2);
        if (!tmp) {
            av_frame_unref(p->afr);
            return -1;
        }
        dsts[0] = tmp;
        got = swr_convert(p->swr, dsts, max_out,
                          (const uint8_t **)p->afr->extended_data, p->afr->nb_samples);
        av_frame_unref(p->afr);
        if (got <= 0) {
            free(tmp);
            return 0;
        }
        if (ensure_speed_swr(p, p->speed) < 0) {
            free(tmp);
            return -1;
        }
        if (p->swr_speed) {
            int mout = (int)swr_get_out_samples(p->swr_speed, got) + 256;
            uint8_t *ins[1] = {tmp};
            if (mout < 1)
                mout = got + 256;
            sped = malloc((size_t)mout * OUT_CH * 2);
            if (!sped) {
                free(tmp);
                return -1;
            }
            dsts[0] = sped;
            got = swr_convert(p->swr_speed, dsts, mout, (const uint8_t **)ins, got);
            free(tmp);
            tmp = sped;
            if (got <= 0) {
                free(tmp);
                return 0;
            }
        }
        nbytes = got * OUT_CH * 2;
        pthread_mutex_unlock(&p->mu);
        if (p->on_pcm)
            p->on_pcm(p->user, OUT_RATE, OUT_CH, (const int16_t *)tmp, nbytes);
        pthread_mutex_lock(&p->mu);
        free(tmp);
        (void)clock;
        if (!p->opened || !p->adec)
            return 0;
        return 0;
    }
}

static void present_held(FileAv *p, AVFrame **held)
{
    AVFrame *h = *held;
    if (!h)
        return;
    *held = NULL;
    pthread_mutex_unlock(&p->mu);
    present_video(p, h);
    pthread_mutex_lock(&p->mu);
    av_frame_unref(h);
    av_frame_free(&h);
}

static int pump_video(FileAv *p, double clock)
{
    AVPacket *pkt;
    int got;
    double pts;
    AVFrame *fr;
    if (!p->vdec)
        return 0;

    /* One send or one present per call so we drop mu and the EDT can run. */
    if (p->vhold) {
        pts = frame_pts(p->fmt && p->v_st >= 0 ? p->fmt->streams[p->v_st] : NULL, p->vhold, clock);
        if (pts > clock + 0.04)
            return 0;
        present_held(p, &p->vhold);
        return 0;
    }

    pthread_mutex_unlock(&p->mu);
    got = avcodec_receive_frame(p->vdec, p->vfr);
    pthread_mutex_lock(&p->mu);
    if (!p->opened || !p->vdec)
        return 0;

    if (got == AVERROR(EAGAIN)) {
        pkt = pq_pop(&p->vq);
        if (!pkt)
            return 0;
        pthread_mutex_unlock(&p->mu);
        avcodec_send_packet(p->vdec, pkt);
        av_packet_free(&pkt);
        pthread_mutex_lock(&p->mu);
        return 0;
    }
    if (got < 0)
        return 0;

    fr = p->vfr;
    pts = frame_pts(p->fmt && p->v_st >= 0 ? p->fmt->streams[p->v_st] : NULL, fr, clock);
    if (pts > clock + 0.04) {
        if (!p->vhold)
            p->vhold = av_frame_alloc();
        if (p->vhold) {
            av_frame_unref(p->vhold);
            av_frame_ref(p->vhold, fr);
        }
        av_frame_unref(fr);
        return 0;
    }
    pthread_mutex_unlock(&p->mu);
    present_video(p, fr);
    pthread_mutex_lock(&p->mu);
    av_frame_unref(fr);
    return 0;
}

int file_av_step(FileAv *p)
{
    double clock;
    int eof, a_empty, v_empty;
    if (!p)
        return 0;
    pthread_mutex_lock(&p->mu);
    if (!p->opened || !p->playing) {
        pthread_mutex_unlock(&p->mu);
        return 0;
    }
    eof = fill_packets(p);
    clock = media_time_locked(p);
    if (p->adec)
        pump_audio(p, clock);
    if (need_video(p))
        pump_video(p, media_time_locked(p));
    clock = media_time_locked(p);
    if (p->on_prog && now_ns() - p->last_prog_ns > 1e8) {
        FileAvProgressFn prog = p->on_prog;
        void *user = p->user;
        double dur = p->duration;
        int vw = p->vw, vh = p->vh;
        p->last_prog_ns = now_ns();
        pthread_mutex_unlock(&p->mu);
        prog(user, clock, dur, vw, vh);
        pthread_mutex_lock(&p->mu);
    }
    a_empty = !p->adec || (p->aq.rd >= p->aq.n);
    v_empty = !need_video(p) || (p->vq.rd >= p->vq.n);
    if (eof && a_empty && v_empty && !p->ended && (p->adec || need_video(p))) {
        p->ended = 1;
        p->playing = 0;
        p->hold = p->duration > 0 ? p->duration : clock;
        pthread_mutex_unlock(&p->mu);
        if (p->on_event)
            p->on_event(p->user, 1, NULL);
        return 0;
    }
    pthread_mutex_unlock(&p->mu);
    return 1;
}

FileAv *file_av_create(void)
{
    FileAv *p = calloc(1, sizeof(*p));
    if (!p)
        return NULL;
    pthread_mutex_init(&p->mu, NULL);
    pthread_cond_init(&p->cv, NULL);
    p->speed = 1.0;
    p->want_video = 1;
    p->audio_index = -1;
    p->blit_w = 0;
    p->blit_h = 0;
    p->a_st = p->v_st = -1;
    p->afr = av_frame_alloc();
    p->vfr = av_frame_alloc();
    p->vhold = NULL;
    if (!p->afr || !p->vfr) {
        file_av_destroy(p);
        return NULL;
    }
    return p;
}

void file_av_destroy(FileAv *p)
{
    if (!p)
        return;
    file_av_close(p);
    av_frame_free(&p->afr);
    av_frame_free(&p->vfr);
    av_frame_free(&p->vhold);
    av_frame_free(&p->vsw);
    pthread_cond_destroy(&p->cv);
    pthread_mutex_destroy(&p->mu);
    free(p);
}

int file_av_open(FileAv *p, const char *path)
{
    int r;
    if (!p || !path)
        return -1;
    file_av_close(p);
    pthread_mutex_lock(&p->mu);
    p->err[0] = 0;
    p->ended = 0;
    p->hold = 0;
    p->origin_ns = 0;
    p->fmt = NULL;
    r = avformat_open_input(&p->fmt, path, NULL, NULL);
    if (r < 0) {
        char buf[128];
        av_strerror(r, buf, sizeof(buf));
        set_err(p, buf);
        pthread_mutex_unlock(&p->mu);
        return -1;
    }
    if (avformat_find_stream_info(p->fmt, NULL) < 0) {
        set_err(p, "stream info");
        avformat_close_input(&p->fmt);
        pthread_mutex_unlock(&p->mu);
        return -1;
    }
    if (p->fmt->duration > 0)
        p->duration = p->fmt->duration / (double)AV_TIME_BASE;
    else
        p->duration = 0;
    collect_streams(p);
    if (setup_decoders(p) < 0) {
        free_codecs(p);
        avformat_close_input(&p->fmt);
        pthread_mutex_unlock(&p->mu);
        return -1;
    }
    p->opened = 1;
    p->ended = 0;
    pthread_mutex_unlock(&p->mu);
    return 0;
}

void file_av_close(FileAv *p)
{
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    p->opened = 0;
    p->playing = 0;
    free_codecs(p);
    if (p->fmt)
        avformat_close_input(&p->fmt);
    free(p->streams);
    p->streams = NULL;
    p->nstreams = 0;
    p->a_st = p->v_st = -1;
    p->vw = p->vh = 0;
    pthread_mutex_unlock(&p->mu);
}

void file_av_play(FileAv *p)
{
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    if (p->opened && !p->playing) {
        p->playing = 1;
        p->ended = 0;
        p->origin_ns = now_ns();
        pthread_cond_broadcast(&p->cv);
    }
    pthread_mutex_unlock(&p->mu);
}

void file_av_pause(FileAv *p)
{
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    if (p->playing) {
        p->hold = media_time_locked(p);
        p->playing = 0;
        p->origin_ns = 0;
    }
    pthread_mutex_unlock(&p->mu);
}

void file_av_seek(FileAv *p, double seconds)
{
    int64_t ts;
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    if (!p->fmt) {
        pthread_mutex_unlock(&p->mu);
        return;
    }
    if (seconds < 0)
        seconds = 0;
    if (p->duration > 0 && seconds > p->duration)
        seconds = p->duration;
    ts = (int64_t)(seconds * AV_TIME_BASE);
    av_seek_frame(p->fmt, -1, ts, AVSEEK_FLAG_BACKWARD);
    if (p->adec)
        avcodec_flush_buffers(p->adec);
    if (p->vdec)
        avcodec_flush_buffers(p->vdec);
    pq_clear(&p->aq);
    pq_clear(&p->vq);
    av_frame_unref(p->afr);
    av_frame_unref(p->vfr);
    av_frame_free(&p->vhold);
    p->hold = seconds;
    p->ended = 0;
    if (p->playing)
        p->origin_ns = now_ns();
    pthread_mutex_unlock(&p->mu);
}

void file_av_set_speed(FileAv *p, double speed)
{
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    if (p->playing)
        p->hold = media_time_locked(p);
    p->speed = speed > 0.05 ? speed : 0.05;
    if (p->playing)
        p->origin_ns = now_ns();
    swr_free(&p->swr_speed);
    p->swr_speed_in = 0;
    pthread_mutex_unlock(&p->mu);
}

void file_av_set_audio_index(FileAv *p, int stream_index)
{
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    p->audio_index = stream_index;
    pthread_mutex_unlock(&p->mu);
}

void file_av_set_video_enabled(FileAv *p, int on)
{
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    p->want_video = on ? 1 : 0;
    pthread_mutex_unlock(&p->mu);
}

void file_av_set_blit_max(FileAv *p, int max_height)
{
    file_av_set_blit_size(p, 0, max_height);
}

void file_av_set_blit_size(FileAv *p, int w, int h)
{
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    p->blit_w = w > 0 ? w : 0;
    p->blit_h = h > 0 ? h : 0;
    pthread_mutex_unlock(&p->mu);
}

double file_av_duration(FileAv *p)
{
    double d;
    if (!p)
        return 0;
    pthread_mutex_lock(&p->mu);
    d = p->duration;
    pthread_mutex_unlock(&p->mu);
    return d;
}

double file_av_position(FileAv *p)
{
    double d;
    if (!p)
        return 0;
    pthread_mutex_lock(&p->mu);
    d = media_time_locked(p);
    pthread_mutex_unlock(&p->mu);
    return d;
}

int file_av_ended(FileAv *p)
{
    int e;
    if (!p)
        return 0;
    pthread_mutex_lock(&p->mu);
    e = p->ended;
    pthread_mutex_unlock(&p->mu);
    return e;
}

int file_av_video_size(FileAv *p, int *w, int *h)
{
    if (!p)
        return 0;
    pthread_mutex_lock(&p->mu);
    if (w)
        *w = p->vw;
    if (h)
        *h = p->vh;
    pthread_mutex_unlock(&p->mu);
    return p && p->vw > 0 && p->vh > 0;
}

const char *file_av_last_error(FileAv *p)
{
    return p ? p->err : "nil";
}

int file_av_stream_count(FileAv *p)
{
    int n;
    if (!p)
        return 0;
    pthread_mutex_lock(&p->mu);
    n = p->nstreams;
    pthread_mutex_unlock(&p->mu);
    return n;
}

int file_av_stream_at(FileAv *p, int i, int *kind, int *index, int *w, int *h,
                      char *lang, int lang_n, char *codec, int codec_n)
{
    StreamInfo s;
    if (!p)
        return -1;
    pthread_mutex_lock(&p->mu);
    if (i < 0 || i >= p->nstreams) {
        pthread_mutex_unlock(&p->mu);
        return -1;
    }
    s = p->streams[i];
    pthread_mutex_unlock(&p->mu);
    if (kind)
        *kind = s.kind;
    if (index)
        *index = s.index;
    if (w)
        *w = s.w;
    if (h)
        *h = s.h;
    if (lang && lang_n > 0)
        snprintf(lang, (size_t)lang_n, "%s", s.lang);
    if (codec && codec_n > 0)
        snprintf(codec, (size_t)codec_n, "%s", s.codec);
    return 0;
}

void file_av_set_sinks(FileAv *p, FileAvFrameFn on_frame, FileAvPcmFn on_pcm,
                       FileAvProgressFn on_prog, FileAvEventFn on_event, void *user)
{
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    p->on_frame = on_frame;
    p->on_pcm = on_pcm;
    p->on_prog = on_prog;
    p->on_event = on_event;
    p->user = user;
    pthread_mutex_unlock(&p->mu);
}

void *file_av_user(FileAv *p)
{
    void *u;
    if (!p)
        return NULL;
    pthread_mutex_lock(&p->mu);
    u = p->user;
    pthread_mutex_unlock(&p->mu);
    return u;
}
