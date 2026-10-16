package android.content

import java.util.prefs.Preferences

/** Desktop stand-in for what :core's Settings needs from a Context: SharedPreferences, kept in java.util.prefs. */
open class Context {
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = SharedPreferences(Preferences.userRoot().node("kumapie/$name"))

    companion object { const val MODE_PRIVATE = 0 }
}

class SharedPreferences(private val node: Preferences) {
    fun getString(key: String, default: String?): String? = node.get(key, default)
    fun getBoolean(key: String, default: Boolean): Boolean = node.getBoolean(key, default)
    fun edit() = Editor()

    inner class Editor {
        fun putString(key: String, value: String?) = apply { if (value == null) node.remove(key) else node.put(key, value) }
        fun putBoolean(key: String, value: Boolean) = apply { node.putBoolean(key, value) }
        fun apply() = node.flush()
    }
}
