package com.benix22.parrilla

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpStatusException(val code: Int) : IOException("El servidor respondió HTTP $code")

class Loaded(val text: String, val favs: Set<String>?)

object Net {
    /** Descarga texto con la identificación indicada. Sigue redirecciones, también entre http y https. */
    fun get(url: String, ua: String, token: String? = null): String {
        var current = url
        var redirects = 0
        var bearer = token
        val firstHost = URL(url).host

        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.setRequestProperty("User-Agent", ua)
                conn.setRequestProperty("Accept", "*/*")
                if (!bearer.isNullOrEmpty() && URL(current).host == firstHost) {
                    conn.setRequestProperty("Authorization", "Bearer $bearer")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw IOException("Redirección sin destino")
                    redirects++
                    if (redirects > 5) throw IOException("Demasiadas redirecciones")
                    current = URL(URL(current), loc).toString()
                    if (URL(current).host != firstHost) bearer = null
                    continue
                }
                if (code != 200) throw HttpStatusException(code)
                return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }
}

class PlaylistRepo(context: Context, private val prefs: Prefs) {
    private val cacheFile = File(context.filesDir, "playlist.m3u")

    fun hasSource(): Boolean = prefs.serverUrl.isNotBlank() || prefs.listUrl.isNotBlank()

    fun cachedText(): String? = if (cacheFile.exists()) cacheFile.readText() else null

    private fun base(): String = prefs.serverUrl.trim().trimEnd('/')

    /** Bloqueante: llamar siempre fuera del hilo principal. */
    fun download(): Loaded {
        val ua = prefs.userAgent
        if (prefs.serverUrl.isNotBlank()) {
            val raw = try {
                Net.get(base() + "/api/state", ua, prefs.token.trim())
            } catch (e: HttpStatusException) {
                if (e.code == 401) throw IOException("Token incorrecto o ausente (HTTP 401)")
                throw e
            }
            val kv = JSONObject(raw).getJSONObject("kv")
            val text = kv.optString("parrilla.text", "")
            if (text.isEmpty()) {
                throw IOException(
                    "El servidor no tiene ninguna lista guardada. Cárgala antes desde la web de Parrilla."
                )
            }
            val favRaw = kv.optString("parrilla.fav", "")
            cacheFile.writeText(text)
            return Loaded(text, if (favRaw.isNotEmpty()) parseFavs(favRaw) else null)
        }

        val text = Net.get(prefs.listUrl.trim(), ua)
        if (!text.contains("#EXTM3U") && !text.contains("#EXTINF")) {
            throw IOException("La respuesta no parece una lista M3U")
        }
        cacheFile.writeText(text)
        return Loaded(text, null)
    }

    /** Envía los favoritos al servidor Parrilla, si hay uno configurado. Bloqueante. */
    fun pushFavs(favs: Set<String>) {
        if (prefs.serverUrl.isBlank()) return
        val value = org.json.JSONArray(favs.toList()).toString()
        val body = JSONObject().put("value", value).toString()
        val conn = URL(base() + "/api/kv/parrilla.fav").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", prefs.userAgent)
            val t = prefs.token.trim()
            if (t.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $t")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}
