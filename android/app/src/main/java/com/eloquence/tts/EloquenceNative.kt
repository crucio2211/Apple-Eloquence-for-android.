/*
 * SPDX-License-Identifier: MIT
 *
 * EloquenceNative -- thin Kotlin view of libeloquence_jni.so. Each method maps
 * 1:1 to a Java_com_eloquence_tts_EloquenceNative_* entry point in
 * android/jni/eloquence_jni.c.
 */
package com.eloquence.tts

object EloquenceNative {
    init { System.loadLibrary("eloquence_jni") }

    /**
     * Initialise an engine instance.
     * @param dataDir writable dir holding the generated eci.ini (cwd for the engine).
     * @param libDir read-only native lib dir holding libeci.so + language modules.
     * @param dialect eciLanguageDialect code (e.g. 0x00010000 = US English),
     *                or 0 for the engine default.
     * @return opaque native handle, or 0 on failure.
     */
    external fun nativeInit(dataDir: String, libDir: String, dialect: Int): Long

    /** Set speed/pitch/volume; pass -1 to leave a field unchanged. */
    external fun nativeSetProsody(handle: Long, rate: Int, pitch: Int, volume: Int)

    /**
     * Apply an Apple voice preset's timbre (slot 0..7: Reed, Shelley, Sandy,
     * Rocko, Flo, Grandma, Grandpa, Eddy). Pitch/speed/volume stay under
     * prosody control -- call [nativeSetProsody] afterwards.
     */
    external fun nativeSetVoicePreset(handle: Long, slot: Int)

    /** Engine version string for an open handle, or null (diagnostics). */
    external fun nativeEngineVersion(handle: Long): String?

    /**
     * Set the engine-native sample rate via the ECI command
     * (eciSetParam, eciSampleRate=5: 0=8kHz, 1=11025Hz, 2=test).
     * @return engine return code (< 0 = rejected).
     */
    external fun nativeSetSampleRate(handle: Long, param: Int): Int

    /**
     * High-quality resample via libsoxr (very-high-quality preset).
     * @return resampled PCM, or null on failure / unsupported build.
     */
    external fun nativeResample(pcm: ShortArray, fromRate: Int, toRate: Int): ShortArray?

    /**
     * soxr self-test for diagnostics: resamples dummy audio 11025->48000.
     * Never null; e.g. "OK in=2205 out=9600" or "FAIL: <reason>".
     */
    external fun nativeSoxrSelfTest(): String

    /**
     * Probe step 1 (safe): environment + libeci load. Reports chdir, ECIINI,
     * eci.ini dump, every dlopen attempt with loader error text, key
     * symbols, and eciVersion. Always returns a report string.
     */
    external fun nativeProbeLoad(dataDir: String, libDir: String): String

    /**
     * Probe step 2 (may crash natively on bad libs): engine instance
     * creation via eciNewEx(dialect) with eciNew() fallback.
     */
    external fun nativeProbeInit(
        dataDir: String, libDir: String, dialect: Int,
    ): String

    /**
     * Probe step 3 (may crash natively on bad libs): direct loads of the
     * language (+romanizer) libs the engine dlopens internally.
     */
    external fun nativeProbeLang(
        libDir: String, module: String?, romanizer: String?,
    ): String

    /**
     * Synthesize one utterance. Text arrives as bytes in the dialect's own
     * charset (windows-1252 for Latin scripts, GB18030/Big5/Shift_JIS/EUC-KR
     * for CJK) — the engine is an IBM-charset engine, not UTF-8.
     * @return 11025 Hz mono S16 PCM, or null on abort/failure.
     */
    external fun nativeSynthesize(handle: Long, text: ByteArray): ShortArray?

    /** Abort the in-flight utterance (called from onStop). */
    external fun nativeStop(handle: Long)

    /** Tear down the engine instance. */
    external fun nativeShutdown(handle: Long)
}
