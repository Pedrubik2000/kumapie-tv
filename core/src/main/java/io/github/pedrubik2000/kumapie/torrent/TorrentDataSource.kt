package io.github.pedrubik2000.kumapie.torrent

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import io.github.pedrubik2000.kumapie.i18n.tr
import java.net.SocketTimeoutException

class TorrentDataSource(private val context: Context, private val owner: Any) : BaseDataSource(true) {
    private val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(CONNECT_MS).setReadTimeoutMs(TorrentEngine.STALL_MS.toInt()).createDataSource()
    private var uri: Uri? = null
    private var firstBytes = false
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val ref = TorrentRef.parse(dataSpec.uri.toString()) ?: throw TorrentException(tr("This is not a torrent link."))
        val s = TorrentEngine.open(context, ref, owner)
        if (dataSpec.position > s.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        val left = stalled { http.open(dataSpec.buildUpon().setUri(s.url).build()) }
        transferStarted(dataSpec)
        opened = true
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = stalled { http.read(buffer, offset, length) }
        if (n == C.RESULT_END_OF_INPUT) return n
        if (!firstBytes) {
            firstBytes = true
            TorrentEngine.waiting = false
            TorrentEngine.recovered()
        }
        bytesTransferred(n)
        return n
    }

    private fun <T> stalled(block: () -> T): T = try {
        block()
    } catch (e: HttpDataSource.HttpDataSourceException) {
        if (e.cause !is SocketTimeoutException) throw e
        val message = tr("The download stalled. Try another source.")
        TorrentEngine.fail(message, TorrentFault.STALLED)
        throw TorrentException(message, TorrentFault.STALLED)
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { http.close() }
        if (opened) transferEnded()
        opened = false
        uri = null
    }

    class Factory(private val context: Context, private val owner: Any) : DataSource.Factory {
        override fun createDataSource(): DataSource = TorrentDataSource(context, owner)
    }

    private companion object {
        const val CONNECT_MS = 5000
    }
}
