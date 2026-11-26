package io.github.pedrubik2000.kumapie.torrent

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import io.github.pedrubik2000.kumapie.i18n.tr
import java.io.InterruptedIOException
import java.io.RandomAccessFile

class TorrentDataSource(private val context: Context) : BaseDataSource(true) {
    private var stream: TorrentStream? = null
    private var file: RandomAccessFile? = null
    private var uri: Uri? = null
    private var pos = 0L
    private var left = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val ref = TorrentRef.parse(dataSpec.uri.toString()) ?: throw TorrentException(tr("This is not a torrent link."))
        val s = TorrentEngine.open(context, ref)
        stream = s
        if (dataSpec.position > s.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        pos = dataSpec.position
        left = if (dataSpec.length == C.LENGTH_UNSET.toLong()) s.size - pos else minOf(dataSpec.length, s.size - pos)
        s.want(pos)
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val s = stream ?: throw TorrentException(tr("This is not a torrent link."))
        val ready = await(s, minOf(length.toLong(), left).toInt())
        val f = file ?: RandomAccessFile(s.path, "r").also { file = it }
        f.seek(pos)
        val n = f.read(buffer, offset, ready)
        if (n <= 0) return C.RESULT_END_OF_INPUT
        pos += n
        left -= n
        bytesTransferred(n)
        return n
    }

    private fun await(s: TorrentStream, want: Int): Int {
        val since = System.currentTimeMillis()
        while (true) {
            TorrentEngine.check(s)
            s.want(pos)
            val have = s.available(pos)
            if (have > 0) {
                TorrentEngine.waiting = false
                return minOf(have, want.toLong()).toInt()
            }
            val waited = System.currentTimeMillis() - since
            if (waited > SHOW_AFTER_MS) TorrentEngine.waiting = true
            if (waited > TorrentEngine.STALL_MS) {
                val message = tr("The download stalled. Try another source.")
                TorrentEngine.fail(message)
                throw TorrentException(message)
            }
            try { Thread.sleep(POLL_MS) } catch (e: InterruptedException) { throw InterruptedIOException() }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { file?.close() }
        file = null
        if (stream != null) transferEnded()
        stream = null
        uri = null
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = TorrentDataSource(context)
    }

    private companion object {
        const val POLL_MS = 50L
        const val SHOW_AFTER_MS = 400L
    }
}
