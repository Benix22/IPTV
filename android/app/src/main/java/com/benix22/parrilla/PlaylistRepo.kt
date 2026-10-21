package com.benix22.parrilla

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.StringReader
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

    private fun hasXtream(): Boolean =
        prefs.xtreamServer.isNotBlank() && prefs.xtreamUser.isNotBlank() && prefs.xtreamPass.isNotBlank()

    fun hasSource(): Boolean =
        prefs.serverUrl.isNotBlank() || hasXtream() || prefs.listUrl.isNotBlank()

    fun cachedText(): String? = if (cacheFile.exists()) cacheFile.readText() else null

    private fun base(): String = prefs.serverUrl.trim().trimEnd('/')

    /** Bloqueante: llamar siempre fuera del hilo principal. */
    fun download(): Loaded {
        val ua = prefs.userAgent
        if (prefs.serverUrl.isNotBlank()) return downloadFromParrilla(ua)
        if (hasXtream()) return downloadXtream(ua)

        val text = Net.get(prefs.listUrl.trim(), ua)
        if (!text.contains("#EXTM3U") && !text.contains("#EXTINF")) {
            throw IOException("La respuesta no parece una lista M3U")
        }
        cacheFile.writeText(text)
        return Loaded(text, null)
    }

    private fun downloadFromParrilla(ua: String): Loaded {
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

    // ------------------------------------------------------------------ Xtream

    /** Deja solo esquema, servidor y puerto, por si se pega una URL completa. */
    private fun xtreamBase(): String {
        var s = prefs.xtreamServer.trim()
        if (!s.contains("://")) s = "http://$s"
        val u = URL(s)
        return u.protocol + "://" + u.host + (if (u.port != -1) ":" + u.port else "")
    }

    private fun str(r: JsonReader): String = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean().toString()
        JsonToken.NULL -> {
            r.nextNull()
            ""
        }
        else -> {
            r.skipValue()
            ""
        }
    }

    private fun readArray(text: String, each: (JsonReader) -> Unit) {
        val r = JsonReader(StringReader(text))
        r.isLenient = true
        try {
            if (r.peek() != JsonToken.BEGIN_ARRAY) {
                throw IOException("Respuesta no válida del servidor. Revisa servidor, usuario y contraseña.")
            }
            r.beginArray()
            while (r.hasNext()) each(r)
            r.endArray()
        } catch (e: IllegalStateException) {
            throw IOException("Respuesta no válida del servidor")
        } finally {
            r.close()
        }
    }

    /** Pide categorías y canales en directo a player_api.php, como hacen los reproductores Xtream. */
    private fun downloadXtream(ua: String): Loaded {
        val base = xtreamBase()
        val user = Uri.encode(prefs.xtreamUser.trim())
        val pass = Uri.encode(prefs.xtreamPass.trim())
        val api = "$base/player_api.php?username=$user&password=$pass"

        val cats = HashMap<String, String>()
        readArray(Net.get("$api&action=get_live_categories", ua)) { r ->
            var id = ""
            var name = ""
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "category_id" -> id = str(r)
                    "category_name" -> name = str(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (id.isNotEmpty()) cats[id] = name
        }

        val sb = StringBuilder("#EXTM3U\n")
        var count = 0
        readArray(Net.get("$api&action=get_live_streams", ua)) { r ->
            var id = ""
            var name = ""
            var icon = ""
            var cat = ""
            var epg = ""
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "stream_id" -> id = str(r)
                    "name" -> name = str(r)
                    "stream_icon" -> icon = str(r)
                    "category_id" -> cat = str(r)
                    "epg_channel_id" -> epg = str(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (id.isNotEmpty()) {
                val group = (cats[cat] ?: "Sin grupo").replace('"', '\'')
                val title = name.replace('\n', ' ').trim().ifEmpty { "Canal $id" }
                sb.append("#EXTINF:-1 tvg-id=\"").append(epg.replace('"', ' '))
                    .append("\" tvg-logo=\"").append(icon.replace('"', ' '))
                    .append("\" group-title=\"").append(group)
                    .append("\",").append(title).append('\n')
                sb.append(base).append("/live/").append(user).append('/').append(pass)
                    .append('/').append(id).append(".ts\n")
                count++
            }
        }
        if (count == 0) throw IOException("El servidor no devolvió ningún canal en directo")

        val text = sb.toString()
        cacheFile.writeText(text)
        return Loaded(text, null)
    }

    // ------------------------------------------------------------------ favoritos

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
