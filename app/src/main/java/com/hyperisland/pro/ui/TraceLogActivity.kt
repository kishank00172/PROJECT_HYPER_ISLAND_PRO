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
import android.view.MotionEvent
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

    /**
     * True between ACTION_DOWN and ACTION_UP. Writing a scroll position while a gesture is running is how a log
     * screen ends up fighting a finger, and that is exactly what he reported ("swipe karta hu to force swipe ho
     * jata hai"), so no code path is allowed to move this view while it is true.
     */
    private var userTouching = false

    /** The viewer's own filter: on by default, so the story is readable; the export always holds everything. */
    private var quiet = true

    /** How many lines were on screen when the tail last moved. Its difference is what the status line reports. */
    private var shownTotal = -1

    /** The last tail handed to the TextView; identical text must not touch the view again. */
    private var renderedTail = ""

    /**
     * How many lines the on-screen tail shows, and it is a viewport choice, not a data cap: the buffer and the
     * export carry everything, TOP reads the front. 140 because a TextView rebuild costs about a millisecond a
     * line and this screen sits on top of the thing it is measuring.
     */
    private companion object { const val VISIBLE_LINES = 140 }
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
            // Following is a promise about the bottom, and the bottom is where new lines appear. Anywhere else
            // the user is reading, and reading means the text does not move under him: b1396's log had 239
            // sampler lines per session arriving while he was trying to find four events in it, and every swap
            // re-laid the child out, let the ScrollView clamp his offset, and then ran a corrective scroll a
            // frame later - up, down, up again, which is what he means by "kabhi upar ja rha hai kabhi niche".
            setOnScrollChangeListener { _, _, y, _, _ ->
                // The listener hands out a View, which has no children; the ScrollView itself is the apply
                // receiver here, so read the one child off that.
                val child = getChildAt(0)
                if (child != null) followTail = y + height >= child.bottom - 8
            }
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> userTouching = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        userTouching = false
                        // Decided at the end of the drag, once: releasing at the bottom picks the tail back up,
                        // releasing anywhere else leaves the view exactly where he put it.
                        scroll?.let { s ->
                            val c = s.getChildAt(0)
                            if (c != null) followTail = s.scrollY + s.height >= c.bottom - 8
                        }
                    }
                }
                false // observe, never consume - the ScrollView has to see the gesture or it stops scrolling
            }
            addView(body, LinearLayout.LayoutParams(-1, -2))
            isVerticalScrollBarEnabled = true
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8f), 0, 0) }
        row1.addView(Button(this).apply {
            text = "TAIL"; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(24, 26, 32))
            isAllCaps = false; setOnClickListener { followTail = true; render(force = true); scrollBottom() }
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
        row1.addView(Button(this).apply {
            text = if (quiet) "QUIET" else "NOISY"; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(24, 26, 32))
            isAllCaps = false
            setOnClickListener {
                quiet = !quiet
                text = if (quiet) "QUIET" else "NOISY"
                render(force = true)
                Toast.makeText(
                    this@TraceLogActivity,
                    if (quiet) "sampler lines hidden - the export still has them all" else "showing every line",
                    Toast.LENGTH_SHORT
                ).show()
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
        val lost = TraceLog.droppedLines
        val roll = if (lost > 0L) " ($lost rolled off the front: this file starts at line ${lost + 1})" else " (nothing lost)"
        val header = "# Hyper Island Pro trace - $name - ${TraceLog.size()} lines of ${TraceLog.MAX_LINES}$roll\n" +
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

    /**
     * The screen used to hand all 1500 lines to one TextView every 700 ms. That is a StaticLayout build of a
     * hundred-odd kilobytes on the main thread, and it was the single biggest block in the session it produced:
     * 91 of 277 stall samples in the b1373 export were this function, while the tester was judging whether the
     * island is smooth. A tool that eats the frames it is measuring is not a tool, so the screen keeps a tail
     * and the export keeps everything.
     */
    private fun render(force: Boolean = false) {
        val total = TraceLog.size()
        if (!followTail && !force) {
            // Reading, not following: the text stays exactly where it is and nothing else touches this view.
            // Lines still arrive in the buffer (and the export takes all of them); the status says how many are
            // waiting, because "is it still logging?" is the first question a frozen screen raises.
            if (total != shownTotal) {
                status.text = "frozen for reading · ${total - maxOf(0, shownTotal)} new line(s) since · TAIL follows again"
            }
            return
        }
        // Filtering happens over a wider window than the one shown, so hiding 4 sampler lines in 5 does not
        // leave a half-empty screen; collapseRuns then turns a run of identical lines into one line with a count.
        val window = if (quiet) VISIBLE_LINES * 5 else VISIBLE_LINES
        val picked = TraceLog.tailLines(window)
        val kept = if (quiet) picked.filterNot { TraceLog.isChatter(it) } else picked
        val collapsed = TraceLog.collapseRuns(kept).takeLast(VISIBLE_LINES)
        val text = collapsed.joinToString("\n")
        // Nothing new, so do not touch the TextView. Re-assigning identical text rebuilt a StaticLayout every
        // 700 ms and his sampler caught that 204 times in one session: the log screen was the single biggest
        // main-thread cost in a session whose whole point was to find main-thread costs.
        if (text != renderedTail) {
            renderedTail = text
            body.text = text
            // The scroll runs after the new text has been measured (post), which is why the position no longer
            // overshoots and gets clamped back - and never during a gesture, which is why it no longer fights
            // a swipe. Only following is allowed to move this view at all.
            if (!userTouching && followTail) scroll?.post { if (followTail) scroll?.fullScroll(View.FOCUS_DOWN) }
        }
        shownTotal = total
        val lost = TraceLog.droppedLines
        val hidden = picked.size - kept.size
        status.text = "live tail · $total line(s) kept, ${collapsed.size} shown" +
            (if (hidden > 0) " · $hidden sampler line(s) hidden (QUIET off)" else "") +
            (if (lost > 0L) " · $lost rolled off the front (buffer ${TraceLog.MAX_LINES})" else "") +
            // He asked what this screen even is: it is not a recorder that starts on a change, it is a tail of a
            // buffer the service writes to all the time, so the line says so, in the units he reads in.
            "\nwritten as things happen (every tag except STALL/FRAME is an event) · EXPORT writes all $total" +
            (if (total > startedAtLines) " · ${total - startedAtLines} since you opened this" else "")
    }

    private fun scrollBottom() {
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun lp() = LinearLayout.LayoutParams(0, dp(44f), 1f).apply { marginEnd = dp(6f) }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
