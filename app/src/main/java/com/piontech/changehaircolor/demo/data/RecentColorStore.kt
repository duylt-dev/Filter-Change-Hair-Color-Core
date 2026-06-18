package com.piontech.changehaircolor.demo.data

import android.content.Context

/**
 * Persists recently used colors, mirroring the original app's behaviour
 * (`com.myapp.haircolor` stored recent colors as JSON in SharedPreferences,
 * newest-first, capped at 5). To avoid an extra Gson dependency we serialise
 * the small int list ourselves as a comma-separated string.
 */
class RecentColorStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getRecentColors(): List<Int> {
        val raw = prefs.getString(KEY_RECENT, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull { it.trim().toIntOrNull() }
    }

    /** Adds [color] to the front, de-duplicates, caps the list at [MAX]. */
    fun addRecentColor(color: Int) {
        val current = getRecentColors().toMutableList()
        current.remove(color)
        current.add(0, color)
        while (current.size > MAX) current.removeAt(current.size - 1)
        prefs.edit().putString(KEY_RECENT, current.joinToString(",")).apply()
    }

    companion object {
        private const val PREF_NAME = "com.hair_color.demo"
        private const val KEY_RECENT = "recentColors"
        const val MAX = 5
    }
}
