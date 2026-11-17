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
    private val lines = ArrayList<String>()
    @Volatile var onChange: (() -> Unit)? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    fun add(type: String, text: String) {
        synchronized(this) {
            lines.add(0, "${fmt.format(Date())} [$type] $text")
            if (lines.size > 1000) lines.removeAt(lines.size - 1)
        }
        onChange?.invoke()
    }
    @Synchronized fun dump() = lines.joinToString("\n")
}

class MainActivity : Activity() {
    private lateinit var tv: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val scheduled = AtomicBoolean(false)
    private val refresh = Runnable {
        scheduled.set(false)
        tv.text = Log.dump()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val btn = Button(this).apply {
            text = "Allow notification access (for other apps' messages)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        tv = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(16, 16, 16, 16)
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(btn)
        root.addView(ScrollView(this).apply { addView(tv) })
        setContentView(root)
        tv.text = Log.dump()
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
