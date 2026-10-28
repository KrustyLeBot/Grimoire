package app.grimoire

import android.content.Context

/** Device-only preferences (not synced): they depend on the phone, not on the reader. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var wifiOnlyDownloads: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(v) = prefs.edit().putBoolean("wifi_only", v).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(v) = prefs.edit().putBoolean("keep_screen_on", v).apply()

    /** Pages mode reads right to left (Japanese manga). */
    var rightToLeft: Boolean
        get() = prefs.getBoolean("rtl", false)
        set(v) = prefs.edit().putBoolean("rtl", v).apply()

    var textSize: Int
        get() = prefs.getInt("text_size", 18)
        set(v) = prefs.edit().putInt("text_size", v).apply()

    /** When the library's chapter lists were last checked for new chapters. */
    var libraryCheckedAt: Long
        get() = prefs.getLong("library_checked_at", 0)
        set(v) = prefs.edit().putLong("library_checked_at", v).apply()
}
