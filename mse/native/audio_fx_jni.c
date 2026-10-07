#include "audio_fx.h"

#include <jni.h>
#include <stdint.h>
#include <string.h>

JNIEXPORT jlong JNICALL
Java_io_ymusic_mse_AudioFxNative_create(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    return (jlong)(intptr_t)audio_fx_create();
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_AudioFxNative_destroy(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    audio_fx_destroy((AudioFx *)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_AudioFxNative_reset(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    audio_fx_reset((AudioFx *)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_AudioFxNative_setRate(JNIEnv *env, jclass cls, jlong handle, jint rate)
{
    (void)env;
    (void)cls;
    audio_fx_set_rate((AudioFx *)(intptr_t)handle, rate);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_AudioFxNative_setEq(JNIEnv *env, jclass cls, jlong handle, jfloatArray bands)
{
    float tmp[AFX_BANDS];
    jsize n;
    (void)cls;
    memset(tmp, 0, sizeof(tmp));
    if (bands) {
        n = (*env)->GetArrayLength(env, bands);
        if (n > AFX_BANDS)
            n = AFX_BANDS;
        (*env)->GetFloatArrayRegion(env, bands, 0, n, tmp);
    }
    audio_fx_set_eq((AudioFx *)(intptr_t)handle, tmp);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_AudioFxNative_setBass(JNIEnv *env, jclass cls, jlong handle, jfloat db)
{
    (void)env;
    (void)cls;
    audio_fx_set_bass((AudioFx *)(intptr_t)handle, db);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_AudioFxNative_setVirtualizer(JNIEnv *env, jclass cls, jlong handle, jfloat width)
{
    (void)env;
    (void)cls;
    audio_fx_set_virtualizer((AudioFx *)(intptr_t)handle, width);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_AudioFxNative_setPreamp(JNIEnv *env, jclass cls, jlong handle, jfloat db)
{
    (void)env;
    (void)cls;
    audio_fx_set_preamp((AudioFx *)(intptr_t)handle, db);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_AudioFxNative_setSpeed(JNIEnv *env, jclass cls, jlong handle, jfloat speed)
{
    (void)env;
    (void)cls;
    audio_fx_set_speed((AudioFx *)(intptr_t)handle, speed);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_AudioFxNative_setPitch(JNIEnv *env, jclass cls, jlong handle, jfloat pitch)
{
    (void)env;
    (void)cls;
    audio_fx_set_pitch((AudioFx *)(intptr_t)handle, pitch);
}

JNIEXPORT jint JNICALL
Java_io_ymusic_mse_AudioFxNative_stretch(
    JNIEnv *env,
    jclass cls,
    jlong handle,
    jobject in_buf,
    jint in_pos,
    jint in_frames,
    jobject out_buf,
    jint out_pos,
    jint out_cap,
    jint ch)
{
    uint8_t *in_base;
    uint8_t *out_base;
    (void)cls;
    if (!handle || !in_buf || !out_buf || in_frames < 0 || out_cap <= 0 || in_pos < 0 || out_pos < 0)
        return 0;
    in_base = (uint8_t *)(*env)->GetDirectBufferAddress(env, in_buf);
    out_base = (uint8_t *)(*env)->GetDirectBufferAddress(env, out_buf);
    if (!in_base || !out_base)
        return 0;
    return audio_fx_stretch(
        (AudioFx *)(intptr_t)handle,
        (const int16_t *)(in_base + in_pos),
        in_frames,
        (int16_t *)(out_base + out_pos),
        out_cap,
        ch);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_AudioFxNative_color(
    JNIEnv *env,
    jclass cls,
    jlong handle,
    jobject buf,
    jint pos,
    jint frames,
    jint ch)
{
    uint8_t *base;
    (void)cls;
    if (!handle || !buf || frames <= 0 || pos < 0)
        return;
    base = (uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    if (!base)
        return;
    audio_fx_color((AudioFx *)(intptr_t)handle, (int16_t *)(base + pos), frames, ch);
}

JNIEXPORT jint JNICALL
Java_io_ymusic_mse_AudioFxNative_levels(JNIEnv *env, jclass cls, jlong handle, jfloatArray out,
                                       jint delay_frames)
{
    float tmp[AFX_LEVELS];
    jsize n;
    int got;
    (void)cls;
    if (!handle || !out)
        return 0;
    n = (*env)->GetArrayLength(env, out);
    if (n > AFX_LEVELS)
        n = AFX_LEVELS;
    got = audio_fx_levels((AudioFx *)(intptr_t)handle, tmp, (int)n, (int)delay_frames);
    if (got > 0)
        (*env)->SetFloatArrayRegion(env, out, 0, got, tmp);
    return got;
}
