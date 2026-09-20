package com.hyperisland.pro.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
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
                    putExtra(Intent.EXTRA_TEXT, TraceLog.tail(300))
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
