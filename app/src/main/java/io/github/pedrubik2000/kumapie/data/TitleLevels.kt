package io.github.pedrubik2000.kumapie.data

import android.content.Context
import io.github.pedrubik2000.kumapie.BuildConfig
import io.github.pedrubik2000.kumapie.lang.Jiten
import io.github.pedrubik2000.kumapie.lang.JitenApi
import io.github.pedrubik2000.kumapie.lang.JitenHttp
import io.github.pedrubik2000.kumapie.lang.JitenPaced
import io.github.pedrubik2000.kumapie.lang.JitenQueue
import io.github.pedrubik2000.kumapie.lang.JitenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object TitleLevels {
    private var queue: JitenQueue? = null
    private val started = AtomicBoolean(false)

    @Synchronized
    fun of(context: Context): JitenQueue = queue ?: build(context.applicationContext).also { queue = it }

    private fun build(app: Context): JitenQueue {
        Jiten.appVersion = BuildConfig.VERSION_NAME
        val prefs = Settings(app).prefs
        return JitenQueue(JitenStore(File(app.filesDir, "jiten.json")), JitenApi(JitenPaced(JitenHttp)),
            resolve = { AniList.idOfKitsu(it, prefs) }, idle = { !Playing.on })
    }

    fun start(context: Context) {
        if (started.compareAndSet(false, true)) CoroutineScope(Dispatchers.IO).launch { of(context).run() }
    }
}
