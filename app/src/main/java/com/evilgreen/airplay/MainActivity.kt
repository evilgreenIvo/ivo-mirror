/*
 * IVO Mirror · receba a tela do iPhone no IVO LIVE
 * Copyright (C) 2026 evilgreenIvo — GPL-3.0-or-later (see LICENSE)
 */
package com.evilgreen.airplay

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * One screen with IVO LIVE's look (dark green glass, Barlow, Material Icons): what's happening in a
 * card, one big Ligar/Parar, the report, and how to use it.
 */
class MainActivity : Activity() {

    companion object {
        // IVO LIVE's green theme.
        val BG = Color.parseColor("#060A08")
        val CARD = Color.parseColor("#111915")
        val CARD_ON = Color.parseColor("#1C2822")
        val LINE = Color.parseColor("#1F2D26")
        val GREEN = Color.parseColor("#00E27A")
        val LIME = Color.parseColor("#5CFFB0")
        val DEEP = Color.parseColor("#00A85B")
        val DIM = Color.parseColor("#8CA397")
        val TITLE = Color.parseColor("#E6F2EA")
        val ON_ACCENT = Color.parseColor("#02140A")
        val AMBER = Color.parseColor("#FFC24D")
        val RED = Color.parseColor("#FF5F5A")
        val GLASS_TOP = Color.parseColor("#15211B")
        val GLASS_BOTTOM = Color.parseColor("#0A110D")

        const val I_WIFI = 0xe63e
        const val I_POWER = 0xe8ac
        const val I_PHONE = 0xe325
        const val I_ADD = 0xe147
        const val I_REPORT = 0xe873
        const val I_INFO = 0xe88e
        const val I_CAST = 0xe0e2
        const val I_UPDATE = 0xe923
        const val I_LOCK = 0xe897

        /** Opened by the quick settings tile when it couldn't start the receiver itself. */
        const val EXTRA_START = "start"
    }

    private lateinit var bold: Typeface
    private lateinit var regular: Typeface
    private lateinit var icons: Typeface

