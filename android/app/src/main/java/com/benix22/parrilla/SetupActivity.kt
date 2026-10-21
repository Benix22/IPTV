package com.benix22.parrilla

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {

    private val httpUrl = Regex("^https?://.+", RegexOption.IGNORE_CASE)
    private val anyScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val prefs = Prefs(this)
        val xtServer = findViewById<EditText>(R.id.xtServer)
        val xtUser = findViewById<EditText>(R.id.xtUser)
        val xtPass = findViewById<EditText>(R.id.xtPass)
        val listUrl = findViewById<EditText>(R.id.listUrl)
        val serverUrl = findViewById<EditText>(R.id.serverUrl)
        val token = findViewById<EditText>(R.id.token)
        val userAgent = findViewById<EditText>(R.id.userAgent)
        val error = findViewById<TextView>(R.id.error)

        xtServer.setText(prefs.xtreamServer)
        xtUser.setText(prefs.xtreamUser)
        xtPass.setText(prefs.xtreamPass)
        listUrl.setText(prefs.listUrl)
        serverUrl.setText(prefs.serverUrl)
        token.setText(prefs.token)
        userAgent.setText(prefs.userAgent)

        findViewById<Button>(R.id.save).setOnClickListener {
            val xs = xtServer.text.toString().trim()
            val xu = xtUser.text.toString().trim()
            val xp = xtPass.text.toString().trim()
            val l = listUrl.text.toString().trim()
            val s = serverUrl.text.toString().trim()
            val ua = userAgent.text.toString().trim().ifEmpty { DEFAULT_UA }

            val anyXtream = xs.isNotEmpty() || xu.isNotEmpty() || xp.isNotEmpty()
            val fullXtream = xs.isNotEmpty() && xu.isNotEmpty() && xp.isNotEmpty()

            when {
                s.isNotEmpty() -> {
                    if (!httpUrl.matches(s)) {
                        error.text = "El servidor Parrilla debe empezar por http:// o https://"
                        return@setOnClickListener
                    }
                }
                anyXtream -> {
                    if (!fullXtream) {
                        error.text = "Para Xtream rellena servidor, usuario y contraseña."
                        return@setOnClickListener
                    }
                    if (anyScheme.matches(xs) && !httpUrl.matches(xs)) {
                        error.text = "El servidor Xtream debe ser http:// o https://, o solo el nombre."
                        return@setOnClickListener
                    }
                }
                l.isEmpty() -> {
                    error.text = "Rellena una de las tres opciones."
                    return@setOnClickListener
                }
                !httpUrl.matches(l) -> {
                    error.text = "La URL de la lista debe empezar por http:// o https://"
                    return@setOnClickListener
                }
            }

            prefs.xtreamServer = xs
            prefs.xtreamUser = xu
            prefs.xtreamPass = xp
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
