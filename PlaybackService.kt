package com.nanotune.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object NanoLibraryBrowseStore {
    @Volatile
    var tracks: List<TrackItem> = emptyList()
        private set

    @Volatile
    var albums: List<AlbumGroup> = emptyList()
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun updateLibrary(newTracks: List<TrackItem>, newAlbums: List<AlbumGroup>) {
        tracks = newTracks
        albums = newAlbums
        listeners.forEach { runCatching { it.invoke() } }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun findTrackByIdOrUri(mediaId: String): TrackItem? {
        return tracks.firstOrNull { it.uri.toString() == mediaId || it.id.toString() == mediaId }
    }
}

@OptIn(UnstableApi::class)
class NanoTunePlaybackService : MediaLibraryService() {

    companion object {
        const val ROOT_ID = "root"
        const val ALL_TRACKS_ID = "all_tracks"
        const val ALBUMS_ID = "albums"
        const val ALBUM_PREFIX = "album:"
        const val EXTRA_ALBUM_CONTEXT = "nanotune_album_context"
        const val EXTRA_FORMAT_LABEL = "nanotune_format"
    }

    private var exoPlayer: ExoPlayer? = null
    private var forwardingPlayer: Player? = null
    private var mediaLibrarySession: MediaLibrarySession? = null
    private val fxProcessor = NanoTuneFxAudioProcessor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var artworkPushJob: Job? = null

    private val libraryUpdateListener: () -> Unit = {
        mainHandler.post {
            mediaLibrarySession?.let { session ->
                session.notifyChildrenChanged(ROOT_ID, 2, null)
                session.notifyChildrenChanged(ALL_TRACKS_ID, NanoLibraryBrowseStore.tracks.size, null)
                session.notifyChildrenChanged(ALBUMS_ID, NanoLibraryBrowseStore.albums.size, null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(false)
                    .setAudioProcessors(arrayOf(fxProcessor))
                    .build()
            }
        }

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exo = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ALL
                playbackParameters = AudioFxStore.state.value.toPlaybackParameters()
            }

        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem != null) {
                    pushWatchArtworkIfNeeded(exo, mediaItem)
                }
            }
        })

        val exposedPlayer = object : ForwardingPlayer(exo) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(COMMAND_PLAY_PAUSE)
                    .add(COMMAND_SEEK_TO_NEXT)
                    .add(COMMAND_SEEK_TO_PREVIOUS)
                    .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .add(COMMAND_SEEK_FORWARD)
                    .add(COMMAND_SEEK_BACK)
                    .add(COMMAND_SET_SHUFFLE_MODE)
                    .add(COMMAND_SET_REPEAT_MODE)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    COMMAND_PLAY_PAUSE,
                    COMMAND_SEEK_TO_NEXT,
                    COMMAND_SEEK_TO_PREVIOUS,
                    COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    COMMAND_SEEK_FORWARD,
                    COMMAND_SEEK_BACK,
                    COMMAND_SET_SHUFFLE_MODE,
                    COMMAND_SET_REPEAT_MODE -> true
                    else -> super.isCommandAvailable(command)
                }
            }
        }

        AudioFxStore.onFxChangedListener = { fx ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                exo.playbackParameters = fx.toPlaybackParameters()
            } else {
                mainHandler.post { exo.playbackParameters = fx.toPlaybackParameters() }
            }
        }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        exoPlayer = exo
        forwardingPlayer = exposedPlayer
        mediaLibrarySession = MediaLibrarySession.Builder(this, exposedPlayer, LibrarySessionCallback())
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        NanoLibraryBrowseStore.addListener(libraryUpdateListener)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = exoPlayer
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        NanoLibraryBrowseStore.removeListener(libraryUpdateListener)
        artworkPushJob?.cancel()
        serviceScope.cancel()
        AudioFxStore.onFxChangedListener = null
        mediaLibrarySession?.run {
            exoPlayer?.release()
            release()
        }
        mediaLibrarySession = null
        forwardingPlayer = null
        exoPlayer = null
        super.onDestroy()
    }

    private fun pushWatchArtworkIfNeeded(exo: ExoPlayer, currentItem: MediaItem) {
        if (currentItem.mediaMetadata.artworkData != null) return
        val mediaId = currentItem.mediaId
        val track = NanoLibraryBrowseStore.findTrackByIdOrUri(mediaId) ?: return

        artworkPushJob?.cancel()
        artworkPushJob = serviceScope.launch {
            val artBytes = withContext(Dispatchers.IO) {
                val (bmp, _) = ColorExtractor.loadTrackArtwork(this@NanoTunePlaybackService, track, preferLarge = true)
                bmp?.let { ColorExtractor.compressWatchArtwork(it) }
            }
            if (artBytes != null && artBytes.isNotEmpty()) {
                val idx = exo.currentMediaItemIndex
                if (idx in 0 until exo.mediaItemCount) {
                    val itemNow = exo.getMediaItemAt(idx)
                    if (itemNow.mediaId == mediaId && itemNow.mediaMetadata.artworkData == null) {
                        val updatedMeta = itemNow.mediaMetadata.buildUpon()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .setAlbumTitle(track.album)
                            .setTrackNumber(track.trackNo.takeIf { it > 0 })
                            .setDurationMs(track.durationMs.takeIf { it >= 0L })
                            .setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                            .build()
                        val updated = itemNow.buildUpon().setMediaMetadata(updatedMeta).build()
                        exo.replaceMediaItem(idx, updated)
                    }
                }
            }
        }
    }

    private suspend fun ensureLibraryLoaded() {
        if (NanoLibraryBrowseStore.tracks.isNotEmpty()) return
        val cached = MusicScanner.loadCachedLibrary(this@NanoTunePlaybackService)
        if (cached != null) {
            NanoLibraryBrowseStore.updateLibrary(cached.first, cached.second)
        }
    }

    private fun buildRootItem(): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle("NanoTune")
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()
        return MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(meta)
            .build()
    }

    private fun buildFolderItem(id: String, title: String, subtitle: String, mediaType: Int): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(meta)
            .build()
    }

    private fun buildBrowseTrackItem(track: TrackItem, albumContext: String? = null): MediaItem {
        val extras = Bundle().apply {
            if (!albumContext.isNullOrEmpty()) {
                putString(EXTRA_ALBUM_CONTEXT, albumContext)
            }
        }
        val meta = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setTrackNumber(track.trackNo.takeIf { it > 0 })
            .setDurationMs(track.durationMs.takeIf { it >= 0L })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(track.uri.toString())
            .setUri(track.uri)
            .setMediaMetadata(meta)
            .build()
    }

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            val playerCommands = base.availablePlayerCommands.buildUpon()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_FORWARD)
                .add(Player.COMMAND_SEEK_BACK)
                .add(Player.COMMAND_SET_SHUFFLE_MODE)
                .add(Player.COMMAND_SET_REPEAT_MODE)
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_METADATA)
                .add(Player.COMMAND_GET_TIMELINE)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(base.availableSessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(LibraryResult.ofItem(buildRootItem(), params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch(Dispatchers.IO) {
                ensureLibraryLoaded()
                val tracks = NanoLibraryBrowseStore.tracks
                val albums = NanoLibraryBrowseStore.albums

                val allItems: List<MediaItem> = when {
                    parentId == ROOT_ID -> listOf(
                        buildFolderItem(
                            ALL_TRACKS_ID,
                            "All Tracks",
                            "${tracks.size} tracks",
                            MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                        ),
                        buildFolderItem(
                            ALBUMS_ID,
                            "Albums",
                            "${albums.size} albums",
                            MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                        )
                    )
                    parentId == ALL_TRACKS_ID -> {
                        tracks.map { buildBrowseTrackItem(it) }
                    }
                    parentId == ALBUMS_ID -> {
                        albums.map { alb ->
                            buildFolderItem(
                                "$ALBUM_PREFIX${alb.albumKey}",
                                alb.albumName,
                                "${alb.artistName} • ${alb.trackCount} tracks",
                                MediaMetadata.MEDIA_TYPE_ALBUM
                            )
                        }
                    }
                    parentId.startsWith(ALBUM_PREFIX) -> {
                        val albKey = parentId.removePrefix(ALBUM_PREFIX)
                        tracks.filter { it.albumKey == albKey }
                            .sortedWith(
                                compareBy<TrackItem>(
                                    { if (it.trackNo > 0) it.trackNo else 999 },
                                    { it.title.lowercase() }
                                )
                            )
                            .map { buildBrowseTrackItem(it, albumContext = albKey) }
                    }
                    else -> emptyList()
                }

                val safePage = page.coerceAtLeast(0)
                val safeSize = pageSize.coerceAtLeast(1)
                val fromIndex = (safePage * safeSize).coerceAtMost(allItems.size)
                val toIndex = ((safePage + 1) * safeSize).coerceAtMost(allItems.size)
                val paged = ImmutableList.copyOf(allItems.subList(fromIndex, toIndex))
                future.set(LibraryResult.ofItemList(paged, params))
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            serviceScope.launch(Dispatchers.IO) {
                ensureLibraryLoaded()
                val item = when {
                    mediaId == ROOT_ID -> buildRootItem()
                    mediaId == ALL_TRACKS_ID -> buildFolderItem(
                        ALL_TRACKS_ID,
                        "All Tracks",
                        "${NanoLibraryBrowseStore.tracks.size} tracks",
                        MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                    )
                    mediaId == ALBUMS_ID -> buildFolderItem(
                        ALBUMS_ID,
                        "Albums",
                        "${NanoLibraryBrowseStore.albums.size} albums",
                        MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                    )
                    mediaId.startsWith(ALBUM_PREFIX) -> {
                        val albKey = mediaId.removePrefix(ALBUM_PREFIX)
                        val alb = NanoLibraryBrowseStore.albums.firstOrNull { it.albumKey == albKey }
                        if (alb != null) {
                            buildFolderItem(
                                mediaId,
                                alb.albumName,
                                "${alb.artistName} • ${alb.trackCount} tracks",
                                MediaMetadata.MEDIA_TYPE_ALBUM
                            )
                        } else null
                    }
                    else -> NanoLibraryBrowseStore.findTrackByIdOrUri(mediaId)?.let { buildBrowseTrackItem(it) }
                }

                if (item != null) {
                    future.set(LibraryResult.ofItem(item, null))
                } else {
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                }
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val found = NanoLibraryBrowseStore.findTrackByIdOrUri(item.mediaId)
                if (found != null) {
                    buildBrowseTrackItem(found)
                } else if (item.localConfiguration?.uri != null) {
                    item
                } else {
                    item.buildUpon().setUri(item.mediaId).build()
                }
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch(Dispatchers.IO) {
                ensureLibraryLoaded()
                if (mediaItems.size == 1) {
                    val requested = mediaItems[0]
                    val targetTrack = NanoLibraryBrowseStore.findTrackByIdOrUri(requested.mediaId)
                    if (targetTrack != null) {
                        val albumContext = requested.mediaMetadata.extras?.getString(EXTRA_ALBUM_CONTEXT).orEmpty()
                        val queueSource = if (albumContext.isNotEmpty()) {
                            NanoLibraryBrowseStore.tracks
                                .filter { it.albumKey == albumContext }
                                .sortedWith(
                                    compareBy<TrackItem>(
                                        { if (it.trackNo > 0) it.trackNo else 999 },
                                        { it.title.lowercase() }
                                    )
                                )
                        } else {
                            NanoLibraryBrowseStore.tracks
                        }
                        val idx = queueSource.indexOfFirst { it.uri == targetTrack.uri }.coerceAtLeast(0)
                        val builtQueue = queueSource.map { buildBrowseTrackItem(it, albumContext.ifEmpty { null }) }
                        future.set(
                            MediaSession.MediaItemsWithStartPosition(
                                builtQueue,
                                idx,
                                startPositionMs.coerceAtLeast(0L)
                            )
                        )
                        return@launch
                    }
                }

                val resolved = mediaItems.map { item ->
                    val found = NanoLibraryBrowseStore.findTrackByIdOrUri(item.mediaId)
                    if (found != null) {
                        buildBrowseTrackItem(found)
                    } else if (item.localConfiguration?.uri != null) {
                        item
                    } else {
                        item.buildUpon().setUri(item.mediaId).build()
                    }
                }
                future.set(
                    MediaSession.MediaItemsWithStartPosition(
                        resolved,
                        startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)),
                        startPositionMs.coerceAtLeast(0L)
                    )
                )
            }
            return future
        }
    }
}