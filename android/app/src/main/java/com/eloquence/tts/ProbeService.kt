/*
 * SPDX-License-Identifier: MIT
 *
 * ProbeService -- runs the native engine diagnostics in a SEPARATE process
 * (android:process=":probe", see AndroidManifest.xml).
 *
 * Native engine bugs show up as process death (SIGSEGV/SIGABRT), which would
 * otherwise take the whole UI down with "Check engine". Here a crash only
 * kills this throwaway process: the activity watches for the result file
 * and reports a native crash if it never appears.
 *
 * Each step appends to filesDir/probe_result.txt as it completes, so even a
 * mid-probe death leaves a trail showing exactly which step died.
 */
package com.eloquence.tts

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.File

class ProbeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val libDir = intent?.getStringExtra(EXTRA_LIB_DIR).orEmpty()
        val dialect = intent?.getIntExtra(EXTRA_DIALECT, 0) ?: 0
        val module = intent?.getStringExtra(EXTRA_MODULE)
        val romanizer = intent?.getStringExtra(EXTRA_ROMANIZER)
        Thread {
            val out = File(filesDir, RESULT_FILE)
            try {
                if (out.exists()) out.delete()
                out.appendText("device: SDK ${android.os.Build.VERSION.SDK_INT} " +
                    "${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}\n")
                out.appendText("libDir: $libDir\n")
                appendSoList(out, libDir)
                out.appendText("soxr self-test: ${safeSoxrTest()}\n")
                out.appendText(nativeProbeReport(
                    filesDir.absolutePath, libDir, dialect, module, romanizer))
                out.appendText("RESULT: probe process survived\n")
            } catch (e: Throwable) {
                try {
                    out.appendText(
                        "SERVICE EXCEPTION ${e.javaClass.simpleName}: ${e.message}\n")
                } catch (_: Exception) {
                }
            } finally {
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun appendSoList(out: File, libDir: String) {
        val sos = try {
            File(libDir).listFiles()
                ?.map { it.name }?.filter { it.endsWith(".so") }?.sorted()
                ?: emptyList()
        } catch (e: Exception) {
            out.appendText("libDir list FAILED: ${e.message}\n")
            return
        }
        out.appendText("so files: ${sos.size}\n")
        for (n in arrayOf(
            "libeloquence_jni.so", "libeci.so", "libenu.so",
            "libc++_shared.so", "libsoxr.so")) {
            out.appendText("  $n: ${if (sos.contains(n)) "ok" else "MISSING"}\n")
        }
        val ini = File(filesDir, "eci.ini")
        out.appendText(
            "eci.ini: ${if (ini.exists()) "${ini.length()} bytes" else "MISSING"}\n")
    }

    private fun safeSoxrTest(): String {
        return try {
            EloquenceNative.nativeSoxrSelfTest()
        } catch (e: Throwable) {
            "EXCEPTION ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun nativeProbeReport(
        dataDir: String, libDir: String, dialect: Int,
        module: String?, romanizer: String?,
    ): String {
        // Step-by-step with markers: if the process dies inside nativeProbe,
        // the file ends right before the missing marker.
        return try {
            "--- probe ---\n" + EloquenceNative.nativeProbe(
                dataDir, libDir, dialect, module, romanizer) +
                "--- probe returned ---\n"
        } catch (e: Throwable) {
            "probe EXCEPTION ${e.javaClass.simpleName}: ${e.message}\n"
        }
    }

    companion object {
        const val RESULT_FILE = "probe_result.txt"
        const val EXTRA_LIB_DIR = "libDir"
        const val EXTRA_DIALECT = "dialect"
        const val EXTRA_MODULE = "module"
        const val EXTRA_ROMANIZER = "romanizer"
    }
}
