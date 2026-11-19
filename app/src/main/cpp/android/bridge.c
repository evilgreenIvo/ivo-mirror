/*
 * IVO Mirror · receba a tela do iPhone no IVO LIVE
 * Copyright (C) 2026 evilgreenIvo
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE.
 *
 * The bridge between UxPlay's AirPlay server (C) and the app (Kotlin, com.evilgreen.airplay.Native):
 * starts and stops the server the way UxPlay's own main program does, and hands every picture,
 * sound, size change and announcement to the app.
 */
#include <jni.h>
#include <pthread.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <android/log.h>

#include "raop.h"
#include "dnssd.h"
#include "logger.h"
#include "stream.h"

#define TAG "IvoAirPlay"

static JavaVM *vm;
static jclass native_class;
static jmethodID m_announce, m_video, m_audio, m_audio_format, m_size, m_client, m_connections, m_log, m_pin;
static pthread_key_t detach_key;

static raop_t *raop;
static dnssd_t *dnssd;
static int open_connections;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

static void detach_thread(void *unused) {
    (void) unused;
    (*vm)->DetachCurrentThread(vm);
}

/* The calling thread's JNIEnv; the server's own threads are attached once and detached when they end. */
static JNIEnv *env_now(void) {
    JNIEnv *env = NULL;
    jint r = (*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6);
    if (r == JNI_OK) return env;
    if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) return NULL;
    pthread_setspecific(detach_key, (void *) 1);
    return env;
}

static void clear_exception(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

JNIEXPORT jint JNI_OnLoad(JavaVM *jvm, void *reserved) {
    (void) reserved;
    vm = jvm;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass c = (*env)->FindClass(env, "com/evilgreen/airplay/Native");
    if (!c) return JNI_ERR;
    native_class = (*env)->NewGlobalRef(env, c);
    m_announce = (*env)->GetStaticMethodID(env, c, "announce", "(Ljava/lang/String;Ljava/lang/String;I[B)V");
    m_video = (*env)->GetStaticMethodID(env, c, "video", "([BJ)V");
    m_audio = (*env)->GetStaticMethodID(env, c, "audio", "([BIJ)V");
    m_audio_format = (*env)->GetStaticMethodID(env, c, "audioFormat", "(II)V");
    m_size = (*env)->GetStaticMethodID(env, c, "size", "(II)V");
    m_client = (*env)->GetStaticMethodID(env, c, "client", "(Ljava/lang/String;Ljava/lang/String;)V");
    m_connections = (*env)->GetStaticMethodID(env, c, "connections", "(I)V");
    m_log = (*env)->GetStaticMethodID(env, c, "log", "(ILjava/lang/String;)V");
    m_pin = (*env)->GetStaticMethodID(env, c, "pin", "(Ljava/lang/String;)V");
    if (!m_announce || !m_video || !m_audio || !m_audio_format || !m_size || !m_client || !m_connections || !m_log) return JNI_ERR;
    pthread_key_create(&detach_key, detach_thread);
    return JNI_VERSION_1_6;
}

static jstring jstr(JNIEnv *env, const char *s) {
    return (*env)->NewStringUTF(env, s ? s : "");
}

/* --- called by dnssd_android.c --- */

void android_dnssd_announce(const char *type, const char *name, unsigned short port, const char *txt, int txt_len) {
    JNIEnv *env = env_now();
    if (!env) return;
    jstring t = jstr(env, type);
    jstring n = jstr(env, name);
    jbyteArray b = NULL;
    if (txt && txt_len > 0) {
        b = (*env)->NewByteArray(env, txt_len);
        (*env)->SetByteArrayRegion(env, b, 0, txt_len, (const jbyte *) txt);
    }
    (*env)->CallStaticVoidMethod(env, native_class, m_announce, t, n, (jint) port, b);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, t);
    (*env)->DeleteLocalRef(env, n);
    if (b) (*env)->DeleteLocalRef(env, b);
}

/* --- the server's callbacks --- */

static void log_cb(void *cls, int level, const char *msg) {
    (void) cls;
    int prio = level <= LOGGER_ERR ? ANDROID_LOG_ERROR : level <= LOGGER_WARNING ? ANDROID_LOG_WARN :
               level <= LOGGER_INFO ? ANDROID_LOG_INFO : ANDROID_LOG_DEBUG;
    __android_log_print(prio, TAG, "%s", msg);
    if (level > LOGGER_INFO) return;
    JNIEnv *env = env_now();
    if (!env) return;
    jstring s = jstr(env, msg);
    (*env)->CallStaticVoidMethod(env, native_class, m_log, (jint) level, s);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, s);
}

static void conn_init(void *cls) {
    (void) cls;
    int n = __sync_add_and_fetch(&open_connections, 1);
    JNIEnv *env = env_now();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, native_class, m_connections, (jint) n);
    clear_exception(env);
}

