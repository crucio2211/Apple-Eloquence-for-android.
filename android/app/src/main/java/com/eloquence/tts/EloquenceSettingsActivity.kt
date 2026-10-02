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
            EloquencePrefs.SAMPLE_RATES.map { "$it Hz" }).apply {
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

        val testButton = Button(this).apply { text = getString(R.string.settings_test) }
        root.addView(spacer())
        root.addView(testButton)
        val sysButton = Button(this).apply { text = getString(R.string.settings_system) }
        root.addView(sysButton)

        // --- restore saved values (listeners attached afterwards) ---
        rateBar.progress = prefs.rate - 50
        pitchBar.progress = prefs.pitch - 50
        volumeBar.progress = prefs.volume
        rateSpinner.setSelection(
            EloquencePrefs.SAMPLE_RATES.indexOf(prefs.sampleRate).coerceAtLeast(0))
        voiceSpinner.setSelection(prefs.voice)
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
            persist(sampleRate = EloquencePrefs.SAMPLE_RATES[rateSpinner.selectedItemPosition])
        }
        voiceSpinner.onItemSelectedListener = selectedSaver {
            persist(voice = voiceSpinner.selectedItemPosition)
        }

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
        sampleRate: Int? = null, voice: Int? = null,
    ) {
        val cur = EloquencePrefs.load(this)
        EloquencePrefs.save(
            this,
            cur.copy(
                rate = rate ?: cur.rate,
                pitch = pitch ?: cur.pitch,
                volume = volume ?: cur.volume,
                sampleRate = sampleRate ?: cur.sampleRate,
                voice = voice ?: cur.voice,
            )
        )
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
