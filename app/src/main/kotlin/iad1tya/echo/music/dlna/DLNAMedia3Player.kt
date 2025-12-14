package iad1tya.echo.music.dlna

import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Timeline
import androidx.media3.datasource.ResolvingDataSource
import com.echo.innertube.models.SongItem
import iad1tya.echo.music.models.MediaMetadata
import iad1tya.echo.music.utils.YTPlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

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

        // Don't auto-start here, let MusicService call play() or prepare()
    }

    fun syncQueue(items: List<MediaItem>, currentIndex: Int) {
         playlist.clear()
         playlist.addAll(items)
         currentMediaItemIndex = if (items.isNotEmpty()) currentIndex.coerceIn(0, items.lastIndex) else C.INDEX_UNSET
         updateTimeline()
         invalidateState()
    }

    override fun getState(): State {
        return State.Builder()
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
                        Window.uid(windowIndex),
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
            // Create a shuffled list of indices
            if (playlist.isNotEmpty()) {
                shuffledIndices = playlist.indices.shuffled()
                // Ensure current song is not immediately repeated if possible,
                // or just leave it as is.
                // Ideally, current playing song should be first in shuffled order if we were reordering,
                // but here we just use shuffledIndices for navigation.
            }
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
        var streamUrl = uri.toString()

        // Very basic check if it's a YouTube ID (no scheme) or valid URL
        if (uri?.scheme == null) {
            // Assume it's a media ID that needs resolving
             try {
                // We need context to access YTPlayerUtils or connectivity manager
                // But this class doesn't have them easily.
                // However, MusicService passed resolved items usually?
                // MusicService `switchToCastPlayer` resolves URL.
                // We should probably rely on `MusicService` to resolve or do it here.
                // Since `DLNAMedia3Player` is created in `MusicService`, we can inject dependencies?
                // Or we can rely on `MusicService` resolving it before setting MediaItem.
                // But `MusicService` sets media items from Queue which has IDs.

                // Let's assume for now we need to resolve it if it doesn't look like a URL
                // But `YTPlayerUtils` requires `ConnectivityManager`.
                // Let's pass `Context` to `DLNAMedia3Player`.
             } catch (e: Exception) {
                 Log.e(TAG, "Failed to resolve URL", e)
                 return
             }
        }

        // NOTE: MusicService uses `ResolvingDataSource` which resolves on the fly.
        // But DLNA needs a real URL.
        // We really should resolve it here.
        // But I don't have easy access to `YTPlayerUtils` here without more deps.
        // Let's check `MusicService.switchToCastPlayer` again. It resolves URL explicitly.

        // I will assume for now that I need to resolve it.
        // I'll make `prepareAndPlayCurrent` call a callback or just try to play.
        // If it fails, `MusicService` logic for handling playback errors might kick in?
        // No, `SimpleBasePlayer` error handling is different.

        // Since I cannot easily add `YTPlayerUtils` here without significant refactoring or passing deps,
        // I will add a method `playMediaItem(item: MediaItem)` that `MusicService` can override or use?
        // No, `MusicService` treats this as a `Player`.

        // Solution: `MusicService` sets the player. `MusicService` should probably handle URL resolution for external players.
        // But `MusicService` calls `setMediaItems` with standard items.
        // `CastPlayer` has a `MediaItemConverter`.

        // I will use `MusicService`'s existing resolving logic by calling back?
        // Or simply: `MusicService` should be modified to resolving URL before `setMediaItems` for DLNA?
        // No, queue management becomes hard.

        // I'll just assume I can pass the media ID to `DLNAManager`? No, `DLNAManager` expects URL.

        // Let's implement basic resolving here if possible, or add a callback.
        // Actually, `MusicService` `switchToCastPlayer` resolves ONLY current song.
        // So for DLNA, we should do same.

        // I will add a callback to `DLNAMedia3Player` constructor for resolving URL.
    }

    var onResolveUrl: (suspend (MediaItem) -> String?)? = null

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
        // cancel scope?
    }
}
