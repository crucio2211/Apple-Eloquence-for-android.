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
        val v = match(lang, country) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        synchronized(lock) { ensureEngine(v.dialect) }
        return onIsLanguageAvailable(lang, country, variant)
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
        val v = match(request.language, request.country)
        if (v == null) { callback.error(); return }

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
                val base = EloquencePrefs.VOICE_PITCH_BASE[slot]
                val pitch = (base * combinedPitch / 100).coerceIn(0, 100)
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

                val pcm = EloquenceNative.nativeSynthesize(handle, text)
                if (pcm == null) { callback.error(); return }
                deliver(pcm, prefs.sampleRate, callback)
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
        val out = if (outRate == ENGINE_RATE) pcm else resample(pcm, ENGINE_RATE, outRate)
        callback.start(outRate, AudioFormat.ENCODING_PCM_16BIT, 1)
        val maxBytes = callback.maxBufferSize.coerceAtLeast(2)
        val bytes = ByteArray(out.size * 2)
        for (i in out.indices) {
            bytes[i * 2] = (out[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((out[i].toInt() shr 8) and 0xFF).toByte()
        }
        var off = 0
        while (off < bytes.size) {
            val n = minOf(maxBytes, bytes.size - off)
            if (callback.audioAvailable(bytes, off, n) != TextToSpeech.SUCCESS) break
            off += n
        }
        callback.done()
    }

    /** Linear-interpolation upsampler (engine renders 11025 Hz natively). */
    private fun resample(pcm: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (pcm.isEmpty() || fromRate == toRate || fromRate <= 0 || toRate <= 0) return pcm
        val outLen = ((pcm.size.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in out.indices) {
            val pos = i.toLong() * fromRate
            val idx = (pos / toRate).toInt().coerceIn(0, pcm.size - 1)
            val next = (idx + 1).coerceIn(0, pcm.size - 1)
            val frac = ((pos % toRate).toFloat() / toRate)
            out[i] = (pcm[idx] * (1f - frac) + pcm[next] * frac).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
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
        File(filesDir, "eci.ini").writeText(sb.toString())
    }
}
