/*
 * Bionic lab harness — mimics the app's exact engine sequence under a real
 * Bionic linker64, so conversion problems reproduce without a device:
 *
 *   dlopen(libeci, NOW|GLOBAL) -> eciNewEx(dialect) [else eciNew()]
 *   -> Klatt(NULL) -> RegisterCallback -> SetOutputBuffer -> SetParam(5,1)
 *   -> ClearInput? -> AddText -> Synthesize -> Synchronize -> drain Speaking
 *
 * Usage: harness <libeci.so path> <eci.ini path> <dialect hex, e.g. 10000>
 * CWD + ECIINI are set from the ini path, exactly like the app.
 * Exit 0 + "PCM: N samples" on success; nonzero + reason on failure.
 * A native crash shows up as SIGSEGV/SIGABRT (exit 139/134 via timeout).
 */
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef void *ECIHand;
typedef const void *ECIInputText;
enum { eciWaveformBuffer = 0, eciDataProcessed = 1, eciDataAbort = 2 };
typedef int (*CB)(ECIHand, int, long, void *);

#define CHUNK 8192
static short g_chunk[CHUNK];
static short *g_pcm = 0;
static long g_len = 0, g_cap = 0;

static int cb(ECIHand h, int msg, long lp, void *ud) {
    (void)h; (void)ud;
    if (msg != eciWaveformBuffer || lp <= 0) return eciDataProcessed;
    long need = g_len + lp;
    if (need > g_cap) {
        long nc = g_cap ? g_cap * 2 : 65536;
        while (nc < need) nc *= 2;
        short *p = (short *)realloc(g_pcm, (size_t)nc * sizeof(short));
        if (!p) return eciDataAbort;
        g_pcm = p;
        g_cap = nc;
    }
    memcpy(g_pcm + g_len, g_chunk, (size_t)lp * sizeof(short));
    g_len += lp;
    return eciDataProcessed;
}

int main(int argc, char **argv) {
    if (argc < 4) { fprintf(stderr, "usage: %s libeci.so eci.ini dialect-hex\n", argv[0]); return 2; }
    const char *libpath = argv[1];
    const char *inipath = argv[2];
    int dialect = (int)strtol(argv[3], 0, 16);

    /* cwd + ECIINI from the ini path (mirrors the app). */
    char dir[1024];
    snprintf(dir, sizeof dir, "%s", inipath);
    char *sl = strrchr(dir, '/');
    if (sl) { *sl = '\0'; if (chdir(dir) != 0) { printf("CHDIR_FAIL\n"); return 3; } }
    setenv("ECIINI", inipath, 1);

    dlerror();
    void *lib = dlopen(libpath, 2 /*RTLD_NOW*/ | 0x100 /*RTLD_GLOBAL*/);
    if (!lib) { printf("DLOPEN_FAIL: %s\n", dlerror()); return 4; }
    printf("DLOPEN_OK\n");

    ECIHand (*NewEx)(int) = (void *)dlsym(lib, "eciNewEx");
    ECIHand (*New)(void) = (void *)dlsym(lib, "eciNew");
    void (*Del)(ECIHand);
    int (*SetParam)(ECIHand, int, int);
    int (*AddText)(ECIHand, ECIInputText);
    int (*Synth)(ECIHand);
    int (*Sync)(ECIHand);
    int (*Speaking)(ECIHand);
    void (*RegCb)(ECIHand, CB, void *);
    int (*SetOut)(ECIHand, int, short *);
    void (*Klatt)(ECIHand, void *, void *, void *);
    int (*Clear)(ECIHand);
    void (*Ver)(char *);
    Del = (void *)dlsym(lib, "eciDelete");
    SetParam = (void *)dlsym(lib, "eciSetParam");
    AddText = (void *)dlsym(lib, "eciAddText");
    Synth = (void *)dlsym(lib, "eciSynthesize");
    Sync = (void *)dlsym(lib, "eciSynchronize");
    Speaking = (void *)dlsym(lib, "eciSpeaking");
    RegCb = (void *)dlsym(lib, "eciRegisterCallback");
    SetOut = (void *)dlsym(lib, "eciSetOutputBuffer");
    Klatt = (void *)dlsym(lib, "eciRegisterKlattHooks2");
    Clear = (void *)dlsym(lib, "eciClearInput");
    Ver = (void *)dlsym(lib, "eciVersion");
    if (!RegCb || !AddText || !Synth || !SetOut) { printf("SYMBOLS_MISSING\n"); return 5; }
    printf("klatt=%s clear=%s\n", Klatt ? "present" : "MISSING", Clear ? "present" : "MISSING");
    if (Ver) { char v[64] = {0}; Ver(v); printf("eciVersion: %s\n", v); }

    ECIHand h = 0;
    if (NewEx && dialect) h = NewEx(dialect);
    printf("eciNewEx(0x%x): %s\n", dialect, h ? "ok" : "NULL");
    if (!h && New) { h = New(); printf("eciNew(): %s\n", h ? "ok" : "NULL"); }
    if (!h) { printf("RESULT: ENGINE FAILED\n"); return 6; }
    (void)Del;

    if (Klatt) Klatt(h, 0, 0, 0);
    RegCb(h, cb, 0);
    SetOut(h, CHUNK, g_chunk);
    if (SetParam) SetParam(h, 5, 1);
    if (Clear) Clear(h);
    const char *text = "Hello world from the Bionic lab.";
    int r = AddText(h, (ECIInputText)text);
    printf("eciAddText: r=%d\n", r);
    if (r <= 0) { printf("RESULT: ADDTEXT_REFUSED\n"); return 7; }
    Synth(h);
    if (Sync) Sync(h);
    if (Speaking) { int g = 0; while (Speaking(h) && g++ < 100000) usleep(1000); }
    long peak = 0;
    for (long i = 0; i < g_len; i++) { long a = g_pcm[i] < 0 ? -g_pcm[i] : g_pcm[i]; if (a > peak) peak = a; }
    printf("PCM: %ld samples, peak %ld\n", g_len, peak);
    printf("RESULT: %s\n", g_len > 0 ? "ENGINE USABLE" : "ENGINE FAILED (0 samples)");
    return g_len > 0 ? 0 : 8;
}
