/*
 * SPDX-License-Identifier: MIT
 *
 * eloquence_jni.c -- JNI bridge between an Android TextToSpeechService and the
 * converted Apple Eloquence engine (eci.so + language modules).
 *
 * The engine is dlopen'd at runtime from the app's read-only native library
 * dir (where Gradle installs jniLibs); its ECI C API is
 * resolved by dlsym. Synthesis is pull-based: AddText + Synthesize, with the
 * engine delivering 16-bit PCM through an ECI callback that we accumulate into
 * a Java short[]. The Java side (EloquenceTtsService) feeds that to the
 * platform SynthesisCallback.
 *
 * Two directories are involved, passed separately by Java:
 *   - dataDir: writable files dir holding the generated eci.ini. The engine
 *     resolves eci.ini relative to cwd, so we chdir() here before eciNew.
 *   - libDir: applicationInfo.nativeLibraryDir holding eci.so + the language
 *     modules (execute-allowed; writable app storage is blocked by W^X).
 *
 * Engine quirks handled here mirror examples/speak.c and sd_eloquence:
 *   - RegisterCallback must precede SetOutputBuffer.
 *   - Synchronize is non-blocking on Apple's build; we drain via Speaking().
 *   - eci.ini (with absolute Path= entries) must be discoverable; the Java
 *     side writes it into dataDir and we chdir there before eciNew.
 */
#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <stdint.h>
#include <android/log.h>

#define LOG_TAG "EloquenceJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* --- Minimal ECI ABI (mirrors sd_eloquence/src/eci/eci.h) ----------------- */
typedef void *ECIHand;
typedef const void *ECIInputText;
enum ECIMessage        { eciWaveformBuffer = 0 };
enum ECICallbackReturn { eciDataNotProcessed = 0, eciDataProcessed = 1, eciDataAbort = 2 };
enum ECIParam          { eciSampleRate = 5, eciLanguageDialect = 9 };
enum ECIVoiceParam     {
    eciGender = 0, eciHeadSize = 1, eciPitchBaseline = 2,
    eciPitchFluctuation = 3, eciRoughness = 4, eciBreathiness = 5,
    eciSpeed = 6, eciVolume = 7
};
typedef enum ECICallbackReturn (*ECICallback)(ECIHand, enum ECIMessage, long, void *);

/* Resolved entry points (names as exported by the converted eci.so). */
typedef ECIHand (*fn_New)(void);
typedef ECIHand (*fn_NewEx)(int);
typedef int     (*fn_Delete)(ECIHand);
typedef void    (*fn_Version)(char *);
typedef int     (*fn_SetParam)(ECIHand, int, int);
typedef int     (*fn_SetVoiceParam)(ECIHand, int, int, int);
typedef int     (*fn_AddText)(ECIHand, ECIInputText);
typedef int     (*fn_Synthesize)(ECIHand);
typedef int     (*fn_Synchronize)(ECIHand);
typedef int     (*fn_Speaking)(ECIHand);
typedef int     (*fn_Stop)(ECIHand);
typedef void    (*fn_RegisterCallback)(ECIHand, ECICallback, void *);
typedef int     (*fn_SetOutputBuffer)(ECIHand, int, short *);
typedef void    (*fn_RegisterKlattHooks2)(ECIHand, void *, void *, void *);
typedef int     (*fn_ClearInput)(ECIHand);

/* One engine instance per native handle. */
#define CHUNK_SAMPLES 8192
typedef struct {
    void *lib;                 /* dlopen handle for eci.so                    */
    ECIHand eci;               /* engine handle                              */
    fn_Delete         Delete;
    fn_SetParam       SetParam;
    fn_SetVoiceParam  SetVoiceParam;
    fn_AddText        AddText;
    fn_Synthesize     Synthesize;
    fn_Synchronize    Synchronize;
    fn_Speaking       Speaking;
    fn_Stop           Stop;
    fn_SetOutputBuffer SetOutputBuffer;
    fn_RegisterKlattHooks2 RegisterKlattHooks2; /* optional; CJK needs it */
    fn_ClearInput     ClearInput;               /* optional; reset before AddText */
    short  chunk[CHUNK_SAMPLES];
    short *pcm;                /* accumulation buffer for the active utterance */
    long   pcm_len;
    long   pcm_cap;
    int    abort;             /* set by Java onStop to short-circuit          */
} Engine;

