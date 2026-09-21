package com.hyperisland.pro.ui

import android.app.Activity
import android.content.ContentValues
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hyperisland.pro.core.TraceLog
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The trace, on screen. Built in code rather than XML because it is a debug surface: one TextView in a
 * scroll view, monospace, wrapped so a long reason string stays readable without sideways scrolling.
 *
 * Auto-refresh is 700ms and pauses when the activity is not visible. Nothing here talks to the
 * accessibility service directly - the service writes into [TraceLog], this only reads it, so opening
 * the log can never change the behaviour being observed.
 */
class TraceLogActivity : Activity() {

    private val ticker = Handler(Looper.getMainLooper())
    private lateinit var body: TextView
    private lateinit var status: TextView
    private var followTail = true
    private var auto = true
    private var scroll: ScrollView? = null
    private var startedAtLines = 0

    private val tick = object : Runnable {
        override fun run() {
            render()
            ticker.postDelayed(this, 700L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(12f)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 9, 12))
            setPadding(pad, pad, pad, pad)
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header.addView(TextView(this).apply {
            text = "TRACE LOG"
            setTextColor(Color.rgb(0, 150, 255))
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        status = TextView(this).apply {
            setTextColor(Color.rgb(150, 165, 178))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        header.addView(status)
        root.addView(header)

        body = TextView(this).apply {
            setTextColor(Color.rgb(226, 232, 240))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.06f)
        }
        // Horizontal scroll off, wrapping on: a dropped-swipe reason is a sentence, and sideways
        // scrolling through 600 lines is how a log stops being read.
        scroll = ScrollView(this).apply {
            addView(body, LinearLayout.LayoutParams(-1, -2))
            isVerticalScrollBarEnabled = true
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8f), 0, 0) }
        row1.addView(Button(this).apply {
            text = "TAIL"; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(24, 26, 32))
            isAllCaps = false; setOnClickListener { followTail = true; render(); scrollBottom() }
        }, lp())
        row1.addView(Button(this).apply {
            text = "TOP"; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(24, 26, 32))
            isAllCaps = false; setOnClickListener { followTail = false; scroll?.fullScroll(View.FOCUS_UP) }
        }, lp())
        row1.addView(Button(this).apply {
            text = if (auto) "LIVE" else "PAUSED"; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(24, 26, 32))
            isAllCaps = false
            setOnClickListener {
                auto = !auto
                text = if (auto) "LIVE" else "PAUSED"
                if (auto) ticker.postDelayed(tick, 0L) else ticker.removeCallbacks(tick)
            }
        }, lp())
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4f), 0, 0) }
        row2.addView(Button(this).apply {
            text = "COPY"; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(24, 26, 32))
            isAllCaps = false
            setOnClickListener {
                val text = TraceLog.snapshot()
                if (text.isBlank()) { toast("Nothing logged yet"); return@setOnClickListener }
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("hip trace", text))
                toast("${text.lines().size} lines copied")
            }
        }, lp())
        row2.addView(Button(this).apply {
            text = "SHARE"; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(24, 26, 32))
            isAllCaps = false
            setOnClickListener {
                val text = TraceLog.snapshot()
                if (text.isBlank()) { toast("Nothing logged yet"); return@setOnClickListener }
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Hyper Island trace")
                    // A full 600-line log is bigger than some receivers expect; the tail is what matters.
                    putExtra(Intent.EXTRA_TEXT, TraceLog.tail(TraceLog.PERSISTED_LINES)) // same slice the file gets
                }, "Send trace log"))
            }
        }, lp())
        row2.addView(Button(this).apply {
            text = "CLEAR"; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(48, 20, 24))
            isAllCaps = false
            setOnClickListener {
                TraceLog.clear()
                TraceLog.line("TRACE", "cleared by hand from the log screen")
                render()
            }
        }, lp())
        root.addView(row2)

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4f), 0, 0) }
        row3.addView(Button(this).apply {
            text = "EXPORT TO DOWNLOADS"; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(16, 44, 30))
            isAllCaps = false
            setOnClickListener { export() }
        }, LinearLayout.LayoutParams(-1, dp(48f)))
        root.addView(row3)

        setContentView(root)
        startedAtLines = TraceLog.size()
    }

    override fun onResume() {
        super.onResume()
        if (auto) ticker.postDelayed(tick, 0L)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    /**
     * Writes the whole buffer to `Downloads/hip-log-<stamp>.txt`, with a fresh timestamp in the name every
     * time so two captures can never overwrite each other or be told apart.
     *
     * Why a file when COPY and SHARE exist: pasting 1500 lines into a chat truncates the head (the flood),
     * re-wraps every line, and drops the build stamp we need to know *which* APK a report describes. The
     * trace screen is also the one place that can write the log to itself, so the file is proof of what
     * was on the screen at that moment. No storage permission is needed: this is MediaStore's Downloads
     * collection on API 29+, and the app-private folder is the fallback if an OEM still refuses.
     */
    private fun export() {
        val text = TraceLog.snapshot()
        if (text.isBlank()) { toast("Nothing logged yet"); return }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "hip-log-$stamp.txt"
        val header = "# Hyper Island Pro trace - $name - ${TraceLog.size()} lines of ${TraceLog.MAX_LINES}\n" +
            "# model ${Build.MODEL}, android ${Build.VERSION.SDK_INT}, built from the device's own clock\n\n"
        try {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore refused the insert")
            resolver.openOutputStream(uri)?.use { it.write((header + text).toByteArray()) }
                ?: throw IOException("no output stream for $uri")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            TraceLog.line("TRACE", "exported ${TraceLog.size()} lines to Downloads/$name")
            toast("Downloads/$name")
        } catch (first: Throwable) {
            try {
                val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir, "trace")
                dir.mkdirs()
                val file = File(dir, name)
                file.writeText(header + text)
                TraceLog.line("TRACE", "exported to app folder ${file.absolutePath} (MediaStore said: $first)")
                toast("saved ${file.absolutePath}")
            } catch (second: Throwable) {
                TraceLog.line("TRACE", "export FAILED mediaStore=$first appFolder=$second")
                toast("export failed: $second")
            }
        }
    }

    private fun render() {
        val n = TraceLog.size()
        val text = TraceLog.snapshot()
        if (text != body.text.toString()) body.text = text
        status.text = "$n lines kept · $n - ${maxOf(0, n - startedAtLines)} added since this screen opened" +
            if (n >= TraceLog.MAX_LINES) " · older lines rolled off" else ""
        if (followTail) scrollBottom()
    }

    private fun scrollBottom() {
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun lp() = LinearLayout.LayoutParams(0, dp(44f), 1f).apply { marginEnd = dp(6f) }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
