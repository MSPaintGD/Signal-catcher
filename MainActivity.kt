package zenith.signalcatcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.*
import android.provider.Settings
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

object Log {
    private class Entry(val type: String, val text: String)

    private val lines = ArrayList<Entry>()
    private val counts = HashMap<String, Int>()
    @Volatile var onChange: (() -> Unit)? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(type: String, text: String) {
        synchronized(this) {
            lines.add(0, Entry(type, "${fmt.format(Date())} [$type] $text"))
            val c = (counts[type] ?: 0) + 1
            counts[type] = c
            if (c > 600) {
                val i = lines.indexOfLast { it.type == type }
                if (i >= 0) {
                    lines.removeAt(i)
                    counts[type] = c - 1
                }
            }
        }
        onChange?.invoke()
    }

    @Synchronized fun dump(types: Set<String>? = null): String =
        lines.asSequence().filter { types == null || it.type in types }.take(400).joinToString("\n") { it.text }
}

class MainActivity : Activity() {
    private lateinit var tv: TextView
    private lateinit var sv: ScrollView
    private val buttons = ArrayList<Button>()
    private var current = 0
    private val ui = Handler(Looper.getMainLooper())
    private val scheduled = AtomicBoolean(false)
    private val refresh = Runnable {
        scheduled.set(false)
        tv.text = Log.dump(tabs[current].second)
    }

    private val tabs: List<Pair<String, Set<String>?>> = listOf(
        "All" to null,
        "Cell" to setOf("CELL"),
        "Wi-Fi" to setOf("WIFI"),
        "Bluetooth" to setOf("BT"),
        "GPS" to setOf("GPS"),
        "Sensors" to setOf("SENS", "NFC"),
        "Messages" to setOf("SMS", "MSG"),
        "System" to setOf("SYS", "SIG", "ERR")
    )

    private fun showTab(i: Int) {
        current = i
        buttons.forEachIndexed { n, b -> b.alpha = if (n == i) 1f else 0.5f }
        tv.text = Log.dump(tabs[i].second)
        sv.scrollTo(0, 0)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        current = b?.getInt("tab") ?: 0

        val btn = Button(this).apply {
            text = "Allow notification access (for other apps' messages)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.forEachIndexed { idx, t ->
            val tb = Button(this).apply {
                text = t.first
                setAllCaps(false)
                setOnClickListener { showTab(idx) }
            }
            buttons.add(tb)
            bar.addView(tb)
        }
        val barScroll = HorizontalScrollView(this).apply { addView(bar) }

        tv = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(16, 16, 16, 16)
        }
        sv = ScrollView(this).apply { addView(tv) }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(btn)
        root.addView(barScroll)
        root.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        showTab(current)
        Log.onChange = { if (scheduled.compareAndSet(false, true)) ui.postDelayed(refresh, 250) }

        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onSaveInstanceState(o: Bundle) {
        super.onSaveInstanceState(o)
        o.putInt("tab", current)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        val i = Intent(this, SignalService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            Log.add("ERR", "could not start service: ${e.message}")
        }
    }

    override fun onDestroy() {
        Log.onChange = null
        ui.removeCallbacks(refresh)
        super.onDestroy()
    }
}
