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
        val lyricReport = LyricImporter(context, repository).folders(lyricRoots, entries)
        errors += lyricReport.errors
        val byFolder = entries.groupBy { it.folder }
        val handled = mutableSetOf<String>()
        val unique = entries.distinctBy { identity(it.uri) }
        val work = unique.filter { it.extension == "cue" } + unique.filter { it.extension in audioExtensions }
        var imported = 0
        var skipped = 0
        work.forEachIndexed { index, entry ->
            currentCoroutineContext().ensureActive()
            repository.importState.value = ImportState(true, index, work.size, entry.name, errors.takeLast(20))
            if (identity(entry.uri) in handled) { skipped++; return@forEachIndexed }
            try {
                val siblings = byFolder[entry.folder].orEmpty()
                if (entry.extension == "cue") {
                    val sheet = Cue.parse(files.readText(entry.uri))
                    // Validate every file before suppressing its full-length library entry.
                    val baseByFile = sheet.tracks.map { it.file }.distinct().associateWith { name ->
                        val relative = (entry.relative.substringBeforeLast('/', "") + "/" + name).trimStart('/')
                        val audio = entries.firstOrNull { it.relative.equals(relative, true) && it.uri.toString().substringBefore("/document/") == entry.uri.toString().substringBefore("/document/") }
                            ?: error("CUE 引用文件不存在：$name")
                        require(audio.extension in audioExtensions) { "CUE 引用格式不支持" }
                        audio to readSong(audio, byFolder[audio.folder].orEmpty())
                    }
                    val songs = sheet.tracks.mapIndexed { trackIndex, track ->
                        val (source, base) = baseByFile.getValue(track.file)
                        val next = sheet.tracks.getOrNull(trackIndex + 1)?.takeIf { it.file == track.file }?.start
                        val end = next ?: base.duration.takeIf { it > track.start }
                        require(base.duration == 0L || track.start < base.duration) { "CUE 起点超过音频长度" }
                        base.copy(id = digest("${identity(entry.uri)}#${track.number}"), sourceUri = entry.uri.toString(),
                            title = track.title, artist = track.artist.ifBlank { base.artist }, album = sheet.title.ifBlank { entry.stem },
                            albumArtist = sheet.artist.ifBlank { base.albumArtist }, track = track.number, start = track.start, end = end,
                            duration = end?.minus(track.start) ?: 0, format = "CUE · ${base.format}",
                            lyrics = "", lyricTranslation = "", neteaseId = null, folder = source.folder)
                    }
                    songs.forEach { repository.db.upsert(it); imported++ }
                    baseByFile.values.forEach { (source, _) ->
                        handled += identity(source.uri)
                        repository.db.remove(digest(identity(source.uri)))
                    }
                } else {
                    repository.db.upsert(readSong(entry, siblings)); imported++
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors += "${entry.name}：${e.message?.take(100) ?: "导入失败"}" }
            if (index % 12 == 0) repository.reload()
        }
        repository.reload()
        repository.importState.value = ImportState(false, work.size, work.size, "已导入 / 更新 $imported 首 · 网易云歌词 ${lyricReport.imported} 份 · 跳过 $skipped 个音频镜像 · ${errors.size} 个问题", errors.takeLast(50))
    }

    private suspend fun readSong(entry: DocumentEntry, siblings: List<DocumentEntry>): Song {
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
            val lyricFile = siblings.firstOrNull { it.stem.equals(entry.stem, true) && it.extension == "lrc" }
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
                neteaseId = NeteaseLyrics.idFromMetadata(ncmHeader?.metadata) ?: if (entry.extension in setOf("mp3", "flac")) {
                    runCatching { resolver.openInputStream(entry.uri)?.buffered()?.use(NeteaseMetadata::read) }.getOrNull()
                } else null,
                cover = cover, lyrics = sidecar?.original.orEmpty(), lyricTranslation = sidecar?.translated.orEmpty(),
            )
        } finally { retriever.release() }
    }

    private fun cacheCover(bytes: ByteArray): String? {
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
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
