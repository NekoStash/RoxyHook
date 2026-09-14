package hk.uwu.roxyhook.sample.module

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import hk.uwu.roxyhook.platform.libxposed.service.LibXposedService
import hk.uwu.roxyhook.platform.libxposed.service.RoxyServices
import hk.uwu.roxyhook.prefs.Subscription

class MainActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var toggle: Button
    private var subscription: Subscription? = null
    private var selected: LibXposedService? = null
    private var available: List<LibXposedService> = emptyList()
    private lateinit var channelDemo: ChannelDemoController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { textSize = 18f }
        channelDemo = ChannelDemoController(this) { output.text = it }
        toggle = Button(this).apply {
            text = "Toggle greeting hook"
            isEnabled = false
            setOnClickListener {
                val service = selected ?: return@setOnClickListener
                runCatching {
                    val preferences = service.preferences(DemoPreferences.GROUP)
                    val enabled = !preferences[DemoPreferences.enabled]
                    preferences.edit { this[DemoPreferences.enabled] = enabled }
                    output.text = "enabled=$enabled\nReturn to RoxyHook Target and press Refresh."
                }.onFailure { output.text = it.toString() }
            }
        }
        val choose = Button(this).apply {
            text = "Select framework"
            setOnClickListener {
                val snapshot = available
                if (snapshot.isEmpty()) return@setOnClickListener
                val names = snapshot.map { runCatching { it.info.name }.getOrDefault("Disconnected") }.toTypedArray()
                AlertDialog.Builder(this@MainActivity).setTitle("Framework")
                    .setItems(names) { _, index -> select(snapshot[index]) }.show()
            }
        }
        val initializeChannel = Button(this).apply {
            text = "Initialize channel"
            setOnClickListener { selected?.let(channelDemo::initialize) }
        }
        val pingTarget = Button(this).apply {
            text = "Ping target"
            setOnClickListener { channelDemo.ping() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 96, 32, 32)
            addView(output); addView(toggle); addView(choose); addView(initializeChannel); addView(pingTarget)
        })
        subscription = RoxyServices.observe { services ->
            available = services
            val previous = selected
            when {
                previous != null && previous in services -> select(previous)
                services.size == 1 -> select(services.single())
                else -> {
                    selected = null; toggle.isEnabled = false; channelDemo.disconnect()
                    output.text = if (services.isEmpty()) "No framework service connected. Enable the module first."
                    else "Multiple frameworks connected. Select one explicitly."
                }
            }
        }
    }
    private fun select(service: LibXposedService) {
        if (selected !== service) channelDemo.disconnect()
        selected = service
        runCatching {
            val info = service.info
            toggle.isEnabled = service.supportsRemoteData
            output.text = "${info.name} ${info.version}\nAPI ${info.apiVersion}\nScope: ${service.scope().joinToString()}"
        }.onFailure { selected = null; toggle.isEnabled = false; output.text = it.toString() }
    }
    override fun onDestroy() {
        subscription?.close(); subscription = null
        channelDemo.close()
        super.onDestroy()
    }
}
