package com.longzhousz.ami.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.longzhousz.ami.core.Cue
import com.longzhousz.ami.core.NcmDecoder
import com.longzhousz.ami.core.NeteaseLyrics
import com.longzhousz.ami.core.NeteaseMetadata
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.core.digest
import com.longzhousz.ami.core.ScanFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.util.Locale

class LibraryScanner(private val context: Context, private val repository: LibraryRepository) {
    private val resolver = context.contentResolver
    private val files = DocumentFiles(context)
    private val audioExtensions = setOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "aac", "aiff", "ape", "ncm")
    private val errors = mutableListOf<String>()
    // A document ID is stable even when the user authorizes overlapping SAF trees.
    private fun identity(uri: Uri): String = DocumentFiles.identity(uri)

    suspend fun scan(roots: Set<String>, lyricRoots: Set<String> = emptySet()) {
        val entries = mutableListOf<DocumentEntry>()
        errors.clear()
        roots.forEach { root ->
            try { files.walk(Uri.parse(root), DocumentsContract.getTreeDocumentId(Uri.parse(root)), "", entries, 0) }
            catch (e: CancellationException) { throw e }
            catch (_: SecurityException) { errors += "文件夹授权失效，请重新选择：${Uri.parse(root).lastPathSegment}" }
            catch (_: Exception) { errors += "无法读取文件夹：${Uri.parse(root).lastPathSegment}" }
        }
        val unique = entries.distinctBy { identity(it.uri) }
        val records = repository.db.scanRecords()
        val lyricReport = LyricImporter(context, repository).folders(lyricRoots, unique, records)
        errors += lyricReport.errors
        val byFolder = unique.groupBy { it.folder }.mapValues { (_, items) -> items.sortedBy { it.name.lowercase(Locale.ROOT) } }
        val byRelative = entries.asReversed().associateBy { it.uri.authority to (it.uri.toString().substringBefore("/document/") + ":" + it.relative.lowercase(Locale.ROOT)) }
        val byIdentity = entries.groupBy { identity(it.uri) }
        fun cover(entry: DocumentEntry) = entry.stem.lowercase(Locale.ROOT) in setOf("cover", "folder", "front") && entry.extension in setOf("jpg", "jpeg", "png", "webp")
        fun dependencies(entry: DocumentEntry, lyrics: Boolean): List<DocumentEntry> {
            val siblings = byFolder[entry.folder].orEmpty()
            return listOfNotNull(entry, siblings.firstOrNull { lyrics && it.stem.equals(entry.stem, true) && it.extension == "lrc" }, siblings.firstOrNull(::cover))
        }
        fun cueFingerprint(cue: DocumentEntry, sources: List<DocumentEntry>): String? = ScanFingerprint.of("cue-scan-v1",
            (listOf(cue) + sources.flatMap { dependencies(it, false) }).distinctBy { it.uri }.map { it.stamp() })
        val work = unique.filter { it.extension == "cue" } + unique.filter { it.extension in audioExtensions }
        val handled = mutableSetOf<String>()
        val present = repository.db.songIds().toMutableSet()
        val checkedFiles = mutableMapOf<String, Boolean>()
        data class Write(val songs: List<Song>, val key: String, val record: ScanRecord?, val removed: List<String>)
        val pending = mutableListOf<Write>()
        var pendingCount = 0
        var pendingLyrics = 0
        var imported = 0
        var skipped = 0
        var lastReload = System.nanoTime()
        var lastProgress = 0L
        var changed = lyricReport.imported > 0
        fun flush() {
            if (pending.isEmpty()) return
            repository.db.transaction {
                pending.forEach { write ->
                    write.songs.forEach(repository.db::upsert)
                    write.removed.forEach(repository.db::remove)
                    if (write.record != null) repository.db.saveScanRecord(write.record) else repository.db.removeScanRecord(write.key)
                }
            }
            changed = true
            pending.clear(); pendingCount = 0; pendingLyrics = 0
            if (System.nanoTime() - lastReload >= 750_000_000L) { repository.reload(); lastReload = System.nanoTime() }
        }
        fun add(songs: List<Song>, key: String, fingerprint: String?, sources: List<String> = emptyList(), removed: List<String> = emptyList()) {
            val artifacts = songs.flatMap { song -> listOfNotNull(song.cover, song.uri.takeIf { it.startsWith("file:") }?.let { Uri.parse(it).path }) }.distinct()
            val record = fingerprint?.let { ScanRecord(key, it, songs.map { song -> song.id }, sources, artifacts) }
            pending += Write(songs, key, record, removed)
            present += songs.map { it.id }; present -= removed.toSet()
            pendingCount += songs.size; pendingLyrics += songs.sumOf { it.lyrics.length }
            imported += songs.size
        }
        try {
            work.forEachIndexed { index, entry ->
                currentCoroutineContext().ensureActive()
                val now = System.nanoTime()
                if (index == 0 || index == work.lastIndex || now - lastProgress >= 150_000_000L) {
                    repository.importState.value = ImportState(true, index, work.size, entry.name, errors.takeLast(20)); lastProgress = now
                }
                if (identity(entry.uri) in handled) { skipped++; return@forEachIndexed }
                try {
                    val siblings = byFolder[entry.folder].orEmpty()
                    val key = "audio:" + identity(entry.uri)
                    if (entry.extension == "cue") {
                        val previous = records[key]
                        val previousSources = previous?.sources?.mapNotNull { id -> byIdentity[id]?.firstOrNull {
                            it.uri.toString().substringBefore("/document/") == entry.uri.toString().substringBefore("/document/")
                        } }.orEmpty()
                        val fingerprint = if (previousSources.isNotEmpty() && previousSources.size == previous?.sources?.size) cueFingerprint(entry, previousSources) else null
                        if (previous?.reusable(fingerprint, present, checkedFiles) == true) {
                            handled += previous.sources
                            return@forEachIndexed
                        }
                        val errorsBefore = errors.size
                        val sheet = Cue.parse(files.readText(entry.uri))
                        val baseByFile = sheet.tracks.map { it.file }.distinct().associateWith { name ->
                            val relative = (entry.relative.substringBeforeLast('/', "") + "/" + name).trimStart('/')
                            val audio = byRelative[entry.uri.authority to (entry.uri.toString().substringBefore("/document/") + ":" + relative.lowercase(Locale.ROOT))]
                                ?: error("CUE 引用文件不存在：$name")
                            require(audio.extension in audioExtensions) { "CUE 引用格式不支持" }
                            audio to readSong(audio, byFolder[audio.folder].orEmpty(), includeLyrics = false)
                        }
                        val songs = sheet.tracks.mapIndexed { trackIndex, track ->
                            val (source, base) = baseByFile.getValue(track.file)
                            val next = sheet.tracks.getOrNull(trackIndex + 1)?.takeIf { it.file == track.file }?.start
                            val end = next ?: base.duration.takeIf { it > track.start }
                            require(base.duration == 0L || track.start < base.duration) { "CUE 起点超过音频长度" }
                            base.copy(id = digest("${identity(entry.uri)}#${track.number}"), sourceUri = entry.uri.toString(),
                                title = track.title, artist = track.artist.ifBlank { base.artist }, album = sheet.title.ifBlank { entry.stem },
                                albumArtist = sheet.artist.ifBlank { base.albumArtist }, track = track.number, start = track.start, end = end,
                                duration = end?.minus(track.start) ?: 0, format = "CUE · ${base.format}", lyrics = "", lyricTranslation = "", neteaseId = null, folder = source.folder)
                        }
                        val sources = baseByFile.values.map { identity(it.first.uri) }
                        add(songs, key, cueFingerprint(entry, baseByFile.values.map { it.first }).takeIf { errors.size == errorsBefore }, sources, sources.map(::digest))
                        handled += sources
                    } else {
                        val fingerprint = ScanFingerprint.of("audio-scan-v1", dependencies(entry, true).map { it.stamp() })
                        if (records[key]?.reusable(fingerprint, present, checkedFiles) != true) {
                            val errorsBefore = errors.size
                            val song = readSong(entry, siblings)
                            add(listOf(song), key, fingerprint.takeIf { errors.size == errorsBefore })
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { errors += "${entry.name}：${e.message?.take(100) ?: "导入失败"}" }
                if (pendingCount >= 64 || pendingLyrics >= 1_000_000) flush()
            }
        } finally { flush(); if (changed) repository.reload() }
        repository.importState.value = ImportState(false, work.size, work.size, "已导入 / 更新 $imported 首 · 网易云歌词 ${lyricReport.imported} 份 · 跳过 $skipped 个音频镜像 · ${errors.size} 个问题", errors.takeLast(50))
    }

    private suspend fun readSong(entry: DocumentEntry, siblings: List<DocumentEntry>, includeLyrics: Boolean = true): Song {
        currentCoroutineContext().ensureActive()
        var audioUri = entry.uri
        var ncmHeader: NcmDecoder.Header? = null
        if (entry.extension == "ncm") {
            val coroutineContext = currentCoroutineContext()
            val target = File(repository.audioDir, digest("${identity(entry.uri)}:${entry.modified}:${entry.size}") + ".audio")
            val temporary = File(target.path + ".part")
            try {
                if (target.exists() && target.length() > 0 && entry.modified > 0) {
                    ncmHeader = resolver.openInputStream(entry.uri)!!.use { input ->
                        NcmDecoder.decode(input, object : java.io.OutputStream() { override fun write(b: Int) = Unit }, headerOnly = true)
                    }
                } else {
                    ncmHeader = resolver.openInputStream(entry.uri)!!.use { input -> temporary.outputStream().buffered().use { output ->
                        NcmDecoder.decode(input, output) { coroutineContext.ensureActive() }
                    } }
                    check(temporary.renameTo(target)) { "无法保存 NCM 缓存" }
                }
                audioUri = Uri.fromFile(target)
            } finally { temporary.delete() }
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, audioUri)
            fun tag(key: Int) = retriever.extractMetadata(key)?.trim().orEmpty()
            val metadata = ncmHeader?.metadata?.let { runCatching { JSONObject(it) }.getOrNull() }
            val artists = metadata?.optJSONArray("artist")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it)?.optString(0) }.joinToString("、") }.orEmpty()
            var coverBytes = retriever.embeddedPicture ?: ncmHeader?.cover?.takeIf { it.isNotEmpty() }
            if (coverBytes == null) {
                val coverFile = siblings.firstOrNull { it.stem.lowercase(Locale.ROOT) in setOf("cover", "folder", "front") && it.extension in setOf("jpg", "jpeg", "png", "webp") }
                coverBytes = coverFile?.let { files.readBounded(it.uri, 16 * 1024 * 1024) }
            }
            val cover = coverBytes?.let { cacheCover(it) }
            val lyricFile = siblings.firstOrNull { includeLyrics && it.stem.equals(entry.stem, true) && it.extension == "lrc" }
            val sidecar = lyricFile?.let {
                try { NeteaseLyrics.decode(files.readBounded(it.uri, 2 * 1024 * 1024)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { errors += "${it.name}：歌词无法解析，音乐仍可导入"; null }
            }
            return Song(
                id = digest(identity(entry.uri)), uri = audioUri.toString(), sourceUri = entry.uri.toString(), folder = entry.folder,
                title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { metadata?.optString("musicName").orEmpty().ifBlank { entry.stem } },
                artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST).ifBlank { artists.ifBlank { "未知艺人" } },
                album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM).ifBlank { metadata?.optString("album").orEmpty().ifBlank { entry.relative.substringBeforeLast('/', "").substringAfterLast('/').ifBlank { "未知专辑" } } },
                albumArtist = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                track = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).substringBefore('/').toIntOrNull() ?: 0,
                disc = tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER).substringBefore('/').toIntOrNull() ?: 1,
                duration = tag(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0,
                format = if (entry.extension == "ncm") "NCM · ${metadata?.optString("format", "audio")?.uppercase()}" else entry.extension.uppercase(),
                neteaseId = if (!includeLyrics) null else NeteaseLyrics.idFromMetadata(ncmHeader?.metadata) ?: if (entry.extension in setOf("mp3", "flac")) {
                    runCatching { resolver.openInputStream(entry.uri)?.buffered()?.use(NeteaseMetadata::read) }.getOrNull()
                } else null,
                cover = cover, lyrics = sidecar?.original.orEmpty(), lyricTranslation = sidecar?.translated.orEmpty(),
            )
        } finally { retriever.release() }
    }

    private fun cacheCover(bytes: ByteArray): String? {
        val key = digest(bytes)
        val file = File(repository.coverDir, "$key.jpg")
        if (file.exists()) return file.path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val temporary = File(file.path + ".part")
        try {
            temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            check(temporary.renameTo(file))
        } finally { bitmap.recycle(); temporary.delete() }
        return file.path
    }
}