static enum ECICallbackReturn pcm_cb(ECIHand h, enum ECIMessage msg,
                                     long lParam, void *pData) {
    (void)h;
    Engine *e = (Engine *)pData;
    if (e->abort) return eciDataAbort;
    if (msg != eciWaveformBuffer || lParam <= 0) return eciDataProcessed;
    long need = e->pcm_len + lParam;
    if (need > e->pcm_cap) {
        long ncap = e->pcm_cap ? e->pcm_cap * 2 : 65536;
        while (ncap < need) ncap *= 2;
        short *p = (short *)realloc(e->pcm, (size_t)ncap * sizeof(short));
        if (!p) return eciDataAbort;
        e->pcm = p;
        e->pcm_cap = ncap;
    }
    memcpy(e->pcm + e->pcm_len, e->chunk, (size_t)lParam * sizeof(short));
    e->pcm_len += lParam;
    return eciDataProcessed;
}

static void *sym(void *lib, const char *name) {
    void *p = dlsym(lib, name);
    if (!p) LOGE("missing symbol: %s", name);
    return p;
}

/* nativeInit(dataDir, libDir, dialect) -> handle (0 on failure).
 * dataDir holds the generated eci.ini (engine reads it from cwd);
 * libDir holds eci.so + the language .so files. */
JNIEXPORT jlong JNICALL
Java_com_eloquence_tts_EloquenceNative_nativeInit(JNIEnv *env, jclass clazz,
                                                  jstring jDataDir, jstring jLibDir,
                                                  jint dialect) {
    (void)clazz;
    const char *dataDir = (*env)->GetStringUTFChars(env, jDataDir, NULL);
    const char *libDir  = (*env)->GetStringUTFChars(env, jLibDir, NULL);

    /* The engine finds eci.ini two ways: relative to cwd, and via the ECIINI
     * environment variable. Set both — ECIINI is the robust one on Android
     * (a working-device recipe), chdir covers engines that only use cwd. */
    if (chdir(dataDir) != 0) LOGE("chdir(%s) failed", dataDir);
    {
        char iniPath[1152];
        snprintf(iniPath, sizeof iniPath, "%s/eci.ini", dataDir);
        setenv("ECIINI", iniPath, 1);
        LOGI("ECIINI=%s", iniPath);
    }

    /* eci.so lives in the read-only native lib dir, not in filesDir. Try the
     * absolute libDir path first, then fall back to the linker search path
     * (the app's nativeLibraryDir is visible to dlopen on modern Android). */
    char path[1024];
    const char *candidates[4];
    snprintf(path, sizeof path, "%s/libeci.so", libDir);
    candidates[0] = path;
    static char alt[1024];
    snprintf(alt, sizeof alt, "%s/eci.so", libDir);
    candidates[1] = alt;
    candidates[2] = "libeci.so";
    candidates[3] = "eci.so";
    void *lib = NULL;
    for (int i = 0; i < 4 && !lib; i++) {
        lib = dlopen(candidates[i], RTLD_NOW | RTLD_GLOBAL);
        if (lib) LOGI("dlopen %s: ok", candidates[i]);
    }
    (*env)->ReleaseStringUTFChars(env, jDataDir, dataDir);
    (*env)->ReleaseStringUTFChars(env, jLibDir, libDir);
    if (!lib) { LOGE("dlopen eci.so: %s", dlerror()); return 0; }

    Engine *e = (Engine *)calloc(1, sizeof(Engine));
    if (!e) { dlclose(lib); return 0; }
    e->lib = lib;

    fn_NewEx            NewEx            = (fn_NewEx)sym(lib, "eciNewEx");
    fn_New              New              = (fn_New)sym(lib, "eciNew");
    fn_RegisterCallback RegisterCallback = (fn_RegisterCallback)sym(lib, "eciRegisterCallback");
    e->Delete          = (fn_Delete)sym(lib, "eciDelete");
    e->SetParam        = (fn_SetParam)sym(lib, "eciSetParam");
    e->SetVoiceParam   = (fn_SetVoiceParam)sym(lib, "eciSetVoiceParam");
    e->AddText         = (fn_AddText)sym(lib, "eciAddText");
    e->Synthesize      = (fn_Synthesize)sym(lib, "eciSynthesize");
    e->Synchronize     = (fn_Synchronize)sym(lib, "eciSynchronize");
    e->Speaking        = (fn_Speaking)sym(lib, "eciSpeaking");
    e->Stop            = (fn_Stop)sym(lib, "eciStop");
    e->SetOutputBuffer = (fn_SetOutputBuffer)sym(lib, "eciSetOutputBuffer");
    /* Optional but load-bearing on real devices (working-device recipe):
     * Klatt hooks keep CJK synthesis from crashing, ClearInput resets the
     * queue before each utterance. */
    e->RegisterKlattHooks2 = (fn_RegisterKlattHooks2)dlsym(lib, "eciRegisterKlattHooks2");
    e->ClearInput          = (fn_ClearInput)dlsym(lib, "eciClearInput");
    if (!e->RegisterKlattHooks2) LOGE("WARN: eciRegisterKlattHooks2 missing (CJK may crash)");

    if (!RegisterCallback || !e->AddText || !e->Synthesize || !e->SetOutputBuffer) {
        LOGE("required ECI symbols missing");
        free(e); dlclose(lib); return 0;
    }

    /* Prefer eciNewEx(dialect) so the language module loads up front.
     * Fall back to plain eciNew() (the examples/speak.c path): some builds
     * fail eciNewEx while eciNew still works. */
    e->eci = NULL;
    if (NewEx && dialect) {
        e->eci = NewEx(dialect);
        if (!e->eci) LOGE("eciNewEx(0x%x) failed, trying eciNew()", dialect);
    }
    if (!e->eci) e->eci = New ? New() : NULL;
    if (!e->eci) { LOGE("eciNew/eciNewEx failed (check eci.ini + ProgStatus)"); free(e); return 0; }

    /* Null Klatt hooks first (working-device recipe; CJK needs them). */
    if (e->RegisterKlattHooks2) e->RegisterKlattHooks2(e->eci, NULL, NULL, NULL);
    RegisterCallback(e->eci, pcm_cb, e);               /* before SetOutputBuffer */
    e->SetOutputBuffer(e->eci, CHUNK_SAMPLES, e->chunk);
    if (e->SetParam) e->SetParam(e->eci, eciSampleRate, 1);  /* 11025 Hz (Apple) */

    char ver[64] = {0};
    fn_Version Version = (fn_Version)dlsym(lib, "eciVersion");
    if (Version) { Version(ver); LOGI("Eloquence engine %s ready (dialect=0x%x)", ver, dialect); }
    return (jlong)(intptr_t)e;
}

