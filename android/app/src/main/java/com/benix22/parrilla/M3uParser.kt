package com.benix22.parrilla

/** Lee listas M3U/M3U8 con #EXTINF, tvg-logo, group-title y #EXTGRP. */
object M3uParser {
    private val attr = Regex("([\\w-]+)=\"([^\"]*)\"")
    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    fun parse(text: String): List<Channel> = parseLines(text.lineSequence())

    fun parseLines(lines: Sequence<String>): List<Channel> {
        val out = ArrayList<Channel>()
        var name = ""
        var group = ""
        var logo = ""
        var have = false

        for (raw in lines) {
            val l = raw.trim()
            if (l.isEmpty()) continue

            if (l.startsWith("#EXTINF")) {
                val body = l.substring(l.indexOf(':') + 1)
                val a = HashMap<String, String>()
                for (m in attr.findAll(body)) {
                    a[m.groupValues[1].lowercase()] = m.groupValues[2]
                }
                val rest = attr.replace(body, "")
                val ci = rest.indexOf(',')
                val title = if (ci >= 0) rest.substring(ci + 1).trim() else ""
                name = title.ifEmpty { a["tvg-name"] ?: "Sin nombre" }
                group = (a["group-title"] ?: "").trim()
                logo = a["tvg-logo"] ?: ""
                have = true
            } else if (l.startsWith("#EXTGRP:")) {
                if (have && group.isEmpty()) group = l.substring(8).trim()
            } else if (l.startsWith("#")) {
                continue
            } else if (scheme.containsMatchIn(l)) {
                val url = l.substringBefore('|')
                out.add(
                    Channel(
                        if (have) name else url,
                        group.ifEmpty { "Sin grupo" },
                        logo,
                        url
                    )
                )
                have = false
                name = ""
                group = ""
                logo = ""
            }
        }
        return out
    }
}
