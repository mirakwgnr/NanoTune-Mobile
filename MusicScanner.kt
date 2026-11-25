package com.nanotune.player

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object MusicScanner {

    private val AUDIO_EXTENSIONS = setOf("flac", "mp3", "wav", "m4a", "ogg", "opus", "aac")
    private const val INDEX_FILE_NAME = "library_index.json"

    fun normalizeSearch(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val noMarks = decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return noMarks.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    fun formatMs(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val totalSec = ms / 1000L
        val mins = totalSec / 60L
        val secs = totalSec % 60L
        return "%d:%02d".format(mins, secs)
    }

    fun formatBytes(size: Long): String {
        if (size <= 0L) return "—"
        if (size < 1024L * 1024L) return "${size / 1024L} KB"
        return "%.1f MB".format(size / (1024f * 1024f))
    }

    private fun cleanArtist(raw: String?): String {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty() || t == "<unknown>") return "Unknown Artist"
        return t.replace(Regex("\\s*;\\s*"), ", ")
    }

    private fun primaryArtistToken(raw: String): String {
        val cleaned = cleanArtist(raw)
        val first = cleaned.split(
            Regex("\\s*(?:,|&|\\bfeat\\.?|\\bft\\.?|\\bx\\b)\\s*", RegexOption.IGNORE_CASE),
            limit = 2
        ).firstOrNull()?.trim().orEmpty()
        return first.ifEmpty { cleaned }
    }

    fun resolveAlbumArtist(tracks: List<TrackItem>): String {
        val explicit = tracks.mapNotNull { it.albumArtist.takeIf { aa -> aa.isNotEmpty() } }
        if (explicit.isNotEmpty()) {
            return explicit.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "Unknown Artist"
        }
        val primaries = tracks.map { primaryArtistToken(it.artist) }.filter { it.isNotEmpty() }
        return primaries.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "Unknown Artist"
    }

    fun resolveAlbumYear(tracks: List<TrackItem>): String {
        val years = tracks.mapNotNull { it.year.takeIf { y -> y.isNotEmpty() } }
        return years.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""
    }

    private fun albumCacheKey(albumName: String): String {
        return albumName.trim().lowercase().ifEmpty { "unknown album" }
    }

    fun buildAlbumGroups(tracks: List<TrackItem>): List<AlbumGroup> {
        return tracks.groupBy { it.albumKey }.map { (key, list) ->
            val sample = list.first()
            val distinctHashes = LinkedHashSet<String>()
            for (tr in list) {
                if (tr.coverHash.isNotEmpty()) {
                    distinctHashes.add(tr.coverHash)
                    if (distinctHashes.size >= 4) break
                }
            }
            AlbumGroup(
                albumKey = key,
                albumName = sample.album,
                artistName = resolveAlbumArtist(list),
                year = resolveAlbumYear(list),
                trackCount = list.size,
                sampleTrack = sample,
                coverHashes = distinctHashes.toList()
            )
        }.sortedBy { it.albumName.lowercase() }
    }

    private fun interleaveTracks(rawList: List<TrackItem>): List<TrackItem> {
        val byAlbum = rawList.groupBy { it.album }
        val mixed = mutableListOf<Triple<Double, String, TrackItem>>()
        val crc = CRC32()

        for ((albName, albList) in byAlbum) {
            val sortedAlb = albList.sortedWith(
                compareBy<TrackItem>({ if (it.trackNo > 0) it.trackNo else 999 }, { it.title.lowercase() })
            )
            crc.reset()
            crc.update(albName.toByteArray(StandardCharsets.UTF_8))
            val off = (crc.value % 1000L) / 1000.0
            val m = sortedAlb.size.coerceAtLeast(1).toDouble()
            sortedAlb.forEachIndexed { k, tr ->
                mixed.add(Triple((k + off) / m, tr.title.lowercase(), tr))
            }
        }

        return mixed
            .sortedWith(compareBy<Triple<Double, String, TrackItem>>({ it.first }, { it.second }))
            .mapIndexed { idx, item -> item.third.copy(displayNum = idx + 1) }
    }

    private data class CachedEntry(
        val track: TrackItem,
        val coverChecked: Boolean
    )

    suspend fun loadCachedLibrary(context: Context): Pair<List<TrackItem>, List<AlbumGroup>>? =
        withContext(Dispatchers.IO) {
            val file = File(context.filesDir, INDEX_FILE_NAME)
            if (!file.exists()) return@withContext null
            val entries = readIndexMap(file)
            if (entries.isEmpty()) return@withContext null
            val ordered = interleaveTracks(entries.values.map { it.track })
            val albums = buildAlbumGroups(ordered)
            ordered to albums
        }

    suspend fun scanLibrary(
        context: Context,
        settings: AppSettings,
        forceFullRescan: Boolean = false
    ): Pair<List<TrackItem>, List<AlbumGroup>> = withContext(Dispatchers.IO) {
        val indexFile = File(context.filesDir, INDEX_FILE_NAME)
        val cachedMap: Map<String, CachedEntry> = if (forceFullRescan) {
            ColorExtractor.clearCoverCaches(context)
            emptyMap()
        } else {
            readIndexMap(indexFile)
        }

        val coversDir = ColorExtractor.coversDir(context)
        val rawCoverDigestToHash = ConcurrentHashMap<String, String>()
        val folderCoverCache = ConcurrentHashMap<String, String>()
        val mediaStoreAlbumCoverCache = ConcurrentHashMap<Long, String>()

        val foundEntries = LinkedHashMap<String, CachedEntry>()

        val treeUris = settings.musicFolders.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
        if (treeUris.isNotEmpty()) {
            for (treeUri in treeUris) {
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: continue
                scanDocumentFolder(
                    context = context,
                    dir = rootDoc,
                    treeUriStr = treeUri.toString(),
                    recursive = settings.recursiveScan,
                    cachedMap = cachedMap,
                    coversDir = coversDir,
                    rawCoverDigestToHash = rawCoverDigestToHash,
                    out = foundEntries
                )
            }
        } else {
            scanMediaStore(
                context = context,
                cachedMap = cachedMap,
                coversDir = coversDir,
                rawCoverDigestToHash = rawCoverDigestToHash,
                folderCoverCache = folderCoverCache,
                mediaStoreAlbumCoverCache = mediaStoreAlbumCoverCache,
                out = foundEntries
            )
        }

        writeIndexMap(indexFile, foundEntries)

        val orderedTracks = interleaveTracks(foundEntries.values.map { it.track })
        val albums = buildAlbumGroups(orderedTracks)
        orderedTracks to albums
    }

    private fun isCoverCacheValid(coversDir: File, entry: CachedEntry): Boolean {
        if (!entry.coverChecked) return false
        val h = entry.track.coverHash
        if (h.isEmpty()) return true
        return File(coversDir, "${h}_sm.jpg").exists()
    }

    private fun scanMediaStore(
        context: Context,
        cachedMap: Map<String, CachedEntry>,
        coversDir: File,
        rawCoverDigestToHash: ConcurrentHashMap<String, String>,
        folderCoverCache: ConcurrentHashMap<String, String>,
        mediaStoreAlbumCoverCache: ConcurrentHashMap<Long, String>,
        out: MutableMap<String, CachedEntry>
    ) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_MODIFIED
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR " +
            "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.flac' OR " +
            "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.opus'"

        context.contentResolver.query(collection, projection, selection, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val modCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val path = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                val displayName = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else File(path).name
                val ext = displayName.substringAfterLast('.', "").lowercase()
                if (ext !in AUDIO_EXTENSIONS) continue

                val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val uriStr = contentUri.toString()
                val size = cursor.getLong(sizeCol)
                val dateModified = if (modCol >= 0) cursor.getLong(modCol) else 0L

                val cached = cachedMap[uriStr]
                if (cached != null &&
                    cached.track.dateModified == dateModified &&
                    cached.track.fileSize == size &&
                    isCoverCacheValid(coversDir, cached)
                ) {
                    val siblingLrc = if (path.isNotEmpty()) LyricsParser.findSiblingLrcFile(File(path)) else null
                    val updatedTrack = if (siblingLrc != null && !cached.track.hasLyrics) {
                        val tag = if (siblingLrc.extension.equals("elrc", ignoreCase = true)) ".ELRC" else ".LRC"
                        cached.track.copy(hasLyrics = true, lyricsSource = "$tag (${siblingLrc.name})")
                    } else {
                        cached.track
                    }
                    out[uriStr] = cached.copy(track = updatedTrack)
                    continue
                }

                val rawTitle = cursor.getString(titleCol).orEmpty().ifBlank { displayName.substringBeforeLast('.') }
                val rawArtist = cleanArtist(cursor.getString(artistCol))
                val rawAlbum = cursor.getString(albumCol).orEmpty().ifBlank {
                    File(path).parentFile?.name ?: "Unknown Album"
                }
                val albumId = cursor.getLong(albumIdCol)
                val duration = cursor.getLong(durCol)
                val rawTrack = cursor.getInt(trackCol)
                val trackNo = if (rawTrack > 1000) rawTrack % 1000 else rawTrack
                val yearInt = cursor.getInt(yearCol)
                val yearStr = if (yearInt in 1900..2100) yearInt.toString() else ""

                val tech = readTechnicalAndCover(
                    context = context,
                    uri = contentUri,
                    filePath = path,
                    ext = ext,
                    knownDurationMs = duration,
                    fileSize = size,
                    albumId = albumId,
                    coversDir = coversDir,
                    rawCoverDigestToHash = rawCoverDigestToHash,
                    folderCoverCache = folderCoverCache,
                    mediaStoreAlbumCoverCache = mediaStoreAlbumCoverCache
                )

                val resolvedAlbum = (tech.albumOverride ?: rawAlbum).ifBlank { "Unknown Album" }
                val albumKey = albumCacheKey(resolvedAlbum)

                val tr = TrackItem(
                    id = id,
                    uri = contentUri,
                    path = path,
                    fileName = displayName,
                    title = tech.titleOverride ?: rawTitle,
                    artist = tech.artistOverride ?: rawArtist,
                    albumArtist = tech.albumArtist,
                    album = resolvedAlbum,
                    year = tech.yearOverride.ifEmpty { yearStr },
                    trackNo = if (tech.trackNoOverride > 0) tech.trackNoOverride else trackNo,
                    displayNum = 0,
                    durationMs = if (duration > 0L) duration else tech.durationMs,
                    sampleRate = tech.sampleRate,
                    bitsPerSample = tech.bitsPerSample,
                    bitrate = tech.bitrate,
                    channels = tech.channels,
                    fileSize = size,
                    codecDetail = tech.codecDetail,
                    formatLabel = tech.formatLabel,
                    albumId = albumId,
                    albumKey = albumKey,
                    hasLyrics = tech.hasLyrics,
                    lyricsSource = tech.lyricsSource,
                    coverHash = tech.coverHash,
                    dateModified = dateModified
                )
                out[uriStr] = CachedEntry(track = tr, coverChecked = true)
            }
        }
    }

    private fun scanDocumentFolder(
        context: Context,
        dir: DocumentFile,
        treeUriStr: String,
        recursive: Boolean,
        cachedMap: Map<String, CachedEntry>,
        coversDir: File,
        rawCoverDigestToHash: ConcurrentHashMap<String, String>,
        out: MutableMap<String, CachedEntry>
    ) {
        val children = dir.listFiles()
        val lyricFilesByStem = HashMap<String, String>()
        var folderImageFile: DocumentFile? = null

        for (child in children) {
            if (child.isFile) {
                val n = child.name.orEmpty()
                val low = n.lowercase()
                if (low.endsWith(".elrc") || low.endsWith(".lrc")) {
                    lyricFilesByStem[n.substringBeforeLast('.').lowercase()] = n
                } else if (low in setOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg")) {
                    if (folderImageFile == null) folderImageFile = child
                }
            }
        }

        var cachedFolderImageHash: String? = null

        for (child in children) {
            if (child.isDirectory && recursive) {
                scanDocumentFolder(
                    context = context,
                    dir = child,
                    treeUriStr = treeUriStr,
                    recursive = recursive,
                    cachedMap = cachedMap,
                    coversDir = coversDir,
                    rawCoverDigestToHash = rawCoverDigestToHash,
                    out = out
                )
            } else if (child.isFile) {
                val name = child.name.orEmpty()
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in AUDIO_EXTENSIONS) continue

                val uri = child.uri
                val uriStr = uri.toString()
                val size = child.length()
                val dateModified = child.lastModified()
                val stem = name.substringBeforeLast('.').lowercase()
                val siblingName = lyricFilesByStem[stem]

                val cached = cachedMap[uriStr]
                if (cached != null &&
                    cached.track.dateModified == dateModified &&
                    cached.track.fileSize == size &&
                    isCoverCacheValid(coversDir, cached)
                ) {
                    val updatedTrack = if (siblingName != null && !cached.track.hasLyrics) {
                        val tag = if (siblingName.lowercase().endsWith(".elrc")) ".ELRC" else ".LRC"
                        cached.track.copy(hasLyrics = true, lyricsSource = "$tag ($siblingName)")
                    } else {
                        cached.track
                    }
                    out[uriStr] = cached.copy(track = updatedTrack)
                    continue
                }

                val tech = readTechnicalAndCover(
                    context = context,
                    uri = uri,
                    filePath = "",
                    ext = ext,
                    knownDurationMs = 0L,
                    fileSize = size,
                    albumId = 0L,
                    coversDir = coversDir,
                    rawCoverDigestToHash = rawCoverDigestToHash,
                    folderCoverCache = null,
                    mediaStoreAlbumCoverCache = null
                )

                var finalCoverHash = tech.coverHash
                if (finalCoverHash.isEmpty() && folderImageFile != null) {
                    if (cachedFolderImageHash == null) {
                        cachedFolderImageHash = runCatching {
                            context.contentResolver.openInputStream(folderImageFile.uri)?.use { stream ->
                                storeRawImageBytes(context, stream.readBytes(), coversDir, rawCoverDigestToHash)
                            }
                        }.getOrNull().orEmpty()
                    }
                    finalCoverHash = cachedFolderImageHash.orEmpty()
                }

                val hasLyrics = siblingName != null || tech.hasLyrics
                val lyrSource = when {
                    siblingName != null -> {
                        val tag = if (siblingName.lowercase().endsWith(".elrc")) ".ELRC" else ".LRC"
                        "$tag ($siblingName)"
                    }
                    else -> tech.lyricsSource
                }

                val album = tech.albumOverride.orEmpty().ifBlank { dir.name ?: "Unknown Album" }
                val albumKey = albumCacheKey(album)

                out[uriStr] = CachedEntry(
                    track = TrackItem(
                        id = uriStr.hashCode().toLong(),
                        uri = uri,
                        path = "",
                        fileName = name,
                        title = tech.titleOverride.orEmpty().ifBlank { name.substringBeforeLast('.') },
                        artist = cleanArtist(tech.artistOverride),
                        albumArtist = tech.albumArtist,
                        album = album,
                        year = tech.yearOverride,
                        trackNo = tech.trackNoOverride,
                        displayNum = 0,
                        durationMs = tech.durationMs,
                        sampleRate = tech.sampleRate,
                        bitsPerSample = tech.bitsPerSample,
                        bitrate = tech.bitrate,
                        channels = tech.channels,
                        fileSize = size,
                        codecDetail = tech.codecDetail,
                        formatLabel = tech.formatLabel,
                        albumId = 0L,
                        albumKey = albumKey,
                        hasLyrics = hasLyrics,
                        lyricsSource = lyrSource,
                        folderTreeUri = treeUriStr,
                        coverHash = finalCoverHash,
                        dateModified = dateModified
                    ),
                    coverChecked = true
                )
            }
        }
    }

    private data class TechInfo(
        val titleOverride: String? = null,
        val artistOverride: String? = null,
        val albumArtist: String = "",
        val albumOverride: String? = null,
        val yearOverride: String = "",
        val trackNoOverride: Int = 0,
        val durationMs: Long = 0L,
        val sampleRate: Int = 44100,
        val bitsPerSample: Int = 0,
        val bitrate: Int = 0,
        val channels: Int = 2,
        val codecDetail: String,
        val formatLabel: String,
        val hasLyrics: Boolean = false,
        val lyricsSource: String = "",
        val coverHash: String = ""
    )

    private fun readTechnicalAndCover(
        context: Context,
        uri: Uri,
        filePath: String,
        ext: String,
        knownDurationMs: Long,
        fileSize: Long,
        albumId: Long,
        coversDir: File,
        rawCoverDigestToHash: ConcurrentHashMap<String, String>,
        folderCoverCache: ConcurrentHashMap<String, String>?,
        mediaStoreAlbumCoverCache: ConcurrentHashMap<Long, String>?
    ): TechInfo {
        var hasSiblingLrc = false
        var lyrSrc = ""
        if (filePath.isNotEmpty()) {
            val lrc = LyricsParser.findSiblingLrcFile(File(filePath))
            if (lrc != null) {
                hasSiblingLrc = true
                val tag = if (lrc.extension.equals("elrc", ignoreCase = true)) ".ELRC" else ".LRC"
                lyrSrc = "$tag (${lrc.name})"
            }
        }

        var mmrTitle: String? = null
        var mmrArtist: String? = null
        var mmrAlbumArtist = ""
        var mmrAlbum: String? = null
        var mmrDur = knownDurationMs
        var mmrBitrate = 0
        var mmrSampleRate = 44100
        var mmrBps = if (ext == "wav") 16 else 0
        var mmrYear = ""
        var mmrTrack = 0
        var coverHash = ""

        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            mmrTitle = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            mmrArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            mmrAlbumArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty().trim()
            mmrAlbum = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
            val d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (d > 0L) mmrDur = d
            mmrBitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mmrSampleRate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 44100
                mmrBps = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: mmrBps
            }
            mmrYear = Regex("\\b(19\\d{2}|20\\d{2})\\b")
                .find(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE).orEmpty())
                ?.value.orEmpty()
            mmrTrack = Regex("(\\d+)")
                .find(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val embeddedBytes = mmr.embeddedPicture
            if (embeddedBytes != null && embeddedBytes.isNotEmpty()) {
                coverHash = storeRawImageBytes(context, embeddedBytes, coversDir, rawCoverDigestToHash)
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { mmr.release() }
        }

        if (coverHash.isEmpty() && filePath.isNotEmpty()) {
            val parent = File(filePath).parentFile
            if (parent != null) {
                val pKey = parent.absolutePath
                val cachedFolderHash = folderCoverCache?.get(pKey)
                if (cachedFolderHash != null) {
                    coverHash = cachedFolderHash
                } else {
                    var resolved = ""
                    for (name in listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg")) {
                        val imgFile = File(parent, name)
                        if (imgFile.exists() && imgFile.isFile) {
                            resolved = runCatching {
                                storeRawImageBytes(context, imgFile.readBytes(), coversDir, rawCoverDigestToHash)
                            }.getOrDefault("")
                            if (resolved.isNotEmpty()) break
                        }
                    }
                    folderCoverCache?.put(pKey, resolved)
                    coverHash = resolved
                }
            }
        }

        if (coverHash.isEmpty() && albumId > 0L) {
            val cachedAlbHash = mediaStoreAlbumCoverCache?.get(albumId)
            if (cachedAlbHash != null) {
                coverHash = cachedAlbHash
            } else {
                val resolved = runCatching {
                    val artUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        albumId
                    )
                    context.contentResolver.openInputStream(artUri)?.use { input ->
                        storeRawImageBytes(context, input.readBytes(), coversDir, rawCoverDigestToHash)
                    }
                }.getOrNull().orEmpty()
                mediaStoreAlbumCoverCache?.put(albumId, resolved)
                coverHash = resolved
            }
        }

        if (ext == "flac") {
            val flacMeta = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    parseFlacHeader(BufferedInputStream(stream))
                }
            }.getOrNull()

            if (flacMeta != null) {
                val bps = flacMeta.bitsPerSample.takeIf { it > 0 } ?: 16
                val sr = flacMeta.sampleRate.takeIf { it > 0 } ?: 44100
                val dur = if (flacMeta.durationMs > 0L) flacMeta.durationMs else mmrDur
                val br = if (dur > 0 && fileSize > 0) ((fileSize * 8L * 1000L) / dur).toInt() else mmrBitrate
                val hasLyr = hasSiblingLrc || flacMeta.hasEmbeddedLyrics
                val src = if (hasSiblingLrc) lyrSrc else if (flacMeta.hasEmbeddedLyrics) "FLAC Embedded" else ""

                return TechInfo(
                    titleOverride = flacMeta.title ?: mmrTitle,
                    artistOverride = flacMeta.artist ?: mmrArtist,
                    albumArtist = flacMeta.albumArtist.orEmpty().ifEmpty { mmrAlbumArtist },
                    albumOverride = flacMeta.album ?: mmrAlbum,
                    yearOverride = flacMeta.year.orEmpty().ifEmpty { mmrYear },
                    trackNoOverride = if (flacMeta.trackNo > 0) flacMeta.trackNo else mmrTrack,
                    durationMs = dur,
                    sampleRate = sr,
                    bitsPerSample = bps,
                    bitrate = br,
                    channels = flacMeta.channels,
                    codecDetail = "FLAC (Lossless)",
                    formatLabel = "FLAC $bps-bit/${"%.1f".format(sr / 1000f)}kHz",
                    hasLyrics = hasLyr,
                    lyricsSource = src,
                    coverHash = coverHash
                )
            }
        }

        var sampleRate = mmrSampleRate
        var bitsPerSample = mmrBps
        var channels = 2
        var bitrate = mmrBitrate

        if (ext == "wav") {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    parseWavHeader(BufferedInputStream(stream))?.let { wav ->
                        sampleRate = wav.first
                        bitsPerSample = wav.second
                        channels = wav.third
                    }
                }
            }
        }

        if (bitrate <= 0 && mmrDur > 0L && fileSize > 0L) {
            bitrate = ((fileSize * 8L * 1000L) / mmrDur).toInt()
        }

        var hasEmbedded = false
        if (!hasSiblingLrc) {
            when (ext) {
                "mp3" -> {
                    val uslt = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            extractMp3UsltLyrics(BufferedInputStream(stream))
                        }
                    }.getOrNull()
                    if (!uslt.isNullOrBlank()) {
                        hasEmbedded = true
                        lyrSrc = "MP3 Embedded (USLT)"
                    }
                }
                "m4a" -> {
                    val m4aLyr = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            extractM4aLyrics(BufferedInputStream(stream))
                        }
                    }.getOrNull()
                    if (!m4aLyr.isNullOrBlank()) {
                        hasEmbedded = true
                        lyrSrc = "M4A Embedded (©lyr)"
                    }
                }
            }
        }

        val codecDetail = when (ext) {
            "mp3" -> "MP3 (MPEG Audio)"
            "m4a" -> "M4A (AAC / ALAC)"
            "wav" -> "WAV (Uncompressed PCM)"
            "ogg" -> "OGG Vorbis"
            "opus" -> "OPUS Audio"
            "aac" -> "AAC Audio"
            else -> ext.uppercase()
        }
        val fmtLabel = when (ext) {
            "wav" -> "WAV $bitsPerSample-bit/${"%.1f".format(sampleRate / 1000f)}kHz"
            "mp3", "m4a", "ogg", "opus", "aac" -> {
                if (bitrate > 0) "${ext.uppercase()} ${bitrate / 1000}kbps" else ext.uppercase()
            }
            else -> ext.uppercase()
        }

        return TechInfo(
            titleOverride = mmrTitle,
            artistOverride = mmrArtist,
            albumArtist = mmrAlbumArtist,
            albumOverride = mmrAlbum,
            yearOverride = mmrYear,
            trackNoOverride = mmrTrack,
            durationMs = mmrDur,
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample,
            bitrate = bitrate,
            channels = channels,
            codecDetail = codecDetail,
            formatLabel = fmtLabel,
            hasLyrics = hasSiblingLrc || hasEmbedded,
            lyricsSource = lyrSrc,
            coverHash = coverHash
        )
    }

    private fun storeRawImageBytes(
        context: Context,
        bytes: ByteArray,
        coversDir: File,
        rawDigestMap: ConcurrentHashMap<String, String>
    ): String {
        if (bytes.isEmpty()) return ""
        val rawDigest = md5Hex(bytes)
        val existingHash = rawDigestMap[rawDigest]
        if (existingHash != null && File(coversDir, "${existingHash}_sm.jpg").exists()) {
            return existingHash
        }
        val decoded = decodeSampledBitmap(bytes, 512, 512) ?: return ""
        val coverHash = ColorExtractor.storeCoverToDiskAndMemory(context, decoded)
        decoded.recycle()
        rawDigestMap[rawDigest] = coverHash
        return coverHash
    }

    private fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun decodeSampledBitmap(bytes: ByteArray, reqW: Int, reqH: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var inSample = 1
        while (opts.outWidth / inSample > reqW * 2 || opts.outHeight / inSample > reqH * 2) {
            inSample *= 2
        }
        val decOpts = BitmapFactory.Options().apply {
            inSampleSize = inSample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decOpts)
    }

    // -----------------------------------------------------------------------
    // Persistent JSON Library Index Read/Write
    // -----------------------------------------------------------------------
    private fun readIndexMap(file: File): Map<String, CachedEntry> {
        val map = LinkedHashMap<String, CachedEntry>()
        runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("tracks") ?: return map
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val uriStr = o.optString("uri", "")
                if (uriStr.isEmpty()) continue
                val tr = TrackItem(
                    id = o.optLong("id", 0L),
                    uri = Uri.parse(uriStr),
                    path = o.optString("path", ""),
                    fileName = o.optString("fileName", ""),
                    title = o.optString("title", ""),
                    artist = o.optString("artist", "Unknown Artist"),
                    albumArtist = o.optString("albumArtist", ""),
                    album = o.optString("album", "Unknown Album"),
                    year = o.optString("year", ""),
                    trackNo = o.optInt("trackNo", 0),
                    displayNum = o.optInt("displayNum", 0),
                    durationMs = o.optLong("durationMs", 0L),
                    sampleRate = o.optInt("sampleRate", 44100),
                    bitsPerSample = o.optInt("bitsPerSample", 0),
                    bitrate = o.optInt("bitrate", 0),
                    channels = o.optInt("channels", 2),
                    fileSize = o.optLong("fileSize", 0L),
                    codecDetail = o.optString("codecDetail", ""),
                    formatLabel = o.optString("formatLabel", ""),
                    albumId = o.optLong("albumId", 0L),
                    albumKey = o.optString("albumKey", albumCacheKey(o.optString("album", ""))),
                    hasLyrics = o.optBoolean("hasLyrics", false),
                    lyricsSource = o.optString("lyricsSource", ""),
                    folderTreeUri = o.optString("folderTreeUri", ""),
                    coverHash = o.optString("coverHash", ""),
                    dateModified = o.optLong("dateModified", 0L)
                )
                val coverChecked = o.optBoolean("coverChecked", false)
                map[uriStr] = CachedEntry(track = tr, coverChecked = coverChecked)
            }
        }
        return map
    }

    private fun writeIndexMap(file: File, entries: Map<String, CachedEntry>) {
        runCatching {
            val arr = JSONArray()
            for (entry in entries.values) {
                val tr = entry.track
                val o = JSONObject().apply {
                    put("id", tr.id)
                    put("uri", tr.uri.toString())
                    put("path", tr.path)
                    put("fileName", tr.fileName)
                    put("title", tr.title)
                    put("artist", tr.artist)
                    put("albumArtist", tr.albumArtist)
                    put("album", tr.album)
                    put("year", tr.year)
                    put("trackNo", tr.trackNo)
                    put("displayNum", tr.displayNum)
                    put("durationMs", tr.durationMs)
                    put("sampleRate", tr.sampleRate)
                    put("bitsPerSample", tr.bitsPerSample)
                    put("bitrate", tr.bitrate)
                    put("channels", tr.channels)
                    put("fileSize", tr.fileSize)
                    put("codecDetail", tr.codecDetail)
                    put("formatLabel", tr.formatLabel)
                    put("albumId", tr.albumId)
                    put("albumKey", tr.albumKey)
                    put("hasLyrics", tr.hasLyrics)
                    put("lyricsSource", tr.lyricsSource)
                    put("folderTreeUri", tr.folderTreeUri)
                    put("coverHash", tr.coverHash)
                    put("dateModified", tr.dateModified)
                    put("coverChecked", entry.coverChecked)
                }
                arr.put(o)
            }
            val root = JSONObject().put("tracks", arr)
            val tmp = File(file.parentFile, "${INDEX_FILE_NAME}.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Binary Audio Header & Embedded Tag Parsers (WAV / FLAC / ID3 USLT / MP4)
    // -----------------------------------------------------------------------
    private fun parseWavHeader(input: InputStream): Triple<Int, Int, Int>? {
        val hdr = ByteArray(44)
        if (!readFully(input, hdr)) return null
        val riff = String(hdr, 0, 4, StandardCharsets.US_ASCII)
        val wave = String(hdr, 8, 4, StandardCharsets.US_ASCII)
        if (riff != "RIFF" || wave != "WAVE") return null
        val ch = (hdr[22].toInt() and 0xFF) or ((hdr[23].toInt() and 0xFF) shl 8)
        val sr = readLeInt(hdr, 24)
        val bps = (hdr[34].toInt() and 0xFF) or ((hdr[35].toInt() and 0xFF) shl 8)
        if (sr <= 0 || bps <= 0) return null
        return Triple(sr, bps, ch.coerceIn(1, 6))
    }

    data class FlacTagResult(
        val sampleRate: Int = 44100,
        val bitsPerSample: Int = 16,
        val channels: Int = 2,
        val durationMs: Long = 0L,
        val title: String? = null,
        val artist: String? = null,
        val albumArtist: String? = null,
        val album: String? = null,
        val year: String? = null,
        val trackNo: Int = 0,
        val embeddedLyrics: String = ""
    ) {
        val hasEmbeddedLyrics: Boolean get() = embeddedLyrics.isNotBlank()
    }

    fun readFlacLyrics(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                parseFlacHeader(BufferedInputStream(stream))?.embeddedLyrics?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun parseFlacHeader(input: InputStream): FlacTagResult? {
        val magic = ByteArray(4)
        if (!readFully(input, magic) || String(magic, StandardCharsets.US_ASCII) != "fLaC") {
            return null
        }

        var sampleRate = 44100
        var bitsPerSample = 16
        var channels = 2
        var durationMs = 0L
        var title: String? = null
        var artist: String? = null
        var albumArtist: String? = null
        var album: String? = null
        var year: String? = null
        var trackNo = 0
        var lyrics = ""

        var isLast = false
        var safetyCount = 0
        while (!isLast && safetyCount++ < 32) {
            val hdr = ByteArray(4)
            if (!readFully(input, hdr)) break
            val b0 = hdr[0].toInt() and 0xFF
            isLast = (b0 and 0x80) != 0
            val blockType = b0 and 0x7F
            val len = ((hdr[1].toInt() and 0xFF) shl 16) or
                ((hdr[2].toInt() and 0xFF) shl 8) or
                (hdr[3].toInt() and 0xFF)

            if (blockType == 0 && len >= 18) {
                val info = ByteArray(len)
                if (!readFully(input, info)) break
                sampleRate = ((info[10].toInt() and 0xFF) shl 12) or
                    ((info[11].toInt() and 0xFF) shl 4) or
                    ((info[12].toInt() and 0xF0) ushr 4)
                channels = ((info[12].toInt() and 0x0E) ushr 1) + 1
                bitsPerSample = (((info[12].toInt() and 0x01) shl 4) or ((info[13].toInt() and 0xF0) ushr 4)) + 1
                val totalSamples = ((info[13].toInt() and 0x0F).toLong() shl 32) or
                    ((info[14].toInt() and 0xFF).toLong() shl 24) or
                    ((info[15].toInt() and 0xFF).toLong() shl 16) or
                    ((info[16].toInt() and 0xFF).toLong() shl 8) or
                    (info[17].toInt() and 0xFF).toLong()
                if (sampleRate > 0 && totalSamples > 0) {
                    durationMs = (totalSamples * 1000L) / sampleRate
                }
            } else if (blockType == 4 && len in 8..1_048_576) {
                val data = ByteArray(len)
                if (!readFully(input, data)) break
                var pos = 0
                if (pos + 4 <= data.size) {
                    val vendorLen = readLeInt(data, pos)
                    pos += 4 + vendorLen.coerceAtLeast(0)
                    if (pos + 4 <= data.size) {
                        val commentCount = readLeInt(data, pos).coerceIn(0, 256)
                        pos += 4
                        for (c in 0 until commentCount) {
                            if (pos + 4 > data.size) break
                            val cLen = readLeInt(data, pos)
                            pos += 4
                            if (cLen < 0 || pos + cLen > data.size) break
                            val comment = String(data, pos, cLen, StandardCharsets.UTF_8)
                            pos += cLen
                            val eq = comment.indexOf('=')
                            if (eq > 0) {
                                val k = comment.substring(0, eq).uppercase()
                                val v = comment.substring(eq + 1).trim()
                                when {
                                    k == "TITLE" -> title = v
                                    k == "ARTIST" -> artist = v
                                    k == "ALBUMARTIST" || k == "ALBUM ARTIST" -> albumArtist = v
                                    k == "ALBUM" -> album = v
                                    k in setOf("DATE", "YEAR", "ORIGINALDATE") && year == null -> {
                                        Regex("\\b(19\\d{2}|20\\d{2})\\b").find(v)?.let { year = it.value }
                                    }
                                    k == "TRACKNUMBER" -> {
                                        Regex("(\\d+)").find(v)?.groupValues?.get(1)?.toIntOrNull()?.let { trackNo = it }
                                    }
                                    k in setOf("SYNCEDLYRICS", "LYRICS", "UNSYNCEDLYRICS") || k.contains("LYRIC") -> {
                                        if (lyrics.isEmpty() || k == "SYNCEDLYRICS") lyrics = v
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                skipFully(input, len.toLong())
            }
        }

        return FlacTagResult(
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample,
            channels = channels,
            durationMs = durationMs,
            title = title,
            artist = artist,
            albumArtist = albumArtist,
            album = album,
            year = year,
            trackNo = trackNo,
            embeddedLyrics = lyrics
        )
    }

    fun extractMp3UsltLyrics(input: InputStream): String? {
        val header = ByteArray(10)
        if (!readFully(input, header)) return null
        if (header[0].toInt() != 'I'.code || header[1].toInt() != 'D'.code || header[2].toInt() != '3'.code) {
            return null
        }
        val major = header[3].toInt() and 0xFF
        val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
            ((header[7].toInt() and 0x7F) shl 14) or
            ((header[8].toInt() and 0x7F) shl 7) or
            (header[9].toInt() and 0x7F)

        if (tagSize <= 0 || tagSize > 2_097_152) return null
        var remaining = tagSize
        while (remaining >= 10) {
            val fHdr = ByteArray(10)
            if (!readFully(input, fHdr)) break
            remaining -= 10
            if (fHdr[0].toInt() == 0) break
            val frameId = String(fHdr, 0, 4, StandardCharsets.US_ASCII)
            val fSize = if (major >= 4) {
                ((fHdr[4].toInt() and 0x7F) shl 21) or
                    ((fHdr[5].toInt() and 0x7F) shl 14) or
                    ((fHdr[6].toInt() and 0x7F) shl 7) or
                    (fHdr[7].toInt() and 0x7F)
            } else {
                ((fHdr[4].toInt() and 0xFF) shl 24) or
                    ((fHdr[5].toInt() and 0xFF) shl 16) or
                    ((fHdr[6].toInt() and 0xFF) shl 8) or
                    (fHdr[7].toInt() and 0xFF)
            }
            if (fSize <= 0 || fSize > remaining) break
            if (frameId == "USLT") {
                val frameData = ByteArray(fSize)
                if (!readFully(input, frameData)) break
                val enc = frameData[0].toInt() and 0xFF
                var idx = 4
                if (enc == 1 || enc == 2) {
                    while (idx + 1 < frameData.size) {
                        if (frameData[idx].toInt() == 0 && frameData[idx + 1].toInt() == 0) {
                            idx += 2
                            break
                        }
                        idx += 2
                    }
                    val charset = if (enc == 2) StandardCharsets.UTF_16BE else StandardCharsets.UTF_16
                    return String(
                        frameData,
                        idx.coerceAtMost(frameData.size),
                        (frameData.size - idx).coerceAtLeast(0),
                        charset
                    ).trim('\u0000', ' ', '\n', '\r')
                } else {
                    while (idx < frameData.size && frameData[idx].toInt() != 0) idx++
                    if (idx < frameData.size) idx++
                    val charset = if (enc == 3) StandardCharsets.UTF_8 else StandardCharsets.ISO_8859_1
                    return String(
                        frameData,
                        idx.coerceAtMost(frameData.size),
                        (frameData.size - idx).coerceAtLeast(0),
                        charset
                    ).trim('\u0000', ' ', '\n', '\r')
                }
            } else {
                skipFully(input, fSize.toLong())
                remaining -= fSize
            }
        }
        return null
    }

    fun extractM4aLyrics(input: InputStream): String? {
        return findMp4BoxLyrics(input, Long.MAX_VALUE, depth = 0)
    }

    private fun findMp4BoxLyrics(input: InputStream, maxBytes: Long, depth: Int): String? {
        if (depth > 6) return null
        var remaining = maxBytes
        val hdr = ByteArray(8)
        while (remaining >= 8L) {
            if (!readFully(input, hdr)) return null
            val boxSize32 = readBeInt(hdr, 0).toLong() and 0xFFFFFFFFL
            val boxType = String(hdr, 4, 4, StandardCharsets.ISO_8859_1)
            var headerBytes = 8L
            val totalSize: Long = when (boxSize32) {
                0L -> remaining
                1L -> {
                    val ext = ByteArray(8)
                    if (!readFully(input, ext)) return null
                    headerBytes = 16L
                    readBeLong(ext, 0)
                }
                else -> boxSize32
            }
            if (totalSize < headerBytes || totalSize > remaining) return null
            val payloadSize = totalSize - headerBytes
            remaining -= totalSize

            when (boxType) {
                "moov", "udta", "ilst" -> {
                    findMp4BoxLyrics(input, payloadSize, depth + 1)?.let { return it }
                }
                "meta" -> {
                    if (payloadSize >= 4L) {
                        skipFully(input, 4L)
                        findMp4BoxLyrics(input, payloadSize - 4L, depth + 1)?.let { return it }
                    } else {
                        skipFully(input, payloadSize)
                    }
                }
                "\u00A9lyr" -> {
                    readMp4DataAtomText(input, payloadSize)?.takeIf { it.isNotBlank() }?.let { return it }
                }
                else -> {
                    skipFully(input, payloadSize)
                }
            }
        }
        return null
    }

    private fun readMp4DataAtomText(input: InputStream, maxBytes: Long): String? {
        var remaining = maxBytes
        val hdr = ByteArray(8)
        while (remaining >= 8L) {
            if (!readFully(input, hdr)) return null
            val size = (readBeInt(hdr, 0).toLong() and 0xFFFFFFFFL)
            val type = String(hdr, 4, 4, StandardCharsets.ISO_8859_1)
            if (size < 8L || size > remaining) return null
            val payload = size - 8L
            remaining -= size
            if (type == "data" && payload in 9L..524_288L) {
                skipFully(input, 8L)
                val textBytes = ByteArray((payload - 8L).toInt())
                if (readFully(input, textBytes)) {
                    return String(textBytes, StandardCharsets.UTF_8).trim('\u0000', ' ', '\n', '\r')
                }
                return null
            } else {
                skipFully(input, payload)
            }
        }
        return null
    }

    private fun readLeInt(b: ByteArray, off: Int): Int {
        return (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
    }

    private fun readBeInt(b: ByteArray, off: Int): Int {
        return ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
    }

    private fun readBeLong(b: ByteArray, off: Int): Long {
        return ((b[off].toLong() and 0xFFL) shl 56) or
            ((b[off + 1].toLong() and 0xFFL) shl 48) or
            ((b[off + 2].toLong() and 0xFFL) shl 40) or
            ((b[off + 3].toLong() and 0xFFL) shl 32) or
            ((b[off + 4].toLong() and 0xFFL) shl 24) or
            ((b[off + 5].toLong() and 0xFFL) shl 16) or
            ((b[off + 6].toLong() and 0xFFL) shl 8) or
            (b[off + 7].toLong() and 0xFFL)
    }

    private fun readFully(input: InputStream, dst: ByteArray): Boolean {
        var off = 0
        while (off < dst.size) {
            val n = input.read(dst, off, dst.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var left = bytes
        while (left > 0L) {
            val skipped = input.skip(left)
            if (skipped <= 0L) {
                if (input.read() == -1) break
                left--
            } else {
                left -= skipped
            }
        }
    }
}