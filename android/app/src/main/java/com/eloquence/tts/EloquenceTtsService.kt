/*
 * SPDX-License-Identifier: MIT
 *
 * EloquenceTtsService -- an Android TextToSpeechService backed by the converted
 * Apple Eloquence engine. Synthesis is delegated to libeloquence_jni.so, which
 * dlopens libeci.so + the language modules (shipped in the app's native lib dir)
 * and returns 11025 Hz mono S16 PCM that we hand to the platform callback
 * (upsampled to the user-selected output rate when needed).
 *
 * Engine data layout at runtime:
 *   - libeci.so + lib<lang>.so live in applicationInfo.nativeLibraryDir (installed
 *     read-only from app/src/main/jniLibs/arm64-v8a/, where execution is
 *     allowed -- you cannot dlopen executable code from writable app storage on
 *     modern Android).
 *   - eci.ini is generated into filesDir at first run with absolute Path=
 *     entries pointing into nativeLibraryDir; the engine reads it from cwd, so
 *     the JNI layer chdir()s to filesDir before eciNew.
 *
 * Speech settings (rate/pitch/volume/sample rate/voice) live in
 * SharedPreferences (see EloquencePrefs) and are edited from
 * EloquenceSettingsActivity, which is both the launcher entry ("Eloquence")
 * and the engine's settingsActivity.
 */
package com.eloquence.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.util.Locale

private const val TAG = "EloquenceTts"

/** Rate the engine itself renders at. Higher outputs are upsampled. */
private const val ENGINE_RATE = 11025

/** One shipped voice: ISO3 + ISO2 language/country -> ECI dialect + module file(s). */
private data class VoiceInfo(
    val iso3Lang: String,
    val iso3Country: String,
    val iso2Lang: String,
    val iso2Country: String,
    val dialect: Int,
    val module: String,            // lib<module>.so in nativeLibraryDir
    val romanizer: String? = null, // CJK only
)

// Mirrors release.yml's eci.ini table. Modules are installed as lib<name>.so.
private val VOICES = listOf(
    VoiceInfo("eng", "USA", "en", "US", 0x00010000, "enu"),
    VoiceInfo("eng", "GBR", "en", "GB", 0x00010001, "eng"),
    VoiceInfo("spa", "ESP", "es", "ES", 0x00020000, "esp"),
    VoiceInfo("spa", "MEX", "es", "MX", 0x00020001, "esm"),
    VoiceInfo("fra", "FRA", "fr", "FR", 0x00030000, "fra"),
    VoiceInfo("fra", "CAN", "fr", "CA", 0x00030001, "frc"),
    VoiceInfo("deu", "DEU", "de", "DE", 0x00040000, "deu"),
    VoiceInfo("ita", "ITA", "it", "IT", 0x00050000, "ita"),
    VoiceInfo("por", "BRA", "pt", "BR", 0x00070000, "ptb"),
    VoiceInfo("fin", "FIN", "fi", "FI", 0x00090000, "fin"),
    VoiceInfo("zho", "CHN", "zh", "CN", 0x00060000, "chs", "chsrom"),
    VoiceInfo("zho", "TWN", "zh", "TW", 0x00060001, "cht", "chtrom"),
    VoiceInfo("jpn", "JPN", "ja", "JP", 0x00080000, "jpn", "jpnrom"),
    VoiceInfo("kor", "KOR", "ko", "KR", 0x000A0000, "kor", "korrom"),
)

class EloquenceTtsService : TextToSpeechService() {

    private var handle: Long = 0
    private var currentDialect: Int = 0
    private val lock = Any()

    override fun onCreate() {
        // eci.ini must exist before the base class wires up languages.
        try {
            writeEciIni()
        } catch (e: Exception) {
            Log.e(TAG, "writeEciIni failed", e)
        }
        super.onCreate()
        // Engine itself is started lazily on first use so a conversion or
        // device issue can never take the whole service down at bind time.
    }

    override fun onDestroy() {
        synchronized(lock) {
            if (handle != 0L) {
                try {
                    EloquenceNative.nativeShutdown(handle)
                } catch (e: Exception) {
                    Log.e(TAG, "nativeShutdown failed", e)
                }
                handle = 0
            }
        }
        super.onDestroy()
    }

    // --- language plumbing -------------------------------------------------

    private fun match(lang: String?, country: String?): VoiceInfo? {
        if (lang == null) return null
        VOICES.firstOrNull { it.iso3Lang == lang && it.iso3Country == country }?.let { return it }
        return VOICES.firstOrNull { it.iso3Lang == lang } // language-only fallback
    }