JNIEXPORT void JNICALL
Java_com_eloquence_tts_EloquenceNative_nativeSetProsody(JNIEnv *env, jclass c,
        jlong handle, jint rate, jint pitch, jint volume) {
    (void)env; (void)c;
    Engine *e = (Engine *)(intptr_t)handle;
    if (!e || !e->SetVoiceParam) return;
    if (rate   >= 0) e->SetVoiceParam(e->eci, 0, eciSpeed,        rate);
    if (pitch  >= 0) e->SetVoiceParam(e->eci, 0, eciPitchBaseline, pitch);
    if (volume >= 0) e->SetVoiceParam(e->eci, 0, eciVolume,       volume);
}

/* Apple voice presets, transcribed from KonaVoicePresets.plist (mirrors
 * sd_eloquence/src/eci/voices.c). Columns: gender, headSize, pitchBaseline,
 * pitchFluctuation, roughness, breathiness, volume. Pitch/speed/volume are
 * owned by the prosody path; the preset sets timbre only. */
typedef struct { int gender, head, base, fluct, rough, breath, vol; } Preset;
static const Preset PRESETS[8] = {
    { 0, 50, 65, 30,  0,  0, 92 },  /* 0 Reed    (Jacques in French) */
    { 1, 50, 81, 30,  0, 50, 95 },  /* 1 Shelley */
    { 1, 22, 93, 30,  0, 50, 95 },  /* 2 Sandy   */
    { 0, 86, 56, 47,  0,  0, 93 },  /* 3 Rocko   */
    { 1, 56, 89, 35,  0, 40, 95 },  /* 4 Flo     */
    { 1, 45, 68, 30,  3, 40, 90 },  /* 5 Grandma */
    { 0, 30, 61, 44, 18, 20, 90 },  /* 6 Grandpa */
    { 0, 50, 69, 34,  0,  0, 92 },  /* 7 Eddy    */
};

/* Apply a voice preset's timbre to slot 0. Pitch/speed/volume stay under the
 * caller's prosody control (call nativeSetProsody afterwards). */
