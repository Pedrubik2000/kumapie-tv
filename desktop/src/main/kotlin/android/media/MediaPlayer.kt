package android.media

import io.github.pedrubik2000.kumapie.desktop.Vlc

/** Desktop stand-in for the calls :core's WordAudio makes: one short clip (a word) played by libVLC. */
class MediaPlayer {
    private val player = Vlc.factory.mediaPlayers().newMediaPlayer()
    private var url = ""
    private var prepared: ((MediaPlayer) -> Unit)? = null

    fun setAudioAttributes(a: AudioAttributes) {}
    fun setOnPreparedListener(l: (MediaPlayer) -> Unit) { prepared = l }
    fun setOnErrorListener(l: (MediaPlayer, Int, Int) -> Boolean) {}
    fun setDataSource(url: String) { this.url = url }
    fun prepareAsync() { prepared?.invoke(this) }
    fun start() { player.media().play(url) }
    fun release() { player.controls().stop(); player.release() }
}

class AudioAttributes {
    class Builder {
        fun setUsage(u: Int) = this
        fun setContentType(c: Int) = this
        fun build() = AudioAttributes()
    }

    companion object {
        const val USAGE_MEDIA = 1
        const val CONTENT_TYPE_SPEECH = 1
    }
}
