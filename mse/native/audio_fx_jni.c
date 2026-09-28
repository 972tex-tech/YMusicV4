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