JNIEXPORT void JNICALL
Java_com_eloquence_tts_EloquenceNative_nativeSetVoicePreset(JNIEnv *env, jclass c,
        jlong handle, jint slot) {
    (void)env; (void)c;
    Engine *e = (Engine *)(intptr_t)handle;
    if (!e || !e->SetVoiceParam || slot < 0 || slot > 7) return;
    const Preset *p = &PRESETS[slot];
    e->SetVoiceParam(e->eci, 0, eciGender,           p->gender);
    e->SetVoiceParam(e->eci, 0, eciHeadSize,         p->head);
    e->SetVoiceParam(e->eci, 0, eciPitchFluctuation, p->fluct);
    e->SetVoiceParam(e->eci, 0, eciRoughness,        p->rough);
    e->SetVoiceParam(e->eci, 0, eciBreathiness,      p->breath);
    LOGI("voice preset %d applied", slot);
}

/* Step-by-step init probe for the on-device diagnostics screen. Reports which
 * stage fails: chdir, dlopen (with the loader's own error text), missing
 * symbols, eciNewEx, eciNew. Never throws; always returns a report string. */
JNIEXPORT jstring JNICALL
Java_com_eloquence_tts_EloquenceNative_nativeProbe(JNIEnv *env, jclass clazz,
                                                   jstring jDataDir, jstring jLibDir,
                                                   jint dialect) {
    (void)clazz;
    char out[4096];
    size_t n = 0;
#define P(...) do { int w = snprintf(out + n, sizeof(out) - n, __VA_ARGS__); \
                    if (w > 0) n += (size_t)w; } while (0)
    const char *dataDir = (*env)->GetStringUTFChars(env, jDataDir, NULL);
    const char *libDir  = (*env)->GetStringUTFChars(env, jLibDir, NULL);

    P("chdir %s: %s\n", dataDir, chdir(dataDir) == 0 ? "ok" : "FAILED");
    {
        char iniPath[1152];
        snprintf(iniPath, sizeof iniPath, "%s/eci.ini", dataDir);
        setenv("ECIINI", iniPath, 1);
        P("ECIINI=%s\n", iniPath);
    }

    char p0[1024], p1[1024];
    snprintf(p0, sizeof p0, "%s/libeci.so", libDir);
    snprintf(p1, sizeof p1, "%s/eci.so", libDir);
    const char *cands[4] = { p0, p1, "libeci.so", "eci.so" };
    void *lib = NULL;
    for (int i = 0; i < 4 && !lib; i++) {
        dlerror();
        lib = dlopen(cands[i], RTLD_NOW | RTLD_GLOBAL);
        if (lib) {
            P("dlopen %s: ok\n", cands[i]);
        } else {
            const char *e = dlerror();
            char eb[512];
            snprintf(eb, sizeof eb, "%s", e ? e : "(no error text)");
            P("dlopen %s: FAILED: %s\n", cands[i], eb);
        }
    }
    (*env)->ReleaseStringUTFChars(env, jDataDir, dataDir);
    (*env)->ReleaseStringUTFChars(env, jLibDir, libDir);
    if (!lib) { P("RESULT: dlopen failed\n"); return (*env)->NewStringUTF(env, out); }

    fn_New New = (fn_New)dlsym(lib, "eciNew");
    fn_NewEx NewEx = (fn_NewEx)dlsym(lib, "eciNewEx");
    P("symbols: eciNew=%s eciNewEx=%s\n",
      New ? "ok" : "MISSING", NewEx ? "ok" : "MISSING");
    fn_Version Version = (fn_Version)dlsym(lib, "eciVersion");
    if (Version) { char ver[64] = {0}; Version(ver); P("eciVersion: %s\n", ver); }
    else P("eciVersion: MISSING\n");

    ECIHand h = NULL;
    if (NewEx && dialect) {
        h = NewEx(dialect);
        P("eciNewEx(0x%x): %s\n", (unsigned)dialect, h ? "ok" : "NULL");
    } else {
        P("eciNewEx: skipped (no symbol or dialect=0)\n");
    }
    if (!h && New) {
        h = New();
        P("eciNew(): %s\n", h ? "ok" : "NULL");
    }
    P("klattHooks=%s clearInput=%s\n",
      dlsym(lib, "eciRegisterKlattHooks2") ? "present" : "MISSING",
      dlsym(lib, "eciClearInput") ? "present" : "MISSING");
    /* NOTE: deliberately NOT calling eciDelete here — on this build it can
     * join an already-destroyed worker thread and SIGABRT the process. */
    P("RESULT: %s\n", h ? "ENGINE USABLE" : "ENGINE FAILED");
    return (*env)->NewStringUTF(env, out);