static void conn_destroy(void *cls) {
    (void) cls;
    int n = __sync_sub_and_fetch(&open_connections, 1);
    if (n < 0) { open_connections = 0; n = 0; }
    JNIEnv *env = env_now();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, native_class, m_connections, (jint) n);
    clear_exception(env);
}

static void conn_reset(void *cls, int reason) {
    (void) cls;
    __android_log_print(ANDROID_LOG_INFO, TAG, "conn_reset %d", reason);
}

static void conn_feedback(void *cls) { (void) cls; }

static void video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    (void) cls; (void) ntp;
    if (!data || data->data_len <= 0 || data->is_h265) return;
    JNIEnv *env = env_now();
    if (!env) return;
    jbyteArray b = (*env)->NewByteArray(env, data->data_len);
    if (!b) { clear_exception(env); return; }
    (*env)->SetByteArrayRegion(env, b, 0, data->data_len, (const jbyte *) data->data);
    (*env)->CallStaticVoidMethod(env, native_class, m_video, b, (jlong) data->ntp_time_remote);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, b);
}

static void audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    (void) cls; (void) ntp;
    if (!data || data->data_len <= 0) return;
    JNIEnv *env = env_now();
    if (!env) return;
    jbyteArray b = (*env)->NewByteArray(env, data->data_len);
    if (!b) { clear_exception(env); return; }
    (*env)->SetByteArrayRegion(env, b, 0, data->data_len, (const jbyte *) data->data);
    (*env)->CallStaticVoidMethod(env, native_class, m_audio, b, (jint) data->ct, (jlong) data->ntp_time_remote);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, b);
}

static void audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *usingScreen, bool *isMedia, uint64_t *audioFormat) {
    (void) cls; (void) usingScreen; (void) isMedia; (void) audioFormat;
    JNIEnv *env = env_now();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, native_class, m_audio_format, (jint) *ct, (jint) *spf);
    clear_exception(env);
}

static int video_set_codec_and_size(void *cls, video_codec_t codec, unsigned short width_source, unsigned short height_source,
                                    unsigned short x_offset, unsigned short y_offset, unsigned short width, unsigned short height) {
    (void) cls; (void) x_offset; (void) y_offset; (void) width_source; (void) height_source;
    if (codec == VIDEO_CODEC_H265) return -1;
    JNIEnv *env = env_now();
    if (!env) return 0;
    (*env)->CallStaticVoidMethod(env, native_class, m_size, (jint) width, (jint) height);
    clear_exception(env);
    return 0;
}

static void report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    (void) cls; (void) deviceid;
    *admit = true;
    JNIEnv *env = env_now();
    if (!env) return;
    jstring n = jstr(env, name);
    jstring m = jstr(env, model);
    (*env)->CallStaticVoidMethod(env, native_class, m_client, n, m);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, n);
    (*env)->DeleteLocalRef(env, m);
}

/* --- PIN: iPhones that typed the code once are kept in a file (their public key), like UxPlay --- */

static int use_pin;
static char clients_file[512];

static bool check_register(void *cls, const char *pk) {
    (void) cls;
    if (!use_pin) return true;
    if (!pk) return false;
    FILE *f = fopen(clients_file, "r");
    if (!f) return false;
    char line[512];
    bool found = false;
    while (fgets(line, sizeof(line), f)) {
        line[strcspn(line, "\r\n")] = 0;
        if (!strcmp(line, pk)) { found = true; break; }
    }
    fclose(f);
    return found;
}

static void register_client(void *cls, const char *id, const char *pk, const char *name) {
    (void) cls; (void) id; (void) name;
    if (!use_pin || !pk || check_register(NULL, pk)) return;
    FILE *f = fopen(clients_file, "a");
    if (!f) return;
    fprintf(f, "%s\n", pk);
    fclose(f);
}

static void display_pin(void *cls, char *pin) {
    (void) cls;
    JNIEnv *env = env_now();
    if (!env || !m_pin) return;
    jstring p = jstr(env, pin);
    (*env)->CallStaticVoidMethod(env, native_class, m_pin, p);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, p);
}
static void audio_flush(void *cls) { (void) cls; }
static void video_flush(void *cls) { (void) cls; }
static void video_pause(void *cls) { (void) cls; }
static void video_resume(void *cls) { (void) cls; }
static void video_reset(void *cls, reset_type_t t) { (void) cls; (void) t; }
static double audio_set_client_volume(void *cls) { (void) cls; return 0.0; }
static void audio_set_volume(void *cls, float v) { (void) cls; (void) v; }

/* --- start / stop, like UxPlay's main(): announce, server, register --- */

