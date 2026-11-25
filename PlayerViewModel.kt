package com.nanotune.player

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RepeatMode { ALBUM, ONE, OFF }

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val settingsRepo = SettingsRepository(context)

    val settings: StateFlow<AppSettings> = settingsRepo.settings
    val fxState: StateFlow<AudioFxState> = AudioFxStore.state

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private val pendingControllerActions = ArrayDeque<(MediaController) -> Unit>()

    private var positionJob: Job? = null
    private var artworkJob: Job? = null
    private var backdropJob: Job? = null
    private var scanJob: Job? = null
    private var cacheLoadAttempted = false

    @Volatile
    private var playbackGenerationId: Long = 0L

    @Volatile
    private var backdropGenerationId: Long = 0L

    private val _allTracks = MutableStateFlow<List<TrackItem>>(emptyList())
    val allTracks: StateFlow<List<TrackItem>> = _allTracks.asStateFlow()

    private val _albums = MutableStateFlow<List<AlbumGroup>>(emptyList())
    val albums: StateFlow<List<AlbumGroup>> = _albums.asStateFlow()

    private val _activeAlbumFilter = MutableStateFlow("")
    val activeAlbumFilter: StateFlow<String> = _activeAlbumFilter.asStateFlow()

    val displayedTracks: StateFlow<List<TrackItem>> =
        combine(_allTracks, _activeAlbumFilter) { tracks, albumFilter ->
            if (albumFilter.isEmpty()) {
                tracks
            } else {
                tracks.filter { it.album == albumFilter }
                    .sortedWith(
                        compareBy<TrackItem>(
                            { if (it.trackNo > 0) it.trackNo else 999 },
                            { it.title.lowercase() }
                        )
                    )
                    .mapIndexed { idx, item ->
                        item.copy(displayNum = if (item.trackNo > 0) item.trackNo else idx + 1)
                    }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _activeTrack = MutableStateFlow<TrackItem?>(null)
    val activeTrack: StateFlow<TrackItem?> = _activeTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.ALBUM)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _activeCover = MutableStateFlow<Bitmap?>(null)
    val activeCover: StateFlow<Bitmap?> = _activeCover.asStateFlow()

    private val _processedBackdrop = MutableStateFlow<Bitmap?>(null)
    val processedBackdrop: StateFlow<Bitmap?> = _processedBackdrop.asStateFlow()

    private val _themeColors = MutableStateFlow(ColorExtractor.DefaultTheme)
    val themeColors: StateFlow<ThemeColors> = _themeColors.asStateFlow()

    private val emptyLyrics = ParsedLyrics(
        isSynced = false,
        hasElrcWords = false,
        lines = emptyList(),
        rawText = "",
        sourceLabel = "No Lyrics Found"
    )

    private val _parsedLyrics = MutableStateFlow(emptyLyrics)
    val parsedLyrics: StateFlow<ParsedLyrics> = _parsedLyrics.asStateFlow()

    private var queueTracks: List<TrackItem> = emptyList()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val mc = mediaController
            val effective = if (mc != null && mc.playbackState == Player.STATE_BUFFERING) {
                mc.playWhenReady
            } else {
                isPlaying
            }
            _isPlaying.value = effective
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val mc = mediaController ?: return
            if (mc.playbackState == Player.STATE_BUFFERING || mc.playbackState == Player.STATE_READY) {
                _isPlaying.value = playWhenReady
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncCurrentTrackFromPlayer()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            mediaController?.let { mc ->
                if (mc.duration > 0L) {
                    _durationMs.value = mc.duration
                }
                if (playbackState == Player.STATE_ENDED) {
                    _isPlaying.value = false
                }
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffleEnabled.value = shuffleModeEnabled
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_OFF -> RepeatMode.OFF
                else -> RepeatMode.ALBUM
            }
        }
    }

    init {
        connectService()
        loadCachedLibraryIfNeeded()
        viewModelScope.launch {
            combine(
                _activeCover,
                _themeColors,
                settings.map { it.blurRadiusDp }.distinctUntilChanged()
            ) { bmp, colors, radiusDp ->
                Triple(bmp, colors, radiusDp)
            }.collect { (bmp, colors, radiusDp) ->
                rebuildProcessedBackdrop(bmp, colors.primary, radiusDp)
            }
        }
    }

    private fun connectService() {
        if (controllerFuture != null) return
        val sessionToken = SessionToken(context, ComponentName(context, NanoTunePlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching {
                    val mc = future.get()
                    mediaController = mc
                    mc.addListener(playerListener)
                    syncPlayerRepeatMode(mc)
                    mc.shuffleModeEnabled = _shuffleEnabled.value
                    syncCurrentTrackFromPlayer()
                    startPositionLoop()
                    while (pendingControllerActions.isNotEmpty()) {
                        pendingControllerActions.removeFirst().invoke(mc)
                    }
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    private fun withController(action: (MediaController) -> Unit) {
        val mc = mediaController
        if (mc != null) {
            action(mc)
        } else {
            pendingControllerActions.addLast(action)
        }
    }

    private fun startPositionLoop() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (isActive) {
                val mc = mediaController
                if (mc != null) {
                    _positionMs.value = mc.currentPosition.coerceAtLeast(0L)
                    if (mc.duration > 0L) {
                        _durationMs.value = mc.duration
                    }
                    if (mc.playbackState != Player.STATE_BUFFERING) {
                        _isPlaying.value = mc.isPlaying
                    }
                }
                delay(32L)
            }
        }
    }

    fun loadCachedLibraryIfNeeded() {
        if (cacheLoadAttempted) return
        cacheLoadAttempted = true
        viewModelScope.launch {
            val cached = MusicScanner.loadCachedLibrary(context)
            if (cached != null && _allTracks.value.isEmpty()) {
                _allTracks.value = cached.first
                _albums.value = cached.second
                NanoLibraryBrowseStore.updateLibrary(cached.first, cached.second)
                if (queueTracks.isEmpty()) {
                    queueTracks = cached.first
                }
                syncCurrentTrackFromPlayer()
            }
        }
    }

    fun scanLibrary(forceFullRescan: Boolean = false) {
        if (scanJob?.isActive == true) {
            if (!forceFullRescan) return
            scanJob?.cancel()
        }
        scanJob = viewModelScope.launch {
            if (forceFullRescan || _allTracks.value.isEmpty()) {
                _isScanning.value = true
            }
            val (tracks, groups) = MusicScanner.scanLibrary(
                context = context,
                settings = settings.value,
                forceFullRescan = forceFullRescan
            )
            _allTracks.value = tracks
            _albums.value = groups
            NanoLibraryBrowseStore.updateLibrary(tracks, groups)

            if (queueTracks.isEmpty()) {
                queueTracks = tracks
            } else {
                val cur = _activeTrack.value
                if (cur != null) {
                    tracks.firstOrNull { it.uri == cur.uri }?.let { _activeTrack.value = it }
                }
            }
            _isScanning.value = false
        }
    }

    fun selectAlbumFilter(albumName: String) {
        _activeAlbumFilter.value = albumName
    }

    fun clearAlbumFilter() {
        _activeAlbumFilter.value = ""
    }

    fun playFromList(index: Int, shuffle: Boolean = _shuffleEnabled.value) {
        val list = displayedTracks.value
        if (list.isEmpty()) return
        val safeIdx = index.coerceIn(0, list.lastIndex)
        val chosenTrack = list[safeIdx]

        queueTracks = list
        _shuffleEnabled.value = shuffle

        onTrackActivated(chosenTrack)

        withController { mc ->
            mc.shuffleModeEnabled = shuffle
            syncPlayerRepeatMode(mc)
            mc.setMediaItems(list.map { it.toMediaItem() }, safeIdx, 0L)
            mc.prepare()
            mc.play()
        }
    }

    fun playAllFromStart() {
        playFromList(0, shuffle = _shuffleEnabled.value)
    }

    fun playShuffled() {
        val list = displayedTracks.value
        if (list.isEmpty()) return
        val startIdx = list.indices.random()
        playFromList(startIdx, shuffle = true)
    }

    fun enqueuePlayNext(track: TrackItem) {
        withController { mc ->
            if (queueTracks.isEmpty() || mc.mediaItemCount == 0) {
                queueTracks = listOf(track)
                onTrackActivated(track)
                mc.setMediaItem(track.toMediaItem())
                mc.prepare()
                mc.play()
                return@withController
            }
            val insertIdx = (mc.currentMediaItemIndex + 1).coerceAtMost(mc.mediaItemCount)
            val mutable = queueTracks.toMutableList()
            mutable.add(insertIdx.coerceAtMost(mutable.size), track)
            queueTracks = mutable
            mc.addMediaItem(insertIdx, track.toMediaItem())
        }
    }

    fun togglePlayPause() {
        withController { mc ->
            if (mc.mediaItemCount == 0) {
                val fallback = displayedTracks.value
                if (fallback.isNotEmpty()) playFromList(0)
                return@withController
            }
            if (mc.isPlaying) {
                _isPlaying.value = false
                mc.pause()
            } else {
                _isPlaying.value = true
                mc.play()
            }
        }
    }

    fun seekToMs(ms: Long) {
        val clamped = ms.coerceAtLeast(0L)
        _positionMs.value = clamped
        withController { mc ->
            mc.seekTo(clamped)
        }
    }

    fun skipNext() {
        withController { mc ->
            if (mc.mediaItemCount == 0) {
                val fallback = displayedTracks.value
                if (fallback.isNotEmpty()) playFromList(0)
                return@withController
            }
            if (mc.hasNextMediaItem()) {
                mc.seekToNextMediaItem()
                if (!mc.isPlaying) mc.play()
            } else if (queueTracks.isNotEmpty()) {
                mc.seekToDefaultPosition(0)
                mc.play()
            }
        }
    }

    fun skipPrevious() {
        withController { mc ->
            if (mc.mediaItemCount == 0) {
                val fallback = displayedTracks.value
                if (fallback.isNotEmpty()) playFromList(fallback.lastIndex)
                return@withController
            }
            if (mc.currentPosition > 3000L) {
                seekToMs(0L)
            } else if (mc.hasPreviousMediaItem()) {
                mc.seekToPreviousMediaItem()
                if (!mc.isPlaying) mc.play()
            } else if (queueTracks.isNotEmpty()) {
                mc.seekToDefaultPosition(queueTracks.lastIndex)
                mc.play()
            }
        }
    }

    fun toggleShuffle() {
        val next = !_shuffleEnabled.value
        _shuffleEnabled.value = next
        withController { mc ->
            mc.shuffleModeEnabled = next
        }
    }

    fun cycleRepeatMode() {
        val next = when (_repeatMode.value) {
            RepeatMode.ALBUM -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
            RepeatMode.OFF -> RepeatMode.ALBUM
        }
        _repeatMode.value = next
        withController { mc ->
            syncPlayerRepeatMode(mc)
        }
    }

    private fun syncPlayerRepeatMode(mc: MediaController) {
        mc.repeatMode = when (_repeatMode.value) {
            RepeatMode.ALBUM -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        }
    }

    private fun syncCurrentTrackFromPlayer() {
        val mc = mediaController ?: return
        val mediaId = mc.currentMediaItem?.mediaId ?: return
        val idx = mc.currentMediaItemIndex
        val matched = queueTracks.firstOrNull { it.uri.toString() == mediaId }
            ?: queueTracks.getOrNull(idx)?.takeIf { it.uri.toString() == mediaId }
            ?: _allTracks.value.firstOrNull { it.uri.toString() == mediaId }
        if (matched != null && matched.uri != _activeTrack.value?.uri) {
            onTrackActivated(matched)
        }
    }

    private fun onTrackActivated(track: TrackItem) {
        val genId = ++playbackGenerationId

        // Synchronously clear stale state immediately before background IO work
        _activeTrack.value = track
        _durationMs.value = track.durationMs
        _positionMs.value = 0L
        _parsedLyrics.value = emptyLyrics

        val memCover = ColorExtractor.getCachedLargeCover(track.coverHash.ifEmpty { track.uri.toString() })
        _activeCover.value = memCover
        _themeColors.value = if (memCover != null) {
            ColorExtractor.extractThemeColors(memCover)
        } else {
            ColorExtractor.DefaultTheme
        }

        artworkJob?.cancel()
        artworkJob = viewModelScope.launch {
            val (bmp, colors) = ColorExtractor.loadTrackArtwork(context, track, preferLarge = true)
            if (genId != playbackGenerationId) return@launch

            _activeCover.value = bmp
            _themeColors.value = colors

            val lyrics = LyricsParser.loadLyricsForTrack(context, track)
            if (genId != playbackGenerationId) return@launch
            _parsedLyrics.value = lyrics

            val artBytes = withContext(Dispatchers.Default) {
                bmp?.let { bitmap -> ColorExtractor.compressWatchArtwork(bitmap) }
            }
            if (genId != playbackGenerationId) return@launch

            if (artBytes != null && artBytes.isNotEmpty()) {
                withController { mc ->
                    if (genId != playbackGenerationId) return@withController
                    val idx = mc.currentMediaItemIndex
                    if (idx in 0 until mc.mediaItemCount) {
                        val currentItem = mc.getMediaItemAt(idx)
                        if (currentItem.mediaId == track.uri.toString() && currentItem.mediaMetadata.artworkData == null) {
                            val updatedMetadata = currentItem.mediaMetadata.buildUpon()
                                .setTitle(track.title)
                                .setArtist(track.artist)
                                .setAlbumTitle(track.album)
                                .setTrackNumber(track.trackNo.takeIf { it > 0 })
                                .setDurationMs(track.durationMs.takeIf { it >= 0L })
                                .setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                .build()
                            val updatedItem = currentItem.buildUpon()
                                .setMediaMetadata(updatedMetadata)
                                .build()
                            mc.replaceMediaItem(idx, updatedItem)
                        }
                    }
                }
            }
        }
    }

    private fun rebuildProcessedBackdrop(
        coverBmp: Bitmap?,
        fallbackAccent: Color,
        blurRadiusDp: Float
    ) {
        val bgGen = ++backdropGenerationId
        backdropJob?.cancel()
        backdropJob = viewModelScope.launch {
            val processed = withContext(Dispatchers.Default) {
                val src = coverBmp ?: ColorExtractor.buildFallbackCoverBitmap(fallbackAccent, 360)
                ColorExtractor.createProcessedBackdrop(src, blurRadiusDp)
            }
            if (bgGen != backdropGenerationId) return@launch
            _processedBackdrop.value = processed
        }
    }

    fun saveEditedLyrics(rawText: String, onResult: (Result<String>) -> Unit) {
        val track = _activeTrack.value ?: return
        val genId = playbackGenerationId
        viewModelScope.launch {
            val result = LyricsParser.saveLrcForTrack(context, track, rawText)
            if (result.isSuccess) {
                val reloaded = LyricsParser.loadLyricsForTrack(context, track)
                if (genId == playbackGenerationId && _activeTrack.value?.uri == track.uri) {
                    _parsedLyrics.value = reloaded
                }
                val updatedList = _allTracks.value.map { item ->
                    if (item.uri == track.uri) {
                        item.copy(
                            hasLyrics = rawText.isNotBlank(),
                            lyricsSource = result.getOrDefault("Saved")
                        )
                    } else {
                        item
                    }
                }
                _allTracks.value = updatedList
                NanoLibraryBrowseStore.updateLibrary(updatedList, _albums.value)
                queueTracks = queueTracks.map { item ->
                    updatedList.firstOrNull { it.uri == item.uri } ?: item
                }
                if (_activeTrack.value?.uri == track.uri) {
                    _activeTrack.value = updatedList.firstOrNull { it.uri == track.uri }
                }
            }
            onResult(result)
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val prevRecursive = settings.value.recursiveScan
        settingsRepo.update(transform)
        if (settings.value.recursiveScan != prevRecursive) {
            scanLibrary(forceFullRescan = false)
        }
    }

    fun addMusicFolder(folderUriStr: String) {
        settingsRepo.update { cur ->
            if (folderUriStr in cur.musicFolders) {
                cur.copy(hasCompletedOnboarding = true)
            } else {
                cur.copy(
                    musicFolders = cur.musicFolders + folderUriStr,
                    hasCompletedOnboarding = true
                )
            }
        }
        scanLibrary(forceFullRescan = false)
    }

    fun removeMusicFolder(folderUriStr: String) {
        settingsRepo.update { cur ->
            cur.copy(musicFolders = cur.musicFolders.filterNot { it == folderUriStr })
        }
        scanLibrary(forceFullRescan = false)
    }

    fun completeOnboarding() {
        settingsRepo.update { it.copy(hasCompletedOnboarding = true) }
        scanLibrary(forceFullRescan = false)
    }

    private fun TrackItem.toMediaItem(): MediaItem {
        val artUri = if (albumId > 0L) {
            Uri.parse("content://media/external/audio/albumart/$albumId")
        } else {
            null
        }

        val extras = Bundle().apply {
            putString(NanoTunePlaybackService.EXTRA_FORMAT_LABEL, formatLabel)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setTrackNumber(trackNo.takeIf { it > 0 })
            .setDurationMs(durationMs.takeIf { it >= 0L })
            .setArtworkUri(artUri)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    override fun onCleared() {
        positionJob?.cancel()
        artworkJob?.cancel()
        backdropJob?.cancel()
        scanJob?.cancel()
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
        super.onCleared()
    }
}