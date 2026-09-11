#include "file_av.h"

#include <jni.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    JavaVM *jvm;
    jobject sink;
    jmethodID onFrame;
    jmethodID onPcm;
    jmethodID onProgress;
    jmethodID onEvent;
    jobject direct;
    const uint8_t *direct_ptr;
    int direct_n;
} JniSink;

static JNIEnv *sink_env(JniSink *s)
{
    JNIEnv *env = NULL;
    if (!s || !s->jvm)
        return NULL;
    (*s->jvm)->GetEnv(s->jvm, (void **)&env, JNI_VERSION_1_6);
    if (env)
        return env;
    (*s->jvm)->AttachCurrentThread(s->jvm, (void **)&env, NULL);
    return env;
}

static jobject direct_for(JNIEnv *env, JniSink *s, const uint8_t *bgra, int n)
{
    jobject local;
    if (s->direct && s->direct_ptr == bgra && s->direct_n >= n)
        return s->direct;
    if (s->direct) {
        (*env)->DeleteGlobalRef(env, s->direct);
        s->direct = NULL;
    }
    local = (*env)->NewDirectByteBuffer(env, (void *)bgra, (jlong)n);
    if (!local)
        return NULL;
    s->direct = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    s->direct_ptr = bgra;
    s->direct_n = n;
    return s->direct;
}

static void on_frame(void *user, int w, int h, const uint8_t *bgra)
{
    JniSink *s = user;
    JNIEnv *env;
    jobject buf;
    int n;
    if (!s || !bgra || w <= 0 || h <= 0)
        return;
    env = sink_env(s);
    if (!env)
        return;
    if ((uint64_t)w * (uint64_t)h > (uint64_t)(INT_MAX / 4))
        return;
    n = w * h * 4;
    buf = direct_for(env, s, bgra, n);
    if (!buf)
        return;
    (*env)->CallVoidMethod(env, s->sink, s->onFrame, w, h, buf);
}

static void on_pcm(void *user, int rate, int ch, const int16_t *s16, int nbytes)
{
    JniSink *s = user;
    JNIEnv *env;
    jbyteArray arr;
    if (!s || !s16 || nbytes <= 0)
        return;
    env = sink_env(s);
    if (!env)
        return;
    arr = (*env)->NewByteArray(env, nbytes);
    if (!arr)
        return;
    (*env)->SetByteArrayRegion(env, arr, 0, nbytes, (const jbyte *)s16);
    (*env)->CallVoidMethod(env, s->sink, s->onPcm, rate, ch, arr);
    (*env)->DeleteLocalRef(env, arr);
}

static void on_prog(void *user, double pos, double dur, int w, int h)
{
    JniSink *s = user;
    JNIEnv *env = sink_env(s);
    if (!env)
        return;
    (*env)->CallVoidMethod(env, s->sink, s->onProgress, pos, dur, w, h);
}

static void on_event(void *user, int ended, const char *err)
{
    JniSink *s = user;
    JNIEnv *env;
    jstring js = NULL;
    if (!s)
        return;
    env = sink_env(s);
    if (!env)
        return;
    if (err && err[0])
        js = (*env)->NewStringUTF(env, err);
    (*env)->CallVoidMethod(env, s->sink, s->onEvent, ended ? JNI_TRUE : JNI_FALSE, js);
    if (js)
        (*env)->DeleteLocalRef(env, js);
}

static void drop_sink(JNIEnv *env, JniSink *s)
{
    if (!s)
        return;
    if (s->direct)
        (*env)->DeleteGlobalRef(env, s->direct);
    if (s->sink)
        (*env)->DeleteGlobalRef(env, s->sink);
    free(s);
}

