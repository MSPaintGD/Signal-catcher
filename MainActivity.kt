package zenith.signalcatcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.*
import android.provider.Settings
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

object Log {
    val lines = ArrayList<String>()
    @Volatile var onChange: (() -> Unit)? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    @Synchronized fun add(type: String, text: String) {
        lines.add(0, "${fmt.format(Date())} [$type] $text")
        if (lines.size > 1000) lines.removeAt(lines.size - 1)
        onChange?.invoke()
    }
    @Synchronized fun dump() = lines.joinToString("\n")
}

class MainActivity : Activity() {
    private lateinit var tv: TextView
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val perms = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) { perms += Manifest.permission.BLUETOOTH_SCAN; perms += Manifest.permission.BLUETOOTH_CONNECT }
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)

        val btn = Button(this).apply {
            text = "Allow notification access (for other apps' messages)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        tv = TextView(this).apply { textSize = 12f }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(btn)
        root.addView(ScrollView(this).apply { addView(tv) })
        setContentView(root)
        Log.onChange = { runOnUiThread { tv.text = Log.dump() } }
        tv.text = Log.dump()
    }
    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        val i = Intent(this, SignalService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }
    override fun onDestroy() { Log.onChange = null; super.onDestroy() }
}
