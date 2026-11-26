package io.github.pedrubik2000.kumapie.torrent

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

fun torrentAware(context: Context): DataSource.Factory =
    DefaultDataSource.Factory(context, DataSource.Factory { Router(DefaultHttpDataSource.Factory().createDataSource(), TorrentDataSource(context)) })

private class Router(private val http: DataSource, private val torrent: DataSource) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        http.addTransferListener(transferListener)
        torrent.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val next = if (dataSpec.uri.scheme == TorrentRef.SCHEME) torrent else http
        active = next
        return next.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = (active ?: throw IOException("not open")).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        active?.close()
        active = null
    }
}