static int parse_mac(const char *mac, char out[6]) {
    unsigned int b[6];
    if (sscanf(mac, "%x:%x:%x:%x:%x:%x", &b[0], &b[1], &b[2], &b[3], &b[4], &b[5]) != 6) return -1;
    for (int i = 0; i < 6; i++) out[i] = (char) b[i];
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_evilgreen_airplay_Native_start(JNIEnv *env, jclass cls, jstring jname, jstring jmac, jstring jkeyfile,
                                        jint width, jint height, jint fps, jint pin) {
    (void) cls;
    pthread_mutex_lock(&lock);
    if (raop) { pthread_mutex_unlock(&lock); return -10; }
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *mac = (*env)->GetStringUTFChars(env, jmac, NULL);
    const char *keyfile = (*env)->GetStringUTFChars(env, jkeyfile, NULL);
    int result = 0;
    char hw[6];
    open_connections = 0;

    ntp_global_init();
    if (parse_mac(mac, hw)) { result = -1; goto done; }

    int error = 0;
    /* PIN (0 = off): the iPhone asks for this fixed 4-digit code the first time. */
    use_pin = pin > 0;
    snprintf(clients_file, sizeof(clients_file), "%s", keyfile);
    char *slash = strrchr(clients_file, '/');
    if (slash) snprintf(slash + 1, sizeof(clients_file) - (size_t) (slash + 1 - clients_file), "airplay-clients.txt");
    dnssd = dnssd_init(name, (int) strlen(name), hw, 6, use_pin ? 1 : 0, &error);
    if (!dnssd) { result = -2; goto done; }
    /* Like UxPlay's defaults: no HLS video, no H.265, the newer pairing (not legacy). */
    dnssd_set_airplay_features(dnssd, 0, 0);
    dnssd_set_airplay_features(dnssd, 4, 0);
    dnssd_set_airplay_features(dnssd, 42, 0);
    dnssd_set_airplay_features(dnssd, 27, 0);

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = conn_init;
    cbs.conn_destroy = conn_destroy;
    cbs.conn_reset = conn_reset;
    cbs.conn_feedback = conn_feedback;
    cbs.audio_process = audio_process;
    cbs.video_process = video_process;
    cbs.audio_flush = audio_flush;
    cbs.video_flush = video_flush;
    cbs.video_pause = video_pause;
    cbs.video_resume = video_resume;
    cbs.video_reset = video_reset;
    cbs.audio_set_client_volume = audio_set_client_volume;
    cbs.audio_set_volume = audio_set_volume;
    cbs.audio_get_format = audio_get_format;
    cbs.report_client_request = report_client_request;
    cbs.register_client = register_client;
    cbs.check_register = check_register;
    cbs.video_set_codec_and_size = video_set_codec_and_size;
    cbs.display_pin = display_pin;

    raop = raop_init(&cbs);
    if (!raop) { result = -3; goto done; }
    raop_set_log_callback(raop, log_cb, NULL);
    raop_set_log_level(raop, LOGGER_INFO);
    if (raop_init2(raop, 1, mac, keyfile)) { free(raop); raop = NULL; result = -4; goto done; }
    if (width > 0) raop_set_plist(raop, "width", width);
    if (height > 0) raop_set_plist(raop, "height", height);
    if (fps > 0) { raop_set_plist(raop, "refreshRate", fps); raop_set_plist(raop, "maxFPS", fps); }
    /* Above 9999 means "this fixed code" to UxPlay (else a new random one each time). */
    if (use_pin) raop_set_plist(raop, "pin", 10000 + (pin % 10000));

    unsigned short tcp[2] = {0, 0};
    unsigned short udp[3] = {0, 0, 0};
    raop_set_tcp_ports(raop, tcp);
    raop_set_udp_ports(raop, udp);
    unsigned short port = raop_get_port(raop);
    if (raop_start_httpd(raop, &port) < 0) { raop_destroy(raop); raop = NULL; result = -5; goto done; }
    raop_set_port(raop, port);
    raop_set_dnssd(raop, dnssd);

    dnssd_register_raop(dnssd, port);
    dnssd_register_airplay(dnssd, port);
    result = port;

done:
    if (result < 0 && dnssd && !raop) { dnssd_destroy(dnssd); dnssd = NULL; }
    (*env)->ReleaseStringUTFChars(env, jname, name);
    (*env)->ReleaseStringUTFChars(env, jmac, mac);
    (*env)->ReleaseStringUTFChars(env, jkeyfile, keyfile);
    pthread_mutex_unlock(&lock);
    return result;
}

JNIEXPORT void JNICALL
Java_com_evilgreen_airplay_Native_stop(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    pthread_mutex_lock(&lock);
    if (dnssd) {
        dnssd_unregister_raop(dnssd);
        dnssd_unregister_airplay(dnssd);
    }
    if (raop) {
        raop_destroy(raop);
        raop = NULL;
    }
    if (dnssd) {
        dnssd_destroy(dnssd);
        dnssd = NULL;
    }
    open_connections = 0;
    pthread_mutex_unlock(&lock);
}
