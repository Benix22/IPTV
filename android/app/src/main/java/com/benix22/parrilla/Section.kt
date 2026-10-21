package com.benix22.parrilla

enum class Section(val key: String, val label: String, val noun: String) {
    LIVE("live", "Directo", "canales"),
    MOVIES("movies", "Películas", "películas"),
    SERIES("series", "Series", "series")
}
