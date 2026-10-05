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
    private val setCasMode: (String) -> Unit,
    private val getCasMode: () -> String,
    private val batteryPercent: () -> Int,
    private val ramStatus: () -> String?,
    private val thermalStatus: () -> String?,
    private val wattage: () -> Double?,
    private val openNotes: () -> Unit,
    private val toggleDiscordEnabled: () -> Boolean,
    private val isDiscordEnabled: () -> Boolean,
    private val togglePreferIpv6: () -> Boolean,
    private val isPreferIpv6: () -> Boolean
) {
    private var container: FrameLayout? = null
    private var panel: View? = null
    private var discordCard: View? = null
    private var discordTitle: TextView? = null
    private var discordSubtitle: TextView? = null
    private var ipv6State: TextView? = null
    private var headerStatus: TextView? = null
    private var title: TextView? = null
    private var fullStatsText: TextView? = null
    private var statsPage: View? = null
    private var mainPage: View? = null
    private val casButtons = mutableMapOf<String, Button>()
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStats()
            scheduleRefresh()
        }
    }

    val isVisible: Boolean get() = container?.visibility == View.VISIBLE

    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        if (isVisible) {
            val interval = if (statsPage?.visibility == View.VISIBLE) STATS_INTERVAL_MS else HEADER_INTERVAL_MS
            handler.postDelayed(refreshRunnable, interval)
        }
    }

    fun show() {
        if (container == null) inflate()
        showMainPage()
        container!!.visibility = View.VISIBLE
        updateDiscordLabel()
        updateCasSelection()
        resizePanel()
        refreshStats()
        scheduleRefresh()
    }

    fun hide() {
        handler.removeCallbacks(refreshRunnable)
        container?.visibility = View.GONE
    }

    // isVisible alone doesn't account for the Activity being backgrounded — without
    // these, refreshRunnable keeps polling battery and poking the (paused) WebView via
    // evaluateJavascript while the menu is left open behind Home.
    fun onPause() {
        handler.removeCallbacks(refreshRunnable)
    }

    fun onResume() {
        scheduleRefresh()
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
        mainPage = root.findViewById(R.id.quickMenuMainPage)
        ipv6State = root.findViewById(R.id.quickMenuIpv6State)

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
        root.findViewById<View>(R.id.quickMenuIpv6).setOnClickListener {
            togglePreferIpv6()
            updateIpv6State()
        }
        mapOf(
            "off" to R.id.quickMenuCasOff,
            "normal" to R.id.quickMenuCasNormal,
            "high" to R.id.quickMenuCasHigh
        ).forEach { (mode, id) ->
            root.findViewById<Button>(id).also { button ->
                casButtons[mode] = button
                button.setOnClickListener {
                    setCasMode(mode)
                    updateCasSelection()
                }
            }
        }
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
        val casMode = getCasMode()
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
        val title = if (enabled) "Turn Off Discord" else "Turn On Discord"
        val subtitle = if (enabled) "Four-tap Discord shortcut is enabled" else "Enable the four-tap Discord shortcut"
        if (discordTitle?.text != title) discordTitle?.text = title
        if (discordSubtitle?.text != subtitle) discordSubtitle?.text = subtitle
    }

    private fun updateIpv6State() {
        val enabled = isPreferIpv6()
        ipv6State?.text = if (enabled) "On" else "Off"
        ipv6State?.setTextColor(if (enabled) Color.WHITE else Color.rgb(210, 210, 210))
        ipv6State?.setBackgroundResource(
            if (enabled) R.drawable.quick_menu_segment_selected else R.drawable.quick_menu_segment
        )
    }

    private fun showStatsPage() {
        mainPage?.visibility = View.GONE
        statsPage?.visibility = View.VISIBLE
        title?.text = "Stream Stats"
        updateIpv6State()
        refreshStats()
        scheduleRefresh()
    }

    private fun showMainPage() {
        mainPage?.visibility = View.VISIBLE
        statsPage?.visibility = View.GONE
        title?.text = "Quick Menu"
        scheduleRefresh()
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
                // Native selection is authoritative; an older document cannot reset it.
                val casMode = getCasMode()
                val resolution = stats.optString("resolution", "--")
                val total = stats.optString("totalFrames", "--")
                val presented = stats.optString("presentedFrames", "--")
                val state = stats.optString("state", "No active stream")
                // System CPU% and GPU clock/busy% are not shown: confirmed on this device
                // that neither has a readable data source without root (/proc/stat has been
                // blocked to third-party apps since Android 7; the kgsl sysfs files were
                // denied even to adb shell). See AGENTS.md "Power Profile".
                val ram = ramStatus() ?: "--"
                val thermal = thermalStatus() ?: "--"
                val watts = wattage()?.let { String.format("%.1f W", it) } ?: "--"
                // Count of IPv6 candidates the server offered in its last ICE response;
                // null until a stream has connected in this document.
                val serverIpv6 = when (val count = stats.opt("serverIpv6Candidates")) {
                    is Int -> if (count > 0) "$count offered" else "None offered"
                    else -> "--"
                }
                // Address family and direct/relay of the selected ICE pair. The
                // compatibility getStats fallback can be one poll behind.
                val connection = stats.opt("connectionPath") as? String ?: "--"
                val text = "Resolution      $resolution\n" +
                    "Playback state  $state\n" +
                    "Total frames    $total\n" +
                    "Presented frames $presented\n" +
                    "CAS mode        ${casMode.replaceFirstChar { it.uppercase() }}\n" +
                    "RAM used        $ram\n" +
                    "Thermal status  $thermal\n" +
                    "Power draw      $watts\n" +
                    "Server IPv6     $serverIpv6\n" +
                    "Connection      $connection"
                if (fullStatsText?.text != text) fullStatsText?.text = text
            } catch (_: Exception) {
                val fallback = "No active stream statistics available."
                if (fullStatsText?.text != fallback) fullStatsText?.text = fallback
            }
        }
    }

    private companion object {
        const val PANEL_FRACTION = 0.80f
        const val HEADER_INTERVAL_MS = 5_000L
        const val STATS_INTERVAL_MS = 1_000L
    }
}
