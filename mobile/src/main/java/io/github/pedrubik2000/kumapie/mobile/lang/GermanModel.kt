package io.github.pedrubik2000.kumapie.mobile.lang

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * spaCy's large German model (de_core_news_lg 3.8.0, the one morphs uses on the PC; ~610 MB unpacked). It is
 * downloaded once from spaCy's GitHub releases into the app's external folder, never part of the APK. A test
 * build can also get it pushed there over adb (`models/de_core_news_lg/` with config.cfg inside).
 */
class GermanModel(private val context: Context) {
    private val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
    val dir = File(base, NAME)
    val isReady: Boolean get() = File(dir, "config.cfg").exists()
    private val work get() = WorkManager.getInstance(context)

    fun download() {
        val request = OneTimeWorkRequestBuilder<ModelWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }

    /** Download progress, a failure message, or null when no download is running. */
    fun state(): Flow<String?> = AssetWorker.state(work, WORK)

    companion object {
        const val NAME = "de_core_news_lg"
        const val VERSION = "3.8.0"
        const val URL = "https://github.com/explosion/spacy-models/releases/download/$NAME-$VERSION/$NAME-$VERSION-py3-none-any.whl"
        private const val WORK = "german-model"
    }
}

/** Downloads the model wheel, unpacks the model folder from it and deletes the wheel. */
class ModelWorker(context: Context, params: WorkerParameters) : AssetWorker(context, params) {
    private val model = GermanModel(context)
    override val what = "the German model"
    override val notificationId = 999

    override suspend fun run() {
        if (model.isReady) return
        val base = model.dir.parentFile!!.apply { mkdirs() }
        val wheel = File(base, "${GermanModel.NAME}.whl.part")
        fetch(GermanModel.URL, wheel)

        // The wheel holds <name>/<name>-<version>/... : that inner folder is what spaCy loads.
        report("Unpacking…", 0.95f)
        val prefix = "${GermanModel.NAME}/${GermanModel.NAME}-${GermanModel.VERSION}/"
        val tmp = File(base, "${GermanModel.NAME}.tmp").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(wheel.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                val out = File(tmp, entry.name.removePrefix(prefix))
                if (!out.canonicalPath.startsWith(tmp.canonicalPath)) continue
                out.parentFile?.mkdirs()
                out.outputStream().use { zip.copyTo(it) }
            }
        }
        if (!File(tmp, "config.cfg").exists()) throw IOException("the download is not a spaCy model")
        model.dir.deleteRecursively()
        if (!tmp.renameTo(model.dir)) throw IOException("couldn't move the model into place")
        wheel.delete()
    }
}