    /** Voice used when the system asks for something we don't ship. */
    private fun preferredVoice(): VoiceInfo {
        val idx = try {
            EloquencePrefs.load(this).language
        } catch (e: Exception) {
            EloquencePrefs.DEFAULT_LANGUAGE
        }.coerceIn(0, VOICES.size - 1)
        return VOICES[idx]
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val v = match(lang, country) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        return when {
            v.iso3Lang == lang && v.iso3Country == country -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onGetLanguage(): Array<String> {
        val v = VOICES.firstOrNull { it.dialect == currentDialect } ?: VOICES.first()
        return arrayOf(v.iso3Lang, v.iso3Country, "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val v = match(lang, country) ?: preferredVoice().also {
            Log.w(TAG, "unsupported request $lang-$country, falling back to ${it.module}")
        }
        synchronized(lock) { ensureEngine(v.dialect) }
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onGetVoices(): List<Voice> {
        val out = mutableListOf<Voice>()
        for (v in VOICES) {
            try {
                out.add(
                    Voice(
                        "eloquence-${v.module}",
                        Locale(v.iso2Lang, v.iso2Country),
                        Voice.QUALITY_NORMAL,
                        Voice.LATENCY_NORMAL,
                        false,
                        emptySet(),
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "voice build failed for ${v.module}", e)
            }
        }
        return out
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        if (voiceName != null && VOICES.any { "eloquence-${it.module}" == voiceName }) {
            return TextToSpeech.SUCCESS
        }
        return TextToSpeech.ERROR
    }

    // --- synthesis ---------------------------------------------------------

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val v = match(request.language, request.country) ?: preferredVoice().also {
            Log.w(TAG, "synthesize for unsupported ${request.language}-${request.country}, " +
                "falling back to ${it.module}")
        }

        val text = request.charSequenceText?.toString().orEmpty()
        if (text.isEmpty()) { callback.error(); return }
        val prefs = EloquencePrefs.load(this)
        synchronized(lock) {
            if (!ensureEngine(v.dialect)) { callback.error(); return }
            try {
                val slot = prefs.voice.coerceIn(0, 7)
                EloquenceNative.nativeSetVoicePreset(handle, slot)
                // System TTS rate/pitch (percent, 100 = normal) combine with
                // the app's own settings. eciSpeed is 0..250 (~50 normal);
                // pitch scales the preset's baseline (~65..93 per voice).
                val combinedRate = request.speechRate * prefs.rate / 100
                val combinedPitch = request.pitch * prefs.pitch / 100
                val rate = (combinedRate * 50 / 100).coerceIn(0, 250)
                // GuangRong pitch curve: <=50 -> p*69/50, above -> +31/50 slope.
                // Combined 100% lands on 50 -> 69, the preset-neutral point.
                val pitchIn = (combinedPitch / 2).coerceIn(0, 100)
                val pitch = if (pitchIn <= 50) pitchIn * 69 / 50
                    else (pitchIn - 50) * 31 / 50 + 69
                var volume = prefs.volume
                try {
                    request.params?.let { b ->
                        if (b.containsKey(TextToSpeech.Engine.KEY_PARAM_VOLUME)) {
                            volume = (volume * b.getFloat(
                                TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)).toInt()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "bundle volume ignored", e)
                }
                EloquenceNative.nativeSetProsody(
                    handle, rate, pitch, volume.coerceIn(0, 100))

                // Engine rate via the ECI command (eciSampleRate=5):
                // 0=8kHz, 1=11025Hz, 2=test. Rejected values fall back
                // to 11025. The 48k choice renders 11025 natively, then
                // upsamples with libsoxr (very-high-quality).
                val eciParam = when (prefs.sampleMode) {
                    EloquencePrefs.MODE_8000_NATIVE -> 0
                    EloquencePrefs.MODE_16000_TEST -> 2
                    else -> 1
                }
                var engineHz = if (eciParam == 0) 8000 else ENGINE_RATE
                try {
                    if (EloquenceNative.nativeSetSampleRate(handle, eciParam) < 0) {
                        Log.w(TAG, "eciSampleRate=$eciParam rejected, fell back to 11025")
                        EloquenceNative.nativeSetSampleRate(handle, 1)
                        engineHz = ENGINE_RATE
                    } else if (eciParam == 2) {
                        Log.i(TAG, "eciSampleRate=2 accepted (16kHz path)")
                        engineHz = 16000
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "nativeSetSampleRate failed", e)
                    engineHz = ENGINE_RATE
                }

                val pcm = EloquenceNative.nativeSynthesize(handle, encodeForDialect(text, v.dialect))
                if (pcm == null) { callback.error(); return }
                if (prefs.sampleMode == EloquencePrefs.MODE_48000_SOXSR) {
                    val up = try {
                        EloquenceNative.nativeResample(pcm, engineHz, EloquencePrefs.HIGH_RATE_HZ)
                    } catch (e: Exception) {
                        Log.e(TAG, "nativeResample failed", e)
                        null
                    }
                    if (up != null) deliver(up, EloquencePrefs.HIGH_RATE_HZ, callback)
                    else deliver(pcm, engineHz, callback)
                } else {
                    deliver(pcm, engineHz, callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "synthesis failed", e)
                try {
                    callback.error()
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onStop() {
        synchronized(lock) {
            if (handle != 0L) {
                try {
                    EloquenceNative.nativeStop(handle)
                } catch (e: Exception) {
                    Log.e(TAG, "nativeStop failed", e)
                }
            }
        }
    }

    private fun deliver(pcm: ShortArray, outRate: Int, callback: SynthesisCallback) {
        // PCM is already at the engine-native rate — no resampling.
        callback.start(outRate, AudioFormat.ENCODING_PCM_16BIT, 1)
        val maxBytes = callback.maxBufferSize.coerceAtLeast(2)
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            bytes[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
        }
        var off = 0
        while (off < bytes.size) {
            val n = minOf(maxBytes, bytes.size - off)
            if (callback.audioAvailable(bytes, off, n) != TextToSpeech.SUCCESS) break
            off += n
        }
        callback.done()
    }

    /** Encode text in the dialect's own charset (the engine is not UTF-8). */
    private fun encodeForDialect(text: String, dialect: Int): ByteArray {
        val name = when (dialect) {
            0x00060000 -> "GB18030"   // Simplified Chinese
            0x00060001 -> "Big5"      // Traditional Chinese
            0x00080000 -> "Shift_JIS" // Japanese
            0x000A0000 -> "EUC-KR"    // Korean
            else -> "windows-1252"    // Latin scripts
        }
        return try {
            text.toByteArray(charset(name))
        } catch (e: Exception) {
            Log.w(TAG, "charset $name unavailable, ASCII fallback", e)
            text.toByteArray(Charsets.US_ASCII)
        }
    }

    // --- engine lifecycle --------------------------------------------------

    private fun ensureEngine(dialect: Int): Boolean {
        if (handle != 0L && dialect == currentDialect) return true
        if (handle != 0L) {
            try {
                EloquenceNative.nativeShutdown(handle)
            } catch (e: Exception) {
                Log.e(TAG, "nativeShutdown failed", e)
            }
            handle = 0
        }
        return try {
            handle = EloquenceNative.nativeInit(
                filesDir.absolutePath, applicationInfo.nativeLibraryDir, dialect)
            if (handle == 0L) {
                Log.e(TAG, "nativeInit failed for dialect 0x%08x".format(dialect))
                false
            } else {
                currentDialect = dialect
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "nativeInit threw for dialect 0x%08x".format(dialect), e)
            handle = 0
            false
        }
    }

    private fun writeEciIni() {
        val libDir = applicationInfo.nativeLibraryDir
        val sb = StringBuilder()
        sb.append("# Generated by EloquenceTtsService. Path= entries point at the\n")
        sb.append("# app's read-only native lib dir (execute-allowed).\n\n")
        for (v in VOICES) {
            val hi = (v.dialect ushr 16) and 0xFFFF
            val lo = v.dialect and 0xFFFF
            sb.append("[$hi.$lo]\n")
            sb.append("Path=$libDir/lib${v.module}.so\n")
            v.romanizer?.let { sb.append("Path_Rom=$libDir/lib$it.so\n") }
            sb.append("Version=6.1\n\n")
        }
        // Standard voice table — the language libs' register_voices needs
        // this section to initialise the voice table (working-device recipe).
        sb.append("Voice1=0 50 65 30 0 0 50 92\n")    // Reed
        sb.append("Voice2=0 50 81 50 0 0 50 95\n")    // Shelley
        sb.append("Voice3=0 50 93 50 0 0 22 95\n")    // Sandy
        sb.append("Voice4=0 50 56 0 0 0 86 93\n")     // Rocko
        sb.append("Voice5=0 50 65 30 0 0 50 92\n")    // spare
        sb.append("Voice6=0 50 89 40 0 0 56 95\n")    // Flo
        sb.append("Voice7=0 50 68 40 3 0 45 90\n")    // Grandma
        sb.append("Voice8=0 50 61 20 18 0 30 90\n")   // Grandpa
        sb.append("Voice9=0 50 69 0 0 0 50 92\n")     // Eddy
        File(filesDir, "eci.ini").writeText(sb.toString())
    }
}