JNIEXPORT jlong JNICALL
Java_io_ymusic_mse_FileAvNative_create(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    return (jlong)(intptr_t)file_av_create();
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_destroy(JNIEnv *env, jclass cls, jlong handle)
{
    FileAv *p = (FileAv *)(intptr_t)handle;
    JniSink *s;
    (void)cls;
    if (!p)
        return;
    s = (JniSink *)file_av_user(p);
    file_av_set_sinks(p, NULL, NULL, NULL, NULL, NULL);
    file_av_destroy(p);
    drop_sink(env, s);
}

JNIEXPORT jint JNICALL
Java_io_ymusic_mse_FileAvNative_open(JNIEnv *env, jclass cls, jlong handle, jstring path)
{
    FileAv *p = (FileAv *)(intptr_t)handle;
    const char *c;
    int r;
    (void)cls;
    if (!p || !path)
        return -1;
    c = (*env)->GetStringUTFChars(env, path, NULL);
    if (!c)
        return -1;
    r = file_av_open(p, c);
    (*env)->ReleaseStringUTFChars(env, path, c);
    return r;
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_close(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    file_av_close((FileAv *)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_play(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    file_av_play((FileAv *)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_pause(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    file_av_pause((FileAv *)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_seek(JNIEnv *env, jclass cls, jlong handle, jdouble seconds)
{
    (void)env;
    (void)cls;
    file_av_seek((FileAv *)(intptr_t)handle, seconds);
}

JNIEXPORT jint JNICALL
Java_io_ymusic_mse_FileAvNative_step(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    return file_av_step((FileAv *)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_setSpeed(JNIEnv *env, jclass cls, jlong handle, jdouble speed)
{
    (void)env;
    (void)cls;
    file_av_set_speed((FileAv *)(intptr_t)handle, speed);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_setAudioIndex(JNIEnv *env, jclass cls, jlong handle, jint index)
{
    (void)env;
    (void)cls;
    file_av_set_audio_index((FileAv *)(intptr_t)handle, index);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_setVideoEnabled(JNIEnv *env, jclass cls, jlong handle, jboolean on)
{
    (void)env;
    (void)cls;
    file_av_set_video_enabled((FileAv *)(intptr_t)handle, on ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_setBlitMax(JNIEnv *env, jclass cls, jlong handle, jint maxEdge)
{
    (void)env;
    (void)cls;
    file_av_set_blit_max((FileAv *)(intptr_t)handle, maxEdge);
}

JNIEXPORT jdouble JNICALL
Java_io_ymusic_mse_FileAvNative_duration(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    return file_av_duration((FileAv *)(intptr_t)handle);
}

JNIEXPORT jdouble JNICALL
Java_io_ymusic_mse_FileAvNative_position(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    return file_av_position((FileAv *)(intptr_t)handle);
}

JNIEXPORT jboolean JNICALL
Java_io_ymusic_mse_FileAvNative_ended(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    return file_av_ended((FileAv *)(intptr_t)handle) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_ymusic_mse_FileAvNative_videoSize(JNIEnv *env, jclass cls, jlong handle, jintArray outWh)
{
    int w = 0, h = 0, ok;
    jint wh[2];
    (void)cls;
    ok = file_av_video_size((FileAv *)(intptr_t)handle, &w, &h);
    wh[0] = w;
    wh[1] = h;
    (*env)->SetIntArrayRegion(env, outWh, 0, 2, wh);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_io_ymusic_mse_FileAvNative_lastError(JNIEnv *env, jclass cls, jlong handle)
{
    const char *e = file_av_last_error((FileAv *)(intptr_t)handle);
    (void)cls;
    return (*env)->NewStringUTF(env, e ? e : "");
}

JNIEXPORT jobjectArray JNICALL
Java_io_ymusic_mse_FileAvNative_streams(JNIEnv *env, jclass cls, jlong handle)
{
    FileAv *p = (FileAv *)(intptr_t)handle;
    int n, i;
    jobjectArray arr;
    jclass strCls;
    (void)cls;
    n = file_av_stream_count(p);
    strCls = (*env)->FindClass(env, "java/lang/String");
    arr = (*env)->NewObjectArray(env, n, strCls, NULL);
    for (i = 0; i < n; i++) {
        int kind = 0, index = 0, w = 0, h = 0;
        char lang[16], codec[32], line[96];
        jstring js;
        file_av_stream_at(p, i, &kind, &index, &w, &h, lang, (int)sizeof(lang), codec, (int)sizeof(codec));
        snprintf(line, sizeof(line), "%d|%d|%d|%d|%s|%s", kind, index, w, h, lang, codec);
        js = (*env)->NewStringUTF(env, line);
        (*env)->SetObjectArrayElement(env, arr, i, js);
        (*env)->DeleteLocalRef(env, js);
    }
    return arr;
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_setSink(JNIEnv *env, jclass cls, jlong handle, jobject sink)
{
    FileAv *p = (FileAv *)(intptr_t)handle;
    JniSink *s;
    jclass sc;
    (void)cls;
    if (!p)
        return;
    s = (JniSink *)file_av_user(p);
    file_av_set_sinks(p, NULL, NULL, NULL, NULL, NULL);
    drop_sink(env, s);
    if (!sink)
        return;
    s = calloc(1, sizeof(*s));
    if (!s)
        return;
    (*env)->GetJavaVM(env, &s->jvm);
    s->sink = (*env)->NewGlobalRef(env, sink);
    sc = (*env)->GetObjectClass(env, sink);
    s->onFrame = (*env)->GetMethodID(env, sc, "onFrame", "(IILjava/nio/ByteBuffer;)V");
    s->onPcm = (*env)->GetMethodID(env, sc, "onPcm", "(II[B)V");
    s->onProgress = (*env)->GetMethodID(env, sc, "onProgress", "(DDII)V");
    s->onEvent = (*env)->GetMethodID(env, sc, "onEvent", "(ZLjava/lang/String;)V");
    file_av_set_sinks(p, on_frame, on_pcm, on_prog, on_event, s);
}
