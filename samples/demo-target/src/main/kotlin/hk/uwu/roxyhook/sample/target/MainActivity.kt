package hk.uwu.roxyhook.sample.target

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class Greeting {
    fun message(name: String): String = "Hello, $name"
    fun engineLabel(): String = "Original app"
}
class MainActivity : Activity() {
    private lateinit var output: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { textSize = 22f }
        val refresh = Button(this).apply { text = "Refresh"; setOnClickListener { render() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 96, 32, 32)
            addView(output); addView(refresh)
        })
    }
    override fun onResume() { super.onResume(); render() }
    private fun render() {
        val greeting = Greeting()
        output.text = greeting.message("Android") + "\n\n" + greeting.engineLabel()
    }
}
