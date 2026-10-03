/*
 * SPDX-License-Identifier: MIT
 *
 * EloquenceSettingsActivity -- the visible face of the app. It is both the
 * launcher entry ("Eloquence" in the app drawer) and the TTS engine's
 * settingsActivity (the gear icon in system Text-to-speech settings).
 *
 * Edits the same SharedPreferences the service reads: speech rate, pitch,
 * volume, output sample rate, and Apple voice preset, plus a test button
 * that speaks through this engine and a shortcut to system TTS settings.
 */
package com.eloquence.tts

import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

class EloquenceSettingsActivity : Activity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private lateinit var rateValue: TextView
    private lateinit var pitchValue: TextView
    private lateinit var volumeValue: TextView
    private lateinit var rateBar: SeekBar
    private lateinit var pitchBar: SeekBar
    private lateinit var volumeBar: SeekBar
    private lateinit var rateSpinner: Spinner
    private lateinit var voiceSpinner: Spinner
    private lateinit var languageSpinner: Spinner
    private lateinit var diagText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)

        val prefs = EloquencePrefs.load(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        // Installed version, so a test report can name the exact build.
        root.addView(TextView(this).apply {
            textSize = 12f
            text = try {
                val pi = packageManager.getPackageInfo(packageName, 0)
                "Version ${pi.versionName} (${pi.versionCode})"
            } catch (e: Exception) {
                ""
            }
        })

        root.addView(header("Speech rate"))
        rateValue = TextView(this)
        root.addView(rateValue)
        rateBar = SeekBar(this).apply { max = 150 } // 50..200 %
        root.addView(rateBar)

        root.addView(header("Pitch"))
        pitchValue = TextView(this)
        root.addView(pitchValue)
        pitchBar = SeekBar(this).apply { max = 150 } // 50..200 %
        root.addView(pitchBar)

        root.addView(header("Volume"))
        volumeValue = TextView(this)
        root.addView(volumeValue)
        volumeBar = SeekBar(this).apply { max = 100 } // 0..100
        root.addView(volumeBar)

        root.addView(header("Sample rate"))
        root.addView(TextView(this).apply {
            text = getString(R.string.settings_rate_note)
            textSize = 12f
        })
        rateSpinner = Spinner(this)
        rateSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf(
                "11025 Hz (native)",
                "8000 Hz (native)",
                "16000 Hz (ECI test)",
                "${EloquencePrefs.HIGH_RATE_HZ} Hz (libsoxr high quality)")).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        root.addView(rateSpinner)

        root.addView(header("Voice"))
        voiceSpinner = Spinner(this)
        voiceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            EloquencePrefs.VOICE_NAMES.toList()).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        root.addView(voiceSpinner)

        root.addView(header(getString(R.string.settings_language)))
        root.addView(TextView(this).apply {
            text = getString(R.string.settings_language_note)
            textSize = 12f
        })
        languageSpinner = Spinner(this)
        languageSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            EloquencePrefs.LANGUAGES.toList()).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        root.addView(languageSpinner)

        root.addView(header(getString(R.string.settings_diag)))
        diagText = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(diagText)
        val checkButton = Button(this).apply { text = getString(R.string.settings_check) }
        root.addView(checkButton)
        val directButton = Button(this).apply { text = getString(R.string.settings_play_direct) }
        root.addView(directButton)

        val testButton = Button(this).apply { text = getString(R.string.settings_test) }
        root.addView(spacer())
        root.addView(testButton)
        val sysButton = Button(this).apply { text = getString(R.string.settings_system) }
        root.addView(sysButton)

        // --- restore saved values (listeners attached afterwards) ---
        rateBar.progress = prefs.rate - 50
        pitchBar.progress = prefs.pitch - 50
        volumeBar.progress = prefs.volume
        rateSpinner.setSelection(prefs.sampleMode)
        voiceSpinner.setSelection(prefs.voice)
        languageSpinner.setSelection(prefs.language)
        diagText.text = getString(R.string.settings_diag_hint)
        refreshLabels()

        rateBar.setOnSeekBarChangeListener(saver {
            persist(rate = rateBar.progress + 50); refreshLabels()
        })
        pitchBar.setOnSeekBarChangeListener(saver {
            persist(pitch = pitchBar.progress + 50); refreshLabels()
        })
        volumeBar.setOnSeekBarChangeListener(saver {
            persist(volume = volumeBar.progress); refreshLabels()
        })
        rateSpinner.onItemSelectedListener = selectedSaver {
            persist(sampleMode = rateSpinner.selectedItemPosition)
        }
        voiceSpinner.onItemSelectedListener = selectedSaver {
            persist(voice = voiceSpinner.selectedItemPosition)
        }
        languageSpinner.onItemSelectedListener = selectedSaver {
            persist(language = languageSpinner.selectedItemPosition)
        }

        checkButton.setOnClickListener { checkEngine() }
        directButton.setOnClickListener { playDirect() }

        testButton.setOnClickListener { speakSample() }
        sysButton.setOnClickListener {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.settings_system_fail),
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            try {
                tts?.language = Locale.US
            } catch (_: Exception) {
            }
        }
        runOnUiThread {
            if (!ttsReady) {
                Toast.makeText(this, getString(R.string.settings_engine_fail),
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun speakSample() {
        try {
            if (tts == null) {
                ttsReady = false
                tts = TextToSpeech(this, this, packageName)
            }
            // Give onInit a moment on first use.
            var waited = 0
            while (!ttsReady && waited < 20) {
                Thread.sleep(100)
                waited++
            }
            if (!ttsReady) {
                Toast.makeText(this, getString(R.string.settings_engine_fail),
                    Toast.LENGTH_LONG).show()
                return
            }
            tts?.speak(getString(R.string.settings_sample), TextToSpeech.QUEUE_FLUSH, null, "eloq-test")
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: getString(R.string.settings_engine_fail),
                Toast.LENGTH_LONG).show()
        }
    }

    private fun persist(
        rate: Int? = null, pitch: Int? = null, volume: Int? = null,
        sampleMode: Int? = null, voice: Int? = null, language: Int? = null,
    ) {
        val cur = EloquencePrefs.load(this)
        EloquencePrefs.save(
            this,
            cur.copy(
                rate = rate ?: cur.rate,
                pitch = pitch ?: cur.pitch,
                volume = volume ?: cur.volume,
                sampleMode = sampleMode ?: cur.sampleMode,
                voice = voice ?: cur.voice,
                language = language ?: cur.language,
            )
        )
    }

    /**
     * Run diagnostics in the :probe process and show the result file.
     * A native crash kills only the probe process: after 30s without a
     * result file, report it as a native crash instead of dying with it.
     */
    private fun checkEngine() {
        diagText.text = getString(R.string.settings_diag_running)
        val resultFile = File(filesDir, ProbeService.RESULT_FILE)
        try {
            if (resultFile.exists()) resultFile.delete()
        } catch (_: Exception) {
        }
        val prefs = EloquencePrefs.load(this)
        val langIdx = prefs.language.coerceIn(0, EloquencePrefs.LANGUAGES.size - 1)
        try {
            startService(Intent(this, ProbeService::class.java)
                .putExtra(ProbeService.EXTRA_LIB_DIR,
                    applicationInfo.nativeLibraryDir)
                .putExtra(ProbeService.EXTRA_DIALECT,
                    EloquencePrefs.LANGUAGE_DIALECTS[langIdx])
                .putExtra(ProbeService.EXTRA_MODULE,
                    EloquencePrefs.LANGUAGE_MODULES[langIdx])
                .putExtra(ProbeService.EXTRA_ROMANIZER,
                    EloquencePrefs.LANGUAGE_ROMANIZERS[langIdx]))
        } catch (e: Exception) {
            diagText.text = "Could not start probe service: ${e.message}"
            return
        }
        Thread {
            var waited = 0
            var text: String? = null
            while (waited < 30000) {
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
                waited += 500
                try {
                    if (resultFile.exists()) {
                        text = resultFile.readText()
                        break
                    }
                } catch (_: Exception) {
                }
            }
            val out = text
                ?: "Probe did not finish in 30s — the native engine CRASHED " +
                    "during probing (process died). The UI survived because " +
                    "the probe runs isolated. This usually means dlopen or " +
                    "eciNewEx crashes on this device/OS version."
            runOnUiThread { diagText.text = out }
        }.start()
    }

    /**
     * Synthesize straight from the engine and play it with AudioTrack,
     * bypassing the system TTS framework. If you hear this but system TTS
     * stays silent, the engine is fine and the problem is framework plumbing.
     */
    private fun playDirect() {
        diagText.text = getString(R.string.settings_diag_running)
        Thread {
            var msg: String
            var h = 0L
            try {
                val prefs = EloquencePrefs.load(this)
                val dialect = EloquencePrefs.LANGUAGE_DIALECTS[prefs.language]
                h = EloquenceNative.nativeInit(
                    filesDir.absolutePath, applicationInfo.nativeLibraryDir, dialect)
                if (h == 0L) {
                    msg = "engine init FAILED (handle=0)"
                } else {
                    EloquenceNative.nativeSetVoicePreset(h, prefs.voice)
                    val pitchIn = (prefs.pitch / 2).coerceIn(0, 100)
                    val pitch = if (pitchIn <= 50) pitchIn * 69 / 50
                        else (pitchIn - 50) * 31 / 50 + 69
                    EloquenceNative.nativeSetProsody(
                        h,
                        (prefs.rate * 50 / 100).coerceIn(0, 250),
                        pitch,
                        prefs.volume,
                    )
                    val bytes = try {
                        getString(R.string.settings_sample)
                            .toByteArray(charset("windows-1252"))
                    } catch (e: Exception) {
                        getString(R.string.settings_sample)
                            .toByteArray(Charsets.US_ASCII)
                    }
                    // Engine rate via the ECI command; 48k choice upsamples
                    // the native render with libsoxr (very-high-quality).
                    val eciParam = when (prefs.sampleMode) {
                        EloquencePrefs.MODE_8000_NATIVE -> 0
                        EloquencePrefs.MODE_16000_TEST -> 2
                        else -> 1
                    }
                    var engineHz = if (eciParam == 0) 8000 else 11025
                    try {
                        if (EloquenceNative.nativeSetSampleRate(h, eciParam) < 0) {
                            EloquenceNative.nativeSetSampleRate(h, 1)
                            engineHz = 11025
                        } else if (eciParam == 2) {
                            engineHz = 16000
                        }
                    } catch (e: Exception) {
                        engineHz = 11025
                    }
                    val pcm = EloquenceNative.nativeSynthesize(h, bytes)
                    if (pcm == null) {
                        msg = "synth FAILED (null)"
                    } else if (prefs.sampleMode == EloquencePrefs.MODE_48000_SOXSR) {
                        val up = try {
                            EloquenceNative.nativeResample(
                                pcm, engineHz, EloquencePrefs.HIGH_RATE_HZ)
                        } catch (e: Exception) {
                            null
                        }
                        if (up == null) {
                            msg = "resample FAILED, playing native…"
                            runOnUiThread { diagText.text = msg }
                            playPcm(pcm, engineHz)
                            msg = "played ${pcm.size} samples @ $engineHz Hz"
                        } else {
                            msg = "playing ${up.size} samples @ ${EloquencePrefs.HIGH_RATE_HZ} Hz…"
                            runOnUiThread { diagText.text = msg }
                            playPcm(up, EloquencePrefs.HIGH_RATE_HZ)
                            msg = "played ${up.size} samples @ ${EloquencePrefs.HIGH_RATE_HZ} Hz"
                        }
                    } else {
                        msg = "playing ${pcm.size} samples @ $engineHz Hz…"
                        runOnUiThread { diagText.text = msg }
                        playPcm(pcm, engineHz)
                        msg = "played ${pcm.size} samples @ $engineHz Hz"
                    }
                }
            } catch (e: Throwable) {
                msg = "EXCEPTION ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                if (h != 0L) {
                    try {
                        EloquenceNative.nativeShutdown(h)
                    } catch (_: Exception) {
                    }
                }
            }
            runOnUiThread { diagText.text = msg }
        }.start()
    }

    // Modern Builder API: the deprecated constructor this replaced is
    // unreliable for static playback on current Android (it could leave
    // the Test button completely silent with no error). Every failure
    // below throws, and the caller reports it — nothing goes quiet.
    private fun playPcm(pcm: ShortArray, rateHz: Int) {
        if (pcm.isEmpty()) throw IllegalArgumentException("empty pcm")
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            bytes[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
        }
        val minBuf = AudioTrack.getMinBufferSize(
            rateHz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IllegalStateException("bad rate $rateHz (minBuf=$minBuf)")
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
            .setBufferSizeInBytes(maxOf(minBuf, bytes.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("track state=${track.state}")
            }
            val written = track.write(bytes, 0, bytes.size)
            if (written < 0) throw IllegalStateException("write=$written")
            track.play()
            var guard = 0
            while (track.playState == AudioTrack.PLAYSTATE_PLAYING && guard++ < 600) {
                Thread.sleep(100)
                try {
                    if (track.playbackHeadPosition >= pcm.size) break
                } catch (_: Exception) {
                    break
                }
            }
        } finally {
            try {
                track.stop()
            } catch (_: Exception) {
            }
            track.release()
        }
    }

    private fun refreshLabels() {
        val cur = EloquencePrefs.load(this)
        rateValue.text = "${cur.rate} %"
        pitchValue.text = "${cur.pitch} %"
        volumeValue.text = "${cur.volume} / 100"
    }

    private fun header(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setPadding(0, 24, 0, 4)
    }

    private fun spacer(): TextView = TextView(this).apply { text = "" }

    private fun saver(onSave: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onSave()
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun selectedSaver(onSave: () -> Unit) =
        object : android.widget.AdapterView.OnItemSelectedListener {
            private var first = true
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long,
            ) {
                // Skip the initial selection event fired during setup.
                if (first) {
                    first = false
                    return
                }
                onSave()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
}
