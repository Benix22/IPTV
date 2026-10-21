package com.benix22.parrilla

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

class HttpStatusException(val code: Int) : IOException("El servidor respondió HTTP $code")

class Loaded(val text: String, val favs: Set<String>?)

object Net {
    /**
     * Abre la conexión y devuelve la respuesta lista para leer (HTTP 200).
     * Sigue redirecciones, también entre http y https. Quien la llama debe hacer disconnect().
     */
    fun open(url: String, ua: String, token: String? = null, readTimeoutMs: Int = 60000): HttpURLConnection {
        var current = url
        var redirects = 0
        var bearer = token
        val firstHost = URL(url).host

        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            var ok = false
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000
                conn.readTimeout = readTimeoutMs
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
                ok = true
                return conn
            } finally {
                if (!ok) conn.disconnect()
            }
        }
    }

    fun get(url: String, ua: String, token: String? = null): String {
        val conn = open(url, ua, token)
        try {
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

class PlaylistRepo(context: Context, private val prefs: Prefs) {
    private val dir: File = context.filesDir
    private val liveFile = File(dir, "playlist.m3u")

    private class Xt(val base: String, val user: String, val pass: String) {
        val api: String get() = "$base/player_api.php?username=$user&password=$pass"
    }

    private fun hasXtream(): Boolean =
        prefs.xtreamServer.isNotBlank() && prefs.xtreamUser.isNotBlank() && prefs.xtreamPass.isNotBlank()

    /** Películas y series solo existen con acceso Xtream (y sin servidor Parrilla, que tiene prioridad). */
    fun xtreamMode(): Boolean = prefs.serverUrl.isBlank() && hasXtream()

    fun hasSource(): Boolean =
        prefs.serverUrl.isNotBlank() || hasXtream() || prefs.listUrl.isNotBlank()

    fun cachedText(): String? = if (liveFile.exists()) liveFile.readText() else null

    private fun cacheFile(sec: Section): File =
        if (sec == Section.LIVE) liveFile else File(dir, sec.key + ".m3u")

    fun clearVodCache() {
        cacheFile(Section.MOVIES).delete()
        cacheFile(Section.SERIES).delete()
    }

    private fun base(): String = prefs.serverUrl.trim().trimEnd('/')

    /** Bloqueante: llamar siempre fuera del hilo principal. */
    fun download(): Loaded {
        val ua = prefs.userAgent
        if (prefs.serverUrl.isNotBlank()) return downloadFromParrilla(ua)
        if (hasXtream()) return downloadXtreamLive(ua)

        val text = Net.get(prefs.listUrl.trim(), ua)
        if (!text.contains("#EXTM3U") && !text.contains("#EXTINF")) {
            throw IOException("La respuesta no parece una lista M3U")
        }
        liveFile.writeText(text)
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
        liveFile.writeText(text)
        return Loaded(text, if (favRaw.isNotEmpty()) parseFavs(favRaw) else null)
    }

    // ------------------------------------------------------------------ Xtream

    /** Deja solo esquema, servidor y puerto, por si se pega una URL completa. */
    private fun xt(): Xt {
        var s = prefs.xtreamServer.trim()
        if (!s.contains("://")) s = "http://$s"
        val u = URL(s)
        val base = u.protocol + "://" + u.host + (if (u.port != -1) ":" + u.port else "")
        return Xt(base, Uri.encode(prefs.xtreamUser.trim()), Uri.encode(prefs.xtreamPass.trim()))
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

    private fun readArray(r: JsonReader, each: (JsonReader) -> Unit) {
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

    private fun readCategories(x: Xt, action: String, ua: String): Map<String, String> {
        val cats = HashMap<String, String>()
        val text = Net.get(x.api + "&action=" + action, ua)
        readArray(JsonReader(StringReader(text))) { r ->
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
        return cats
    }

    /** Canales en directo: pide categorías y canales a player_api.php, como hacen los reproductores Xtream. */
    private fun downloadXtreamLive(ua: String): Loaded {
        val x = xt()
        val cats = readCategories(x, "get_live_categories", ua)

        val sb = StringBuilder("#EXTM3U\n")
        var count = 0
        val text = Net.get(x.api + "&action=get_live_streams", ua)
        readArray(JsonReader(StringReader(text))) { r ->
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
                sb.append(x.base).append("/live/").append(x.user).append('/').append(x.pass)
                    .append('/').append(id).append(".ts\n")
                count++
            }
        }
        if (count == 0) throw IOException("El servidor no devolvió ningún canal en directo")

        val out = sb.toString()
        liveFile.writeText(out)
        return Loaded(out, null)
    }

    /**
     * Películas o series: se descarga en streaming a un archivo (las listas pueden ser enormes).
     * Cada serie queda como una entrada con dirección series://ID; sus episodios se piden al abrirla.
     */
    private fun downloadXtreamSection(sec: Section) {
        if (sec == Section.LIVE || !xtreamMode()) {
            throw IOException("Películas y series solo están disponibles con acceso Xtream")
        }
        val ua = prefs.userAgent
        val x = xt()
        val catAction = if (sec == Section.MOVIES) "get_vod_categories" else "get_series_categories"
        val itemAction = if (sec == Section.MOVIES) "get_vod_streams" else "get_series"
        val cats = readCategories(x, catAction, ua)

        val tmp = File(dir, sec.key + ".tmp")
        var count = 0
        val conn = Net.open(x.api + "&action=" + itemAction, ua, null, 180000)
        try {
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write("#EXTM3U\n")
                val reader = JsonReader(
                    BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8), 65536)
                )
                readArray(reader) { r ->
                    var id = ""
                    var name = ""
                    var icon = ""
                    var cat = ""
                    var ext = ""
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "stream_id", "series_id" -> id = str(r)
                            "name" -> name = str(r)
                            "stream_icon", "cover" -> icon = str(r)
                            "category_id" -> cat = str(r)
                            "container_extension" -> ext = str(r)
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (id.isNotEmpty()) {
                        val group = (cats[cat] ?: "Sin categoría").replace('"', '\'')
                        val title = name.replace('\n', ' ').trim().ifEmpty { "Sin título $id" }
                        val url = if (sec == Section.MOVIES) {
                            x.base + "/movie/" + x.user + "/" + x.pass + "/" + id + "." + ext.ifEmpty { "mp4" }
                        } else {
                            "series://$id"
                        }
                        w.write(
                            "#EXTINF:-1 tvg-logo=\"" + icon.replace('"', ' ') +
                                "\" group-title=\"" + group + "\"," + title + "\n" + url + "\n"
                        )
                        count++
                    }
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        if (count == 0) {
            tmp.delete()
            throw IOException("El servidor no devolvió ninguna entrada")
        }
        val f = cacheFile(sec)
        f.delete()
        if (!tmp.renameTo(f)) throw IOException("No se pudo guardar la lista")
    }

    /** Devuelve películas o series; usa el archivo guardado salvo que se fuerce la descarga. Bloqueante. */
    fun loadVodSection(sec: Section, force: Boolean): List<Channel> {
        val f = cacheFile(sec)
        if (force || !f.exists()) downloadXtreamSection(sec)
        return f.bufferedReader(Charsets.UTF_8).useLines { M3uParser.parseLines(it) }
    }

    /** Episodios de una serie, con su dirección de reproducción. Bloqueante. */
    fun loadEpisodes(seriesId: String): List<Channel> {
        if (!xtreamMode()) throw IOException("Las series solo están disponibles con acceso Xtream")
        val x = xt()
        val out = ArrayList<Channel>()
        val conn = Net.open(
            x.api + "&action=get_series_info&series_id=" + Uri.encode(seriesId),
            prefs.userAgent
        )
        try {
            val r = JsonReader(BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)))
            r.isLenient = true
            try {
                if (r.peek() != JsonToken.BEGIN_OBJECT) throw IOException("Respuesta no válida del servidor")
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() == "episodes") readEpisodes(r, out, x) else r.skipValue()
                }
                r.endObject()
            } catch (e: IllegalStateException) {
                throw IOException("Respuesta no válida del servidor")
            } finally {
                r.close()
            }
        } finally {
            conn.disconnect()
        }
        if (out.isEmpty()) throw IOException("Esta serie no tiene episodios")
        return out
    }

    private fun readEpisodes(r: JsonReader, out: MutableList<Channel>, x: Xt) {
        when (r.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                r.beginObject()
                while (r.hasNext()) {
                    val seasonKey = r.nextName()
                    readSeason(r, seasonKey, out, x)
                }
                r.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                r.beginArray()
                var i = 1
                while (r.hasNext()) {
                    readSeason(r, i.toString(), out, x)
                    i++
                }
                r.endArray()
            }
            else -> r.skipValue()
        }
    }

    private fun readSeason(r: JsonReader, seasonKey: String, out: MutableList<Channel>, x: Xt) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue()
            return
        }
        r.beginArray()
        while (r.hasNext()) {
            var id = ""
            var title = ""
            var num = ""
            var ext = ""
            var season = seasonKey
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "id" -> id = str(r)
                    "title" -> title = str(r)
                    "episode_num" -> num = str(r)
                    "container_extension" -> ext = str(r)
                    "season" -> {
                        val s = str(r)
                        if (s.isNotEmpty()) season = s
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (id.isNotEmpty()) {
                val label = title.replace('\n', ' ').trim().ifEmpty { "Episodio $num" }
                out.add(
                    Channel(
                        "T$season E$num · $label",
                        "Temporada $season",
                        "",
                        x.base + "/series/" + x.user + "/" + x.pass + "/" + id + "." + ext.ifEmpty { "mp4" }
                    )
                )
            }
        }
        r.endArray()
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
