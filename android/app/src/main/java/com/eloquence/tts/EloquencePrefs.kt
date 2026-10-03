/*
 * SPDX-License-Identifier: MIT
 *
 * EloquencePrefs -- SharedPreferences backing for the Eloquence settings
 * activity and the TTS service. Ranges mirror the engine:
 *   rate: percent 50..200 (100 = normal, combined with the system TTS rate)
 *   pitch: percent 50..200 (100 = normal, scales the voice preset's baseline)
 *   volume: 0..100 (engine eciVolume scale)
 *   higherRate: when true, the 11025 Hz engine output is upsampled to
 *     48000 Hz with libsoxr (very-high-quality); otherwise 11025 Hz native.
 *   voice: 0..7 Apple preset slot (Reed, Shelley, Sandy, Rocko, Flo,
 *     Grandma, Grandpa, Eddy)
 *   language: index into LANGUAGES (default voice when the system asks for
 *     a language the engine does not ship, e.g. Filipino)
 */
package com.eloquence.tts

import android.content.Context

data class EloqSettings(
    val rate: Int,
    val pitch: Int,
    val volume: Int,
    val higherRate: Boolean,
    val voice: Int,
    val language: Int,
)

object EloquencePrefs {
    const val FILE = "eloquence_prefs"
    const val KEY_RATE = "rate"
    const val KEY_PITCH = "pitch"
    const val KEY_VOLUME = "volume"
    const val KEY_HIGHER_RATE = "higher_rate"
    const val KEY_VOICE = "voice"
    const val KEY_LANGUAGE = "language"

    /** libsoxr upsampling target for the checkbox. */
    const val HIGH_RATE_HZ = 48000

    const val DEFAULT_RATE = 100
    const val DEFAULT_PITCH = 100
    const val DEFAULT_VOLUME = 90
    const val DEFAULT_HIGHER_RATE = true
    const val DEFAULT_VOICE = 0
    const val DEFAULT_LANGUAGE = 0

    /** Display names, same order as the service VOICES table. */
    val LANGUAGES = arrayOf(
        "English (US)", "English (UK)",
        "Spanish (Spain)", "Spanish (Mexico)",
        "French (France)", "French (Canada)",
        "German", "Italian",
        "Portuguese (Brazil)", "Finnish",
        "Chinese (Simplified)", "Chinese (Traditional)",
        "Japanese", "Korean",
    )
    /** ECI dialect per LANGUAGES entry, same order as the service table. */
    val LANGUAGE_DIALECTS = intArrayOf(
        0x00010000, 0x00010001,
        0x00020000, 0x00020001,
        0x00030000, 0x00030001,
        0x00040000, 0x00050000,
        0x00070000, 0x00090000,
        0x00060000, 0x00060001,
        0x00080000, 0x000A0000,
    )
    /** Engine module per LANGUAGES entry (lib<module>.so), same order. */
    val LANGUAGE_MODULES = arrayOf(
        "enu", "eng",
        "esp", "esm",
        "fra", "frc",
        "deu", "ita",
        "ptb", "fin",
        "chs", "cht",
        "jpn", "kor",
    )
    /** Romanizer per LANGUAGES entry (null = none), same order. */
    val LANGUAGE_ROMANIZERS: Array<String?> = arrayOf(
        null, null,
        null, null,
        null, null,
        null, null,
        null, null,
        "chsrom", "chtrom",
        "jpnrom", "korrom",
    )

    /** Engine-native rates only (ECI eciSampleRate: 0=8k, 1=11025, 2=test). */
    val ENGINE_RATES = intArrayOf(8000, 11025, 16000)
    val VOICE_NAMES = arrayOf(
        "Reed", "Shelley", "Sandy", "Rocko", "Flo", "Grandma", "Grandpa", "Eddy"
    )
    /** Per-preset pitch baseline (see sd_eloquence/src/eci/voices.c). */
    val VOICE_PITCH_BASE = intArrayOf(65, 81, 93, 56, 89, 68, 61, 69)

    fun load(ctx: Context): EloqSettings {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return EloqSettings(
            rate = p.getInt(KEY_RATE, DEFAULT_RATE).coerceIn(50, 200),
            pitch = p.getInt(KEY_PITCH, DEFAULT_PITCH).coerceIn(50, 200),
            volume = p.getInt(KEY_VOLUME, DEFAULT_VOLUME).coerceIn(0, 100),
            higherRate = p.getBoolean(KEY_HIGHER_RATE, DEFAULT_HIGHER_RATE),
            voice = p.getInt(KEY_VOICE, DEFAULT_VOICE).coerceIn(0, 7),
            language = p.getInt(KEY_LANGUAGE, DEFAULT_LANGUAGE)
                .coerceIn(0, LANGUAGES.size - 1),
        )
    }

    fun save(ctx: Context, s: EloqSettings) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_RATE, s.rate.coerceIn(50, 200))
            .putInt(KEY_PITCH, s.pitch.coerceIn(50, 200))
            .putInt(KEY_VOLUME, s.volume.coerceIn(0, 100))
            .putBoolean(KEY_HIGHER_RATE, s.higherRate)
            .putInt(KEY_VOICE, s.voice.coerceIn(0, 7))
            .putInt(KEY_LANGUAGE, s.language.coerceIn(0, LANGUAGES.size - 1))
            .apply()
    }
}
