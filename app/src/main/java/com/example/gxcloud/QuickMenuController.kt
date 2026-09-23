package com.example.gxcloud

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewStub
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONTokener
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

class QuickMenuController(
    private val stub: ViewStub,
    private val requestStats: ((String) -> Unit) -> Unit,
    private val checkWebGpu: () -> Unit,
    private val setCasMode: (String) -> Unit,
    private val batteryPercent: () -> Int,
    private val openNotes: () -> Unit,
    private val toggleDiscordEnabled: () -> Boolean,
    private val isDiscordEnabled: () -> Boolean
) {
    private var container: FrameLayout? = null
    private var panel: View? = null
    private var discordCard: View? = null
    private var discordTitle: TextView? = null
    private var discordSubtitle: TextView? = null
    private var headerStatus: TextView? = null
    private var title: TextView? = null
    private var fullStatsText: TextView? = null
    private var statsPage: View? = null
    private val mainPageViews = mutableListOf<View>()
    private val casButtons = mutableMapOf<String, Button>()
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    private var casMode = "normal"
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStats()
            if (isVisible) handler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    val isVisible: Boolean get() = container?.visibility == View.VISIBLE

    fun show() {
        if (container == null) inflate()
        showMainPage()
        container!!.visibility = View.VISIBLE
        updateDiscordLabel()
        resizePanel()
        refreshStats()
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, STATS_INTERVAL_MS)
    }

    fun hide() {
        handler.removeCallbacks(refreshRunnable)
        container?.visibility = View.GONE
    }

    // isVisible alone doesn't account for the Activity being backgrounded — without
    // these, refreshRunnable keeps polling battery and poking the (paused) WebView via
    // evaluateJavascript once a second the whole time the menu is left open behind Home.
    fun onPause() {
        handler.removeCallbacks(refreshRunnable)
    }

    fun onResume() {
        if (isVisible) {
            handler.removeCallbacks(refreshRunnable)
            handler.postDelayed(refreshRunnable, STATS_INTERVAL_MS)
        }
    }

    fun handleBack(): Boolean {
        if (statsPage?.visibility == View.VISIBLE) {
            showMainPage()
            return true
        }
        return false
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        container = null
        panel = null
    }

    private fun inflate() {
        val root = stub.inflate() as FrameLayout
        container = root
        panel = root.findViewById(R.id.quickMenuPanel)
        discordCard = root.findViewById(R.id.quickMenuDiscord)
        discordTitle = root.findViewById(R.id.quickMenuDiscordTitle)
        discordSubtitle = root.findViewById(R.id.quickMenuDiscordSubtitle)
        headerStatus = root.findViewById(R.id.quickMenuHeaderStatus)
        title = root.findViewById(R.id.quickMenuTitle)
        fullStatsText = root.findViewById(R.id.quickMenuFullStats)
        statsPage = root.findViewById(R.id.quickMenuStatsPage)

        root.setOnClickListener { hide() }
        panel!!.setOnClickListener { /* Consume backdrop clicks that land on the panel. */ }
        root.findViewById<Button>(R.id.quickMenuClose).setOnClickListener { hide() }
        root.findViewById<View>(R.id.quickMenuNotes).setOnClickListener {
            hide()
            openNotes()
        }
        discordCard!!.setOnClickListener {
            toggleDiscordEnabled()
            updateDiscordLabel()
        }
        root.findViewById<View>(R.id.quickMenuStatsCard).setOnClickListener { showStatsPage() }
        root.findViewById<Button>(R.id.quickMenuStatsBack).setOnClickListener { showMainPage() }
        mapOf(
            "off" to R.id.quickMenuCasOff,
            "normal" to R.id.quickMenuCasNormal,
            "high" to R.id.quickMenuCasHigh
        ).forEach { (mode, id) ->
            root.findViewById<Button>(id).also { button ->
                casButtons[mode] = button
                button.setOnClickListener {
                    casMode = mode
                    updateCasSelection()
                    setCasMode(mode)
                }
            }
        }
        mainPageViews += listOf(
            root.findViewById(R.id.quickMenuNotes),
            root.findViewById(R.id.quickMenuDiscord),
            root.findViewById(R.id.quickMenuDivider),
            root.findViewById(R.id.quickMenuCasHeading),
            root.findViewById(R.id.quickMenuStatsCard),
            root.findViewById(R.id.quickMenuFooter),
            root.findViewById<Button>(R.id.quickMenuCasOff).parent as View
        )
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resizePanel() }
        updateCasSelection()
    }

    private fun resizePanel() {
        val root = container ?: return
        val card = panel ?: return
        if (root.width == 0 || root.height == 0) return
        val params = card.layoutParams as FrameLayout.LayoutParams
        val width = (root.width * PANEL_FRACTION).roundToInt()
        val height = (root.height * PANEL_FRACTION).roundToInt()
        // Bail if unchanged: setLayoutParams below already requests a layout pass, and
        // that pass re-invokes the addOnLayoutChangeListener that calls this function —
        // an unconditional assignment here loops every frame for as long as the menu is
        // visible (visible as a stream of "requestLayout() improperly called" warnings).
        if (params.width == width && params.height == height) return
        params.width = width
        params.height = height
        card.layoutParams = params
    }

    private fun updateCasSelection() {
        casButtons.forEach { (mode, button) ->
            val selected = mode == casMode
            button.isSelected = selected
            button.setTextColor(if (selected) Color.WHITE else Color.rgb(210, 210, 210))
            button.setBackgroundResource(
                if (selected) R.drawable.quick_menu_segment_selected else R.drawable.quick_menu_segment
            )
        }
    }

    private fun updateDiscordLabel() {
        val enabled = isDiscordEnabled()
        discordTitle?.text = if (enabled) "Turn Off Discord" else "Turn On Discord"
        discordSubtitle?.text = if (enabled) "Four-tap Discord shortcut is enabled" else "Enable the four-tap Discord shortcut"
    }

    private fun showStatsPage() {
        mainPageViews.forEach { it.visibility = View.GONE }
        statsPage?.visibility = View.VISIBLE
        title?.text = "Stream Stats"
        checkWebGpu()
        refreshStats()
    }

    private fun showMainPage() {
        mainPageViews.forEach { it.visibility = View.VISIBLE }
        statsPage?.visibility = View.GONE
        title?.text = "Quick Menu"
    }

    private fun refreshStats() {
        if (!isVisible) return
        updateDiscordLabel()
        val battery = batteryPercent().takeIf { it in 0..100 }?.let { "$it%" } ?: "--%"
        val time = timeFormat.format(Date())
        val header = "$battery   |   $time"
        if (headerStatus?.text != header) headerStatus?.text = header
        // The full stats page is the only consumer of requestStats — skip the renderer
        // IPC (evaluateJavascript) entirely while it isn't visible.
        if (statsPage?.visibility != View.VISIBLE) return
        requestStats { raw ->
            if (!isVisible || statsPage?.visibility != View.VISIBLE) return@requestStats
            try {
                val value = JSONTokener(raw).nextValue() as? String ?: return@requestStats
                val stats = org.json.JSONObject(value)
                val newCasMode = stats.optString("casMode", casMode)
                if (newCasMode != casMode) {
                    casMode = newCasMode
                    updateCasSelection()
                }
                val resolution = stats.optString("resolution", "--")
                val total = stats.optString("totalFrames", "--")
                val presented = stats.optString("presentedFrames", "--")
                val state = stats.optString("state", "No active stream")
                val text = "Resolution      $resolution\n" +
                    "Playback state  $state\n" +
                    "Total frames    $total\n" +
                    "Dropped frames  $presented\n" +
                    "CAS mode        ${casMode.replaceFirstChar { it.uppercase() }}\n" +
                    "WebGPU compat   ${stats.optString("webGpuStatus", "Not checked")}\n" +
                    "Shader FP16     ${stats.optString("webGpuShaderF16", "--")}\n" +
                    "Battery         $battery\n" +
                    "Local time      $time"
                if (fullStatsText?.text != text) fullStatsText?.text = text
            } catch (_: Exception) {
                val fallback = "No active stream statistics available."
                if (fullStatsText?.text != fallback) fullStatsText?.text = fallback
            }
        }
    }

    private companion object {
        const val PANEL_FRACTION = 0.80f
        const val STATS_INTERVAL_MS = 1_000L
    }
}
