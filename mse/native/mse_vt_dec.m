#ifdef __APPLE__
#import "mse_vt_present.h"
#import <CoreMedia/CoreMedia.h>
#import <VideoToolbox/VideoToolbox.h>
#import <string.h>

struct MseVtDec {
    VTDecompressionSessionRef session;
    CMVideoFormatDescriptionRef fmt;
    int nal_len;
    int w, h;
    CVPixelBufferRef out;
};

static void vt_dec_cb(void *refcon, void *src, OSStatus status, VTDecodeInfoFlags flags,
                      CVImageBufferRef img, CMTime pts, CMTime dur)
{
    struct MseVtDec *d = refcon;
    (void)src;
    (void)flags;
    (void)pts;
    (void)dur;
    if (status != noErr || !img || !d)
        return;
    if (d->out)
        CVPixelBufferRelease(d->out);
    d->out = CVPixelBufferRetain((CVPixelBufferRef)img);
}

static int avcc_sets(const uint8_t *p, int n, const uint8_t **sets, size_t *setn, int *nset, int *nal_len)
{
    int off, nsps, npps, i, k = 0;
    if (!p || n < 7 || p[0] != 1)
        return -1;
    *nal_len = (p[4] & 3) + 1;
    nsps = p[5] & 0x1F;
    off = 6;
    for (i = 0; i < nsps; i++) {
        int len;
        if (off + 2 > n)
            return -1;
        len = (p[off] << 8) | p[off + 1];
        off += 2;
        if (len < 1 || off + len > n || k >= 8)
            return -1;
        sets[k] = p + off;
        setn[k] = (size_t)len;
        k++;
        off += len;
    }
    if (off >= n)
        return -1;
    npps = p[off++];
    for (i = 0; i < npps; i++) {
        int len;
        if (off + 2 > n)
            return -1;
        len = (p[off] << 8) | p[off + 1];
        off += 2;
        if (len < 1 || off + len > n || k >= 8)
            return -1;
        sets[k] = p + off;
        setn[k] = (size_t)len;
        k++;
        off += len;
    }
    *nset = k;
    return 0;
}

void *mse_vt_dec_open(const uint8_t *avcc, int avcc_n, int w, int h)
{
    struct MseVtDec *d;
    const uint8_t *sets[8];
    size_t setn[8];
    int nset = 0, nal_len = 4;
    CFMutableDictionaryRef spec = NULL;
    VTDecompressionOutputCallbackRecord cb;
    OSStatus st;
    if (!avcc || avcc_n < 7 || w <= 0 || h <= 0)
        return NULL;
    if (avcc_sets(avcc, avcc_n, sets, setn, &nset, &nal_len) < 0 || nset < 2)
        return NULL;
    d = calloc(1, sizeof(*d));
    if (!d)
        return NULL;
    d->nal_len = nal_len;
    d->w = w;
    d->h = h;
    st = CMVideoFormatDescriptionCreateFromH264ParameterSets(
        kCFAllocatorDefault, (size_t)nset, sets, setn, nal_len, &d->fmt);
    if (st != noErr || !d->fmt) {
        free(d);
        return NULL;
    }
    spec = CFDictionaryCreateMutable(kCFAllocatorDefault, 1,
                                     &kCFTypeDictionaryKeyCallBacks,
                                     &kCFTypeDictionaryValueCallBacks);
    /* Enable HW; do not Require it. Simulator uses Apple software VT. */
    CFDictionarySetValue(spec, kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder,
                         kCFBooleanTrue);
    cb.decompressionOutputCallback = vt_dec_cb;
    cb.decompressionOutputRefCon = d;
    st = VTDecompressionSessionCreate(kCFAllocatorDefault, d->fmt, spec, NULL, &cb, &d->session);
    CFRelease(spec);
    if (st != noErr || !d->session) {
        CFRelease(d->fmt);
        free(d);
        return NULL;
    }
    return d;
}

void mse_vt_dec_close(void *dec)
{
    struct MseVtDec *d = dec;
    if (!d)
        return;
    if (d->session) {
        VTDecompressionSessionInvalidate(d->session);
        CFRelease(d->session);
    }
    if (d->fmt)
        CFRelease(d->fmt);
    if (d->out)
        CVPixelBufferRelease(d->out);
    free(d);
}

void mse_vt_dec_flush(void *dec)
{
    struct MseVtDec *d = dec;
    if (!d || !d->session)
        return;
    VTDecompressionSessionWaitForAsynchronousFrames(d->session);
    if (d->out) {
        CVPixelBufferRelease(d->out);
        d->out = NULL;
    }
}

int mse_vt_dec_send(void *dec, const uint8_t *data, int n)
{
    struct MseVtDec *d = dec;
    CMBlockBufferRef bb = NULL;
    CMSampleBufferRef sbuf = NULL;
    OSStatus st;
    if (!d || !d->session || !data || n <= 0)
        return -1;
    st = CMBlockBufferCreateWithMemoryBlock(kCFAllocatorDefault, (void *)data, (size_t)n,
                                            kCFAllocatorNull, NULL, 0, (size_t)n, 0, &bb);
    if (st != noErr || !bb)
        return -1;
    st = CMSampleBufferCreateReady(kCFAllocatorDefault, bb, d->fmt, 1, 0, NULL, 0, NULL, &sbuf);
    CFRelease(bb);
    if (st != noErr || !sbuf)
        return -1;
    st = VTDecompressionSessionDecodeFrame(d->session, sbuf, 0, NULL, NULL);
    CFRelease(sbuf);
    if (st != noErr)
        return -1;
    VTDecompressionSessionWaitForAsynchronousFrames(d->session);
    return 0;
}

int mse_vt_dec_recv(void *dec, void **cvpb, int *w, int *h)
{
    struct MseVtDec *d = dec;
    if (!d || !cvpb || !w || !h)
        return 0;
    if (!d->out)
        return 0;
    *cvpb = d->out;
    d->out = NULL;
    *w = d->w;
    *h = d->h;
    return 1;
}

void mse_vt_pb_release(void *cvpb)
{
    if (cvpb)
        CVPixelBufferRelease((CVPixelBufferRef)cvpb);
}
#endif
