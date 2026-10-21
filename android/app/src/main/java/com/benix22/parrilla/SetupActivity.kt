package com.benix22.parrilla

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {

    private val httpUrl = Regex("^https?://.+", RegexOption.IGNORE_CASE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val prefs = Prefs(this)
        val listUrl = findViewById<EditText>(R.id.listUrl)
        val serverUrl = findViewById<EditText>(R.id.serverUrl)
        val token = findViewById<EditText>(R.id.token)
        val userAgent = findViewById<EditText>(R.id.userAgent)
        val error = findViewById<TextView>(R.id.error)

        listUrl.setText(prefs.listUrl)
        serverUrl.setText(prefs.serverUrl)
        token.setText(prefs.token)
        userAgent.setText(prefs.userAgent)

        findViewById<Button>(R.id.save).setOnClickListener {
            val l = listUrl.text.toString().trim()
            val s = serverUrl.text.toString().trim()
            val ua = userAgent.text.toString().trim().ifEmpty { DEFAULT_UA }

            if (l.isEmpty() && s.isEmpty()) {
                error.text = "Indica la URL de la lista o la dirección del servidor Parrilla."
                return@setOnClickListener
            }
            if (s.isNotEmpty() && !httpUrl.matches(s)) {
                error.text = "El servidor debe empezar por http:// o https://"
                return@setOnClickListener
            }
            if (s.isEmpty() && !httpUrl.matches(l)) {
                error.text = "La URL de la lista debe empezar por http:// o https://"
                return@setOnClickListener
            }

            prefs.listUrl = l
            prefs.serverUrl = s
            prefs.token = token.text.toString().trim()
            prefs.userAgent = ua
            setResult(RESULT_OK)
            finish()
        }

        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
    }
}