#undef P
}

/* Return the engine version string for an open handle (diagnostics). */
JNIEXPORT jstring JNICALL
Java_com_eloquence_tts_EloquenceNative_nativeEngineVersion(JNIEnv *env, jclass c,
        jlong handle) {
    (void)c;
    Engine *e = (Engine *)(intptr_t)handle;
    if (!e) return NULL;
    fn_Version Version = (fn_Version)dlsym(e->lib, "eciVersion");
    if (!Version) return NULL;
    char ver[64] = {0};
    Version(ver);
    return (*env)->NewStringUTF(env, ver);
}

/* Set the engine-native sample rate via the ECI command itself
 * (eciSetParam, eciSampleRate=5: 0=8kHz, 1=11025Hz, 2=test).
 * Returns the engine's return code (< 0 = rejected). */
JNIEXPORT jint JNICALL
Java_com_eloquence_tts_EloquenceNative_nativeSetSampleRate(JNIEnv *env, jclass c,
        jlong handle, jint param) {
    (void)env; (void)c;
    Engine *e = (Engine *)(intptr_t)handle;
    if (!e || !e->SetParam) return -1;
    return e->SetParam(e->eci, eciSampleRate, param);
}

/* Synthesize one utterance synchronously; text arrives as bytes in the
 * dialect's own charset (windows-1252 for Latin scripts, GB18030/Big5/
 * Shift_JIS/EUC-KR for CJK — the engine is an IBM-charset engine, not
 * UTF-8). Returns 11025 Hz mono S16 PCM, or null on failure/abort. */
JNIEXPORT jshortArray JNICALL
Java_com_eloquence_tts_EloquenceNative_nativeSynthesize(JNIEnv *env, jclass c,
        jlong handle, jbyteArray jText) {
    (void)c;
    Engine *e = (Engine *)(intptr_t)handle;
    if (!e) return NULL;
    jsize len = (*env)->GetArrayLength(env, jText);
    if (len <= 0 || len > 100000) return NULL;
    char *text = (char *)malloc((size_t)len + 1);
    if (!text) return NULL;
    (*env)->GetByteArrayRegion(env, jText, 0, len, (jbyte *)text);
    text[len] = '\0';

    e->pcm_len = 0;
    e->abort = 0;
    if (e->ClearInput) e->ClearInput(e->eci);
    int r = e->AddText(e->eci, (ECIInputText)text);
    free(text);
    if (r <= 0) { LOGE("eciAddText refused text (r=%d)", r); return NULL; }
    e->Synthesize(e->eci);
    if (e->Synchronize) e->Synchronize(e->eci);
    /* Apple's Synchronize is non-blocking; drain the worker thread. */
    if (e->Speaking) { int guard = 0; while (e->Speaking(e->eci) && !e->abort && guard++ < 100000) usleep(1000); }

    if (e->abort || e->pcm_len == 0) return NULL;
    jshortArray out = (*env)->NewShortArray(env, (jsize)e->pcm_len);
    if (out) (*env)->SetShortArrayRegion(env, out, 0, (jsize)e->pcm_len, e->pcm);
    return out;
}

JNIEXPORT void JNICALL
Java_com_eloquence_tts_EloquenceNative_nativeStop(JNIEnv *env, jclass c, jlong handle) {
    (void)env; (void)c;
    Engine *e = (Engine *)(intptr_t)handle;
    if (!e) return;
    /* Abort flag only. Deliberately NOT calling eciStop(): on this build it
     * terminates an already-destroyed worker thread and SIGABRTs the process.
     * The drain loop in nativeSynthesize observes the flag and returns. */
    e->abort = 1;
}

JNIEXPORT void JNICALL
Java_com_eloquence_tts_EloquenceNative_nativeShutdown(JNIEnv *env, jclass c, jlong handle) {
    (void)env; (void)c;
    Engine *e = (Engine *)(intptr_t)handle;
    if (!e) return;
    /* Deliberately NOT calling eciDelete() or dlclose(): both can join
     * already-destroyed engine threads (SIGABRT) or run C++ atexit
     * destructors in the wrong order. Leak the handle; the process owns it. */
    free(e->pcm);
    free(e);
}