    private lateinit var dot: View
    private lateinit var phaseText: TextView
    private lateinit var hint: TextView
    private lateinit var picture: TextView
    private lateinit var ivoChip: TextView
    private lateinit var button: TextView
    private lateinit var qualityHint: TextView
    private val qualityPills = mutableListOf<Pair<TextView, Boolean>>()
    private lateinit var settings: Settings
    private lateinit var updateCard: View
    private lateinit var updateText: TextView
    private lateinit var pinCode: TextView
    private lateinit var pinNew: TextView
    private lateinit var nextTime: TextView
    private val switches = mutableListOf<Pair<View, () -> Boolean>>()
    private var pulse: ValueAnimator? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun glass(radius: Int) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(GLASS_TOP, GLASS_BOTTOM)).apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), Color.argb(0x45, Color.red(GREEN), Color.green(GREEN), Color.blue(GREEN)))
    }

    private fun text(size: Float, color: Int, face: Typeface = regular) = TextView(this).apply {
        textSize = size; setTextColor(color); typeface = face
    }

    private fun icon(code: Int, size: Float, color: Int) = text(size, color, icons).apply {
        text = String(Character.toChars(code)); gravity = Gravity.CENTER
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        bold = try { resources.getFont(R.font.barlow_semibold) } catch (_: Exception) { Typeface.DEFAULT_BOLD }
        regular = try { resources.getFont(R.font.barlow_medium) } catch (_: Exception) { Typeface.DEFAULT }
        icons = try { resources.getFont(R.font.icons_outlined) } catch (_: Exception) { Typeface.DEFAULT }
        settings = Settings(this)
        // Ligar ao abrir, or the tile asked for it.
        if (!ReceiverService.running && (settings.autoStart || intent?.getBooleanExtra(EXTRA_START, false) == true))
            ReceiverService.start(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(28))
        }

        // Name: IVO in white, MIRROR in the accent (like IVO LIVE's own title).
        col.addView(text(34f, TITLE, bold).apply {
            letterSpacing = 0.04f
            text = SpannableStringBuilder("IVO MIRROR").apply {
                setSpan(ForegroundColorSpan(GREEN), 4, 10, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        })
        col.addView(text(15f, DIM).apply { text = getString(R.string.tagline) })

        // A newer version on GitHub: opens its page.
        updateText = text(15f, ON_ACCENT, bold)
        updateCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(GREEN, 16)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            addView(icon(I_UPDATE, 22f, ON_ACCENT), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
            addView(updateText, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener {
                val url = settings.newerUrl.ifEmpty { "https://github.com/evilgreenIvo/ivo-mirror/releases/latest" }
                try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) {}
            }
        }
        col.addView(updateCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        // The status card.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(22)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dot = View(this)
        top.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) })
        phaseText = text(22f, TITLE, bold)
        top.addView(phaseText, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(top)
        hint = text(15f, DIM).apply { setLineSpacing(0f, 1.15f); setPadding(0, dp(8), 0, 0) }
        card.addView(hint)
        picture = text(14f, LIME).apply { setPadding(0, dp(8), 0, 0) }
        card.addView(picture)
        ivoChip = text(13f, TITLE, bold).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(CARD_ON, 99, LINE)
        }
        card.addView(LinearLayout(this).apply {
            setPadding(0, dp(14), 0, 0)
            addView(ivoChip)
        })
        col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })

        // Ligar / Parar.
        button = text(20f, ON_ACCENT, bold).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
            setPadding(0, dp(18), 0, dp(18))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (ReceiverService.running) ReceiverService.stop(this@MainActivity) else ReceiverService.start(this@MainActivity)
                it.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }.start()
                it.postDelayed({ paint() }, 400)
            }
        }
        col.addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        // The report.
        col.addView(row(I_REPORT, getString(R.string.send_log)).apply { setOnClickListener { sendLog() } },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // Picture quality: the screen size the iPhone is told about.
        col.addView(text(13f, DIM, bold).apply {
            text = getString(R.string.settings_title); letterSpacing = 0.12f
            setPadding(dp(4), dp(28), 0, dp(10))
        })
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(CARD, 16, LINE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf(false to R.string.quality_normal, true to R.string.quality_high).forEach { (high, label) ->
            val pill = text(16f, TITLE, bold).apply {
                text = getString(label)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    settings.high = high
                    paint()
                }
            }
            qualityPills += pill to high
            seg.addView(pill, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 18, LINE)
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        box.addView(text(16f, TITLE, bold).apply { text = getString(R.string.quality_title_short) })
        box.addView(seg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        seg.background = rounded(BG, 14, LINE)
        qualityHint = text(13f, DIM).apply { setLineSpacing(0f, 1.12f); setPadding(0, dp(8), 0, dp(4)) }
        box.addView(qualityHint)
        box.addView(divider())
        box.addView(switchRow(getString(R.string.frame_title), getString(R.string.frame_hint), { settings.fixedFrame }) { settings.fixedFrame = it })
        box.addView(divider())
        box.addView(switchRow(getString(R.string.pin_title), getString(R.string.pin_hint), { settings.pinOn }) { settings.pinOn = it })
        pinCode = text(30f, GREEN, bold).apply { letterSpacing = 0.3f; setPadding(0, dp(2), 0, 0) }
        pinNew = text(14f, GREEN, bold).apply {
            text = getString(R.string.pin_new)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(CARD_ON, 99, LINE)
            setOnClickListener { settings.newPin(); paint() }
        }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
            addView(icon(I_LOCK, 22f, GREEN), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            addView(pinCode, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pinNew)
            tag = "pin"
        })
        box.addView(divider())
        box.addView(switchRow(getString(R.string.auto_title), getString(R.string.auto_hint), { settings.autoStart }) { settings.autoStart = it })
        nextTime = text(13f, AMBER).apply { text = getString(R.string.quality_next_time); setPadding(0, dp(6), 0, dp(4)) }
        box.addView(nextTime)
        col.addView(box)

        // How to use: a real sequence, so numbered.
        col.addView(text(13f, DIM, bold).apply {
            text = getString(R.string.how_title); letterSpacing = 0.12f
            setPadding(dp(4), dp(28), 0, dp(10))
        })
        val steps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 18, LINE)
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        listOf(I_WIFI to R.string.how_1, I_POWER to R.string.how_2, I_CAST to R.string.how_3, I_ADD to R.string.how_4)
            .forEachIndexed { i, (ic, s) -> steps.addView(step(i + 1, ic, getString(s), last = i == 3)) }
        col.addView(steps)

        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(14), dp(4), 0)
            addView(icon(I_INFO, 16f, DIM), LinearLayout.LayoutParams(dp(22), -2).apply { marginEnd = dp(6) })
            addView(text(13f, DIM).apply { text = getString(R.string.limits) }, LinearLayout.LayoutParams(0, -2, 1f))
        })

        col.addView(text(12f, DIM).apply {
            text = getString(R.string.about, BuildConfig.VERSION_NAME)
            gravity = Gravity.CENTER
            alpha = 0.8f
            setPadding(0, dp(26), 0, dp(4))
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(R.string.licenses_title).setMessage(R.string.licenses_body)
                    .setPositiveButton(R.string.ok, null).show()
            }
        })

        setContentView(FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#0B1A12"), BG, BG))
            addView(ScrollView(this@MainActivity).apply { isVerticalScrollBarEnabled = false; addView(col) })
        })
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(4); bottomMargin = dp(4) }
    }

    /** A setting that's on or off: name and explanation on the left, a switch in IVO LIVE's colours on the right. */
    private fun switchRow(title: String, note: String, get: () -> Boolean, set: (Boolean) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        isClickable = true
        isFocusable = true
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(16f, TITLE, bold).apply { text = title })
            addView(text(13f, DIM).apply { text = note; setLineSpacing(0f, 1.1f); setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        val knob = View(this@MainActivity)
        val track = FrameLayout(this@MainActivity).apply { addView(knob, FrameLayout.LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.CENTER_VERTICAL }) }
        addView(track, LinearLayout.LayoutParams(dp(46), dp(28)))
        switches += track to get
        setOnClickListener { set(!get()); paint() }
    }

    private fun paintSwitch(track: View, on: Boolean) {
        val knob = (track as FrameLayout).getChildAt(0)
        track.background = rounded(if (on) GREEN else Color.parseColor("#2C4237"), 99)
        knob.background = rounded(if (on) ON_ACCENT else Color.parseColor("#C9D6CE"), 99)
        (knob.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.CENTER_VERTICAL or (if (on) Gravity.END else Gravity.START)
            marginStart = dp(4); marginEnd = dp(4)
        }
        knob.requestLayout()
    }

    /** A secondary button: icon and words on a card. */
    private fun row(code: Int, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = rounded(CARD, 18, LINE)
        setPadding(0, dp(15), 0, dp(15))
        isClickable = true
        isFocusable = true
        addView(icon(code, 20f, GREEN), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        addView(text(17f, GREEN, bold).apply { text = label })
    }

    private fun step(n: Int, code: Int, words: String, last: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        addView(text(14f, ON_ACCENT, bold).apply {
            text = n.toString(); gravity = Gravity.CENTER
            background = rounded(GREEN, 99)
        }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
        addView(text(15f, TITLE).apply { text = words; setLineSpacing(0f, 1.12f) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(icon(code, 22f, DIM), LinearLayout.LayoutParams(dp(30), -2).apply { marginStart = dp(8) })
        if (!last) background = object : android.graphics.drawable.ColorDrawable(Color.TRANSPARENT) {
            private val p = android.graphics.Paint().apply { color = LINE }
            override fun draw(c: android.graphics.Canvas) { c.drawRect(0f, bounds.bottom - dp(1).toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), p) }
        }
    }

    override fun onResume() {
        super.onResume()
        ReceiverService.onChange = { runOnUiThread { paint() } }
        paint()
        UpdateCheck.maybeCheck(this) { if (!isFinishing) paint() }
    }

    override fun onPause() {
        ReceiverService.onChange = null
        pulse?.cancel()
        pulse = null
        super.onPause()
    }

    private fun sendLog() {
        val header = "IVO Mirror ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n" +
            "Estado: ${if (ReceiverService.running) ReceiverService.status else "desligado"}\n${ReceiverService.picture}\n" +
            "Ajustes: qualidade ${if (settings.high) "Alta (2560x1440)" else "Normal (1920x1080)"} · moldura ${if (settings.fixedFrame) "16:9 fixa" else "do iPhone"} · PIN ${if (settings.pinOn) "ligado" else "desligado"} · ligar ao abrir ${if (settings.autoStart) "sim" else "não"}"
        val report = LogBook.problemReport(header)
        // A .txt file (old reports deleted), so long reports don't get cut by the messaging app.
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val dir = ReportProvider.dir(this)
        dir.listFiles()?.forEach { it.delete() }
        val file = java.io.File(dir, "IvoMirror-relatorio-$stamp.txt").apply { writeText(report) }
        val uri = ReportProvider.uriFor(file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Relatório do IVO Mirror")
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri(file.name, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(send, getString(R.string.send_log)))
    }

    private fun paint() {
        val on = ReceiverService.running
        val phase = if (on) ReceiverService.phase else Phase.OFF
        val color = when (phase) { Phase.OFF -> DIM; Phase.READY -> AMBER; Phase.RECEIVING -> GREEN; Phase.FAILED -> RED }
        dot.background = rounded(color, 99)
        phaseText.text = getString(when (phase) {
            Phase.OFF -> R.string.phase_off; Phase.READY -> R.string.phase_ready
            Phase.RECEIVING -> R.string.phase_receiving; Phase.FAILED -> R.string.phase_failed
        })
        hint.text = when (phase) {
            Phase.OFF -> getString(R.string.off_hint)
            Phase.READY -> getString(R.string.ready_hint, ReceiverService.airplayName)
            else -> ReceiverService.status
        }
        val pic = ReceiverService.picture
        picture.text = pic
        picture.visibility = if (on && pic.isNotEmpty()) View.VISIBLE else View.GONE
        val n = ReceiverService.watchers
        ivoChip.text = if (n > 0) getString(R.string.chip_ivo, n) else getString(R.string.chip_ivo_none)
        ivoChip.setTextColor(if (n > 0) GREEN else DIM)
        (ivoChip.parent as View).visibility = if (on) View.VISIBLE else View.GONE

        button.text = getString(if (on) R.string.stop else R.string.start).uppercase()
        button.setTextColor(if (on) Color.parseColor("#FF8A8F") else ON_ACCENT)
        button.background = if (on) rounded(Color.parseColor("#2A1213"), 18, Color.parseColor("#5A2224"))
            else GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(LIME, GREEN, DEEP)).apply { cornerRadius = dp(18).toFloat() }

        val high = settings.high
        qualityPills.forEach { (p, h) ->
            val chosen = h == high
            p.setTextColor(if (chosen) ON_ACCENT else DIM)
            p.background = if (chosen) rounded(GREEN, 12) else null
        }
        qualityHint.text = getString(if (high) R.string.quality_high_hint else R.string.quality_normal_hint)
        switches.forEach { (t, get) -> paintSwitch(t, get()) }
        val pinRow = (pinCode.parent as View)
        pinRow.visibility = if (settings.pinOn) View.VISIBLE else View.GONE
        pinCode.text = settings.pinCode.toString()
        nextTime.visibility = if (on) View.VISIBLE else View.GONE
        val newer = settings.newerVersion
        updateCard.visibility = if (newer.isNotEmpty()) View.VISIBLE else View.GONE
        updateText.text = getString(R.string.update_card, newer)
        if (phase == Phase.READY && settings.pinOn)
            hint.text = getString(R.string.ready_hint_pin, ReceiverService.airplayName, settings.pinCode.toString())

        // The dot breathes while the picture comes in.
        if (phase == Phase.RECEIVING && pulse == null) {
            pulse = ValueAnimator.ofFloat(1f, 0.35f).apply {
                duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { dot.alpha = it.animatedValue as Float }
                start()
            }
        } else if (phase != Phase.RECEIVING) {
            pulse?.cancel(); pulse = null; dot.alpha = 1f
        }
    }
}
