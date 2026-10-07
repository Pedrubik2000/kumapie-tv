package io.github.pedrubik2000.kumapie.i18n

import java.util.Locale

/**
 * The menus in English or Spanish (Giovanna's and Jackson's kumapie). The English text in the code is the key:
 * `tr("Settings")`, `tr("%s episodes", n)`. A text missing from the Spanish tables shows in English.
 * The choice ("" = the phone's language, "en", "es") is read by [io.github.pedrubik2000.kumapie.data.Settings].
 */
object Tr {
    @Volatile var spanish: Boolean = false
        private set

    fun use(choice: String) {
        spanish = when (choice) {
            "es" -> true
            "en" -> false
            else -> Locale.getDefault().language == "es"
        }
    }

    // ponytail: one map per screen area, merged; add a file + a line here for a new area.
    private val es: Map<String, String> by lazy { esCore + esHome + esPlayer + esSettings + esAdd + esOther + esTv }

    fun of(english: String): String = if (spanish) es[english] ?: english else english
}

/** [english] in the menu language; with [args], a String.format template (%s, %d, %1$s). */
fun tr(english: String, vararg args: Any?): String {
    val t = Tr.of(english)
    return if (args.isEmpty()) t else String.format(t, *args)
}
