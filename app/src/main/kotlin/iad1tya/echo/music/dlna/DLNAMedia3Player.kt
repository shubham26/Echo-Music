package iad1tya.echo.music.dlna

import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Timeline
import com.google.common.util.concurrent.ListenableFuture
import iad1tya.echo.music.models.MediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DLNAMedia3Player(
    looper: Looper,
    private val dlnaManager: DLNAManager
) : SimpleBasePlayer(looper) {

    private val TAG = "DLNAMedia3Player"
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var internalTimeline = Timeline.EMPTY
    private var playlist = mutableListOf<MediaItem>()
    private var currentMediaItemIndex = C.INDEX_UNSET
    private var currentPositionMs = 0L
    private var durationMs = C.TIME_UNSET
    private var isPlayingInternal = false
    private var playWhenReadyInternal = false
    private var playbackStateInternal = Player.STATE_IDLE
    private var shuffleModeEnabledInternal = false
    private var repeatModeInternal = Player.REPEAT_MODE_OFF
    private var shuffledIndices: List<Int> = emptyList()

    private var pollingJob: Job? = null
    private var autoPlayNextJob: Job? = null

    var onResolveUrl: (suspend (MediaItem) -> String?)? = null

    init {
        // Monitor device selection to stop playback if disconnected
        scope.launch {
            dlnaManager.selectedDevice.collectLatest { device ->
                if (device == null) {
                    stop()
                } else {
                    startPolling()
                }
            }
        }
    }

    // Public method to be called by MusicService to sync state
    fun setPlaylist(items: List<MediaItem>, currentIndex: Int, positionMs: Long) {
        playlist.clear()
        playlist.addAll(items)
        currentMediaItemIndex = if (items.isNotEmpty()) currentIndex.coerceIn(0, items.lastIndex) else C.INDEX_UNSET
        currentPositionMs = positionMs

        updateTimeline()

        if (shuffleModeEnabledInternal) {
            updateShuffledIndices()
        }

        // Don't auto-start here, let MusicService call play() or prepare()
    }

    fun syncQueue(items: List<MediaItem>, currentIndex: Int) {
         playlist.clear()
         playlist.addAll(items)
         currentMediaItemIndex = if (items.isNotEmpty()) currentIndex.coerceIn(0, items.lastIndex) else C.INDEX_UNSET
         updateTimeline()
         if (shuffleModeEnabledInternal) {
             updateShuffledIndices()
         }
         invalidateState()
    }

    override fun getState(): SimpleBasePlayer.State {
        return SimpleBasePlayer.State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_STOP,
                        Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_PREPARE,
                        Player.COMMAND_SET_MEDIA_ITEM,
                        Player.COMMAND_SET_MEDIA_ITEMS,
                        Player.COMMAND_CHANGE_MEDIA_ITEMS,
                        Player.COMMAND_SET_SHUFFLE_MODE,
                        Player.COMMAND_SET_REPEAT_MODE,
                        Player.COMMAND_GET_TIMELINE,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SET_VOLUME
                    )
                    .build()
            )
            .setTimeline(internalTimeline)
            .setCurrentMediaItemIndex(currentMediaItemIndex)
            .setPlayWhenReady(playWhenReadyInternal, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackStateInternal)
            .setRepeatMode(repeatModeInternal)
            .setShuffleModeEnabled(shuffleModeEnabledInternal)
            .setContentPositionMs(currentPositionMs)
            .setDurationMs(durationMs)
            .setIsPlaying(isPlayingInternal)
            .build()
    }

    private fun updateTimeline() {
        if (playlist.isEmpty()) {
            internalTimeline = Timeline.EMPTY
        } else {
            internalTimeline = object : Timeline() {
                override fun getWindowCount(): Int = playlist.size

                override fun getWindow(
                    windowIndex: Int,
                    window: Window,
                    defaultPositionProjectionUs: Long
                ): Window {
                    val mediaItem = playlist.getOrNull(windowIndex) ?: MediaItem.EMPTY
                    // We don't know duration of all items, usually.
                    // But for current item we might know it.
                    val duration = if (windowIndex == currentMediaItemIndex && durationMs != C.TIME_UNSET) {
                        durationMs * 1000 // to Micros
                    } else {
                        C.TIME_UNSET
                    }

                    return window.set(
                        windowIndex,
                        mediaItem,
                        null,
                        C.TIME_UNSET,
                        C.TIME_UNSET,
                        C.TIME_UNSET,
                        true,
                        false,
                        null,
                        0,
                        duration,
                        0,
                        0,
                        0
                    )
                }

                override fun getPeriodCount(): Int = playlist.size

                override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
                    val mediaItem = playlist.getOrNull(periodIndex) ?: MediaItem.EMPTY
                    return period.set(
                        periodIndex,
                        periodIndex,
                        0,
                        if (periodIndex == currentMediaItemIndex && durationMs != C.TIME_UNSET) durationMs * 1000 else C.TIME_UNSET,
                        0
                    )
                }

                override fun getIndexOfPeriod(uid: Any): Int {
                    return if (uid is Int) uid else C.INDEX_UNSET
                }

                override fun getUidOfPeriod(periodIndex: Int): Any {
                    return periodIndex
                }
            }
        }
    }

    private fun updateShuffledIndices() {
        if (playlist.isNotEmpty()) {
            shuffledIndices = playlist.indices.shuffled()
        } else {
            shuffledIndices = emptyList()
        }
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playWhenReadyInternal = playWhenReady
        scope.launch {
            if (playWhenReady) {
                if (playbackStateInternal == Player.STATE_IDLE || playbackStateInternal == Player.STATE_ENDED) {
                    prepareAndPlayCurrent()
                } else {
                    dlnaManager.resume()
                }
            } else {
                dlnaManager.pause()
            }
            // Polling will update the actual state
        }
        invalidateState()
        return futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeatModeInternal = repeatMode
        invalidateState()
        return futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        shuffleModeEnabledInternal = shuffleModeEnabled
        if (shuffleModeEnabled) {
            updateShuffledIndices()
        } else {
            shuffledIndices = emptyList()
        }
        invalidateState()
        return futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (playbackStateInternal == Player.STATE_IDLE) {
            playbackStateInternal = Player.STATE_BUFFERING // Transition state
            scope.launch {
                prepareAndPlayCurrent()
            }
        }
        invalidateState()
        return futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playWhenReadyInternal = false
        playbackStateInternal = Player.STATE_IDLE
        isPlayingInternal = false
        scope.launch {
            dlnaManager.stopPlayback()
        }
        stopPolling()
        invalidateState()
        return futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        val index = if (mediaItemIndex == C.INDEX_UNSET) currentMediaItemIndex else mediaItemIndex

        if (index != currentMediaItemIndex) {
            // Change track
            currentMediaItemIndex = index
            currentPositionMs = 0
            scope.launch {
                dlnaManager.stopPlayback()
                prepareAndPlayCurrent()
            }
        } else {
            // Seek in current track
            currentPositionMs = positionMs
            scope.launch {
                dlnaManager.seek(positionMs)
            }
        }

        invalidateState()
        return futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        playlist.clear()
        playlist.addAll(mediaItems)
        updateTimeline()

        if (startIndex != C.INDEX_UNSET) {
            currentMediaItemIndex = startIndex
            currentPositionMs = startPositionMs
        } else {
            currentMediaItemIndex = 0
            currentPositionMs = 0
        }

        if (shuffleModeEnabledInternal) {
            updateShuffledIndices()
        }

        invalidateState()
        return futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(
        index: Int,
        mediaItems: MutableList<MediaItem>
    ): ListenableFuture<*> {
        playlist.addAll(index, mediaItems)
        if (currentMediaItemIndex >= index) {
            currentMediaItemIndex += mediaItems.size
        }
        updateTimeline()
        if (shuffleModeEnabledInternal) {
            updateShuffledIndices()
        }
        invalidateState()
        return futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val count = toIndex - fromIndex
        playlist.subList(fromIndex, toIndex).clear()

        if (currentMediaItemIndex in fromIndex until toIndex) {
            // Removed current item
            currentMediaItemIndex = if (playlist.isEmpty()) C.INDEX_UNSET else fromIndex.coerceAtMost(playlist.lastIndex)
            currentPositionMs = 0
            // Stop playback?
             scope.launch {
                dlnaManager.stopPlayback()
            }
        } else if (currentMediaItemIndex >= toIndex) {
            currentMediaItemIndex -= count
        }

        updateTimeline()
        if (shuffleModeEnabledInternal) {
            updateShuffledIndices()
        }
        invalidateState()
        return futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        scope.launch {
            dlnaManager.setVolume((volume * 100).toInt())
        }
        return futures.immediateVoidFuture()
    }

    private suspend fun prepareAndPlayCurrent() {
        if (currentMediaItemIndex == C.INDEX_UNSET || playlist.isEmpty()) return

        val mediaItem = playlist.getOrNull(currentMediaItemIndex) ?: return

        // Resolve URL if needed (YouTube IDs need resolving)
        val uri = mediaItem.localConfiguration?.uri

        // Very basic check if it's a YouTube ID (no scheme) or valid URL
        if (uri?.scheme == null) {
             // Let the resolveAndPlay handle it or callback
        }

        resolveAndPlay()
    }

    private suspend fun resolveAndPlay() {
        val mediaItem = playlist.getOrNull(currentMediaItemIndex) ?: return

        playbackStateInternal = Player.STATE_BUFFERING
        invalidateState()

        val url = onResolveUrl?.invoke(mediaItem) ?: mediaItem.localConfiguration?.uri.toString()

        val metadata = mediaItem.mediaMetadata
        val title = metadata.title?.toString() ?: "Unknown Title"
        val artist = metadata.artist?.toString() ?: "Unknown Artist"

        if (dlnaManager.playMedia(url, title, artist)) {
             playbackStateInternal = Player.STATE_READY
             isPlayingInternal = true
             startPolling()
        } else {
             // Error
             playbackStateInternal = Player.STATE_IDLE
             isPlayingInternal = false
             // trigger error?
        }
        invalidateState()
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive) {
                val posInfo = dlnaManager.getPositionInfo()
                val transportInfo = dlnaManager.getTransportInfo()

                if (posInfo != null) {
                    currentPositionMs = posInfo.getRelTimeMs()
                    if (posInfo.getTrackDurationMs() > 0) {
                        durationMs = posInfo.getTrackDurationMs()
                    }
                }

                if (transportInfo != null) {
                    when (transportInfo.currentTransportState) {
                        "PLAYING" -> {
                            playbackStateInternal = Player.STATE_READY
                            isPlayingInternal = true
                        }
                        "PAUSED_PLAYBACK" -> {
                            playbackStateInternal = Player.STATE_READY
                            isPlayingInternal = false
                        }
                        "STOPPED", "NO_MEDIA_PRESENT" -> {
                            // Only switch to idle/ended if we were playing before (to avoid initial state issues)
                            if (playbackStateInternal == Player.STATE_READY) {
                                if (currentPositionMs > 0 && durationMs > 0 && (durationMs - currentPositionMs) < 2000) {
                                    // Finished
                                    playbackStateInternal = Player.STATE_ENDED
                                    isPlayingInternal = false
                                    handleAutoNext()
                                } else {
                                     // Just stopped
                                     isPlayingInternal = false
                                }
                            }
                        }
                        "TRANSITIONING" -> {
                            playbackStateInternal = Player.STATE_BUFFERING
                        }
                    }
                }

                updateTimeline() // Update duration in timeline
                invalidateState()
                delay(1000)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun handleAutoNext() {
        if (autoPlayNextJob?.isActive == true) return
        autoPlayNextJob = scope.launch {
            // Logic to move to next item
            var nextIndex = C.INDEX_UNSET

            if (repeatModeInternal == Player.REPEAT_MODE_ONE) {
                nextIndex = currentMediaItemIndex
            } else if (shuffleModeEnabledInternal && shuffledIndices.isNotEmpty()) {
                // Find current index in shuffled list
                val currentShuffledIndex = shuffledIndices.indexOf(currentMediaItemIndex)
                if (currentShuffledIndex != -1 && currentShuffledIndex < shuffledIndices.size - 1) {
                    nextIndex = shuffledIndices[currentShuffledIndex + 1]
                } else if (repeatModeInternal == Player.REPEAT_MODE_ALL) {
                    // Loop back to start of shuffled list
                    nextIndex = shuffledIndices.first()
                }
            } else {
                // Normal sequential playback
                if (currentMediaItemIndex + 1 < playlist.size) {
                    nextIndex = currentMediaItemIndex + 1
                } else if (repeatModeInternal == Player.REPEAT_MODE_ALL && playlist.isNotEmpty()) {
                    nextIndex = 0
                }
            }

            if (nextIndex != C.INDEX_UNSET && nextIndex < playlist.size) {
                currentMediaItemIndex = nextIndex
                currentPositionMs = 0
                resolveAndPlay()
            } else {
                playbackStateInternal = Player.STATE_ENDED
                isPlayingInternal = false
                invalidateState()
            }
        }
    }

    override fun release() {
        super.release()
        scope.launch {
             dlnaManager.stopPlayback()
        }
        stopPolling()
        scope.cancel()
    }
}
