package com.cachecleaner.app

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var btnMain: Button
    private lateinit var btnOpen: Button

    private val reqCode = 100

    private val permListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }
    private val binderListener =
        Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val deadListener =
        Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }

    // 0 = Shizuku mati, 1 = versi lama, 2 = izin belum diberikan, 3 = siap
    private fun state(): Int = when {
        !Shizuku.pingBinder() -> 0
        Shizuku.isPreV11() -> 1
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> 3
        else -> 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        status = TextView(this).apply { textSize = 16f }
        btnMain = Button(this).apply { setOnClickListener { onMainClick() } }
        btnOpen = Button(this).apply {
            text = "Buka Shizuku"
            setOnClickListener { openShizuku() }
        }
        log = TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, 0)
        }
        root.addView(status)
        root.addView(btnMain)
        root.addView(btnOpen)
        root.addView(log)
        setContentView(ScrollView(this).apply { addView(root) })

        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permListener)

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun refresh() {
        when (state()) {
            0 -> {
                status.text = "Shizuku belum berjalan. Buka Shizuku lalu aktifkan layanannya."
                btnMain.text = "Bersihkan Cache"
                btnMain.isEnabled = false
            }
            1 -> {
                status.text = "Versi Shizuku terlalu lama. Perbarui Shizuku."
                btnMain.text = "Bersihkan Cache"
                btnMain.isEnabled = false
            }
            2 -> {
                status.text = "Shizuku aktif. Izin untuk aplikasi ini belum diberikan."
                btnMain.text = "Beri izin Shizuku"
                btnMain.isEnabled = true
            }
            else -> {
                status.text = "Shizuku aktif dan izin sudah diberikan."
                btnMain.text = "Bersihkan Cache"
                btnMain.isEnabled = true
            }
        }
    }

    private fun onMainClick() {
        when (state()) {
            2 -> {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    log.text = "Izin ditolak permanen. Berikan izin lewat aplikasi Shizuku " +
                        "(menu aplikasi yang diotorisasi)."
                } else {
                    Shizuku.requestPermission(reqCode)
                }
            }
            3 -> clean()
        }
    }

    private fun clean() {
        btnMain.isEnabled = false
        log.text = "Membersihkan cache..."
        Thread {
            val before = freeBytes()
            val result = try {
                runShell("pm trim-caches 999999999999")
            } catch (e: Throwable) {
                -1 to (e.message ?: e.toString())
            }
            val after = freeBytes()
            runOnUiThread {
                log.text = "Kode keluar: ${result.first}\n" +
                    (if (result.second.isNotEmpty()) "${result.second}\n" else "") +
                    "Ruang kosong sebelum: ${fmt(before)}\n" +
                    "Ruang kosong sesudah: ${fmt(after)}\n" +
                    "Selisih: ${fmt(after - before)}"
                refresh()
            }
        }.start()
    }

    // Shizuku.newProcess bersifat private sejak 13.1.0, sehingga dipanggil lewat reflection.
    private fun runShell(cmd: String): Pair<Int, String> {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        val code = p.waitFor()
        return code to (out + err).trim()
    }

    private fun freeBytes(): Long =
        StatFs(Environment.getDataDirectory().path).availableBytes

    private fun fmt(b: Long): String = "%.1f MB".format(b / 1048576.0)

    private fun openShizuku() {
        val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (i != null) startActivity(i) else log.text = "Aplikasi Shizuku belum terpasang."
    }
}
