package io.github.sakuraisland

import android.content.Context
import android.media.MediaMetadata
import android.media.session.*
import android.os.Handler

/** Event-driven: callbacks are released with the status-bar host. */
class MediaSource(context: Context, private val handler: Handler, private val changed: (MediaController?) -> Unit) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val callbacks = linkedMapOf<MediaController, MediaController.Callback>()
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers -> Diagnostics.guard("media sessions") { bind(controllers.orEmpty()) } }
    private fun update() { changed(callbacks.keys.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        ?: callbacks.keys.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }) }
    private fun bind(controllers: List<MediaController>) {
        callbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }; callbacks.clear()
        controllers.take(8).forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) { Diagnostics.guard("media metadata") { update() } }
                override fun onPlaybackStateChanged(state: PlaybackState?) { Diagnostics.guard("media state") { update() } }
                override fun onSessionDestroyed() { Diagnostics.guard("media destroyed") { callbacks.remove(c)?.let { c.unregisterCallback(it) }; update() } }
            }
            callbacks[c] = cb; c.registerCallback(cb, handler)
        }
        update()
    }
    fun start() { Diagnostics.guard("media access") {
        manager.addOnActiveSessionsChangedListener(listener, null, handler)
        bind(manager.getActiveSessions(null)); Diagnostics.record("MediaSessionManager listener active")
    } }
    fun stop() { Diagnostics.guard("media cleanup") {
        manager.removeOnActiveSessionsChangedListener(listener)
        callbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }; callbacks.clear()
    } }
}
