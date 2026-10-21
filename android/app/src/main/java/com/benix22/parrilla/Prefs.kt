package com.benix22.parrilla

import android.content.Context
import org.json.JSONArray

const val DEFAULT_UA = "VLC/3.0.20 LibVLC/3.0.20"

/** Lee un array JSON de URLs (el mismo formato que guarda la web de Parrilla). */
fun parseFavs(raw: String): MutableSet<String> {
    val out = LinkedHashSet<String>()
    if (raw.isEmpty()) return out
    try {
        val a = JSONArray(raw)
        for (i in 0 until a.length()) out.add(a.getString(i))
    } catch (e: Exception) {
        // formato no válido: se ignora
    }
    return out
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("parrilla", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = ""): String = sp.getString(key, def) ?: def
    private fun put(key: String, value: String) {
        sp.edit().putString(key, value).apply()
    }

    var listUrl: String
        get() = str("listUrl")
        set(v) = put("listUrl", v)

    var serverUrl: String
        get() = str("serverUrl")
        set(v) = put("serverUrl", v)

    var token: String
        get() = str("token")
        set(v) = put("token", v)

    var xtreamServer: String
        get() = str("xtreamServer")
        set(v) = put("xtreamServer", v)

    var xtreamUser: String
        get() = str("xtreamUser")
        set(v) = put("xtreamUser", v)

    var xtreamPass: String
        get() = str("xtreamPass")
        set(v) = put("xtreamPass", v)

    var userAgent: String
        get() = str("userAgent", DEFAULT_UA).ifBlank { DEFAULT_UA }
        set(v) = put("userAgent", v)

    var lastUrl: String
        get() = str("lastUrl")
        set(v) = put("lastUrl", v)

    var lastGroup: String
        get() = str("lastGroup")
        set(v) = put("lastGroup", v)

    fun loadFavs(): MutableSet<String> = parseFavs(str("favs"))

    fun saveFavs(f: Set<String>) {
        put("favs", JSONArray(f.toList()).toString())
    }
}
