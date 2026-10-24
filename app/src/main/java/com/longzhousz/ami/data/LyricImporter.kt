package com.longzhousz.ami.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.longzhousz.ami.core.NeteaseLyrics
import com.longzhousz.ami.core.ScanFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class LyricImporter(context: Context, private val repository: LibraryRepository) {
    private val files = DocumentFiles(context)
    data class Report(val imported: Int, val errors: List<String>)

    suspend fun folders(roots: Set<String>, musicEntries: List<DocumentEntry> = emptyList(), records: Map<String, ScanRecord> = repository.db.scanRecords()): Report {
        val entries = musicEntries.toMutableList()
        val errors = mutableListOf<String>()
        roots.forEach { root ->
            try { files.walk(Uri.parse(root), DocumentsContract.getTreeDocumentId(Uri.parse(root)), "", entries) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { errors += "歌词文件夹无法读取，请重新选择：${Uri.parse(root).lastPathSegment}" }
        }
        val candidates = entries.filter { NeteaseLyrics.idFromFilename(it.name) != null }
            .sortedWith(compareByDescending<DocumentEntry> { it.modified }.thenBy { it.uri.toString() })
            .distinctBy { NeteaseLyrics.idFromFilename(it.name) }
        var imported = 0
        val present = repository.db.lyricIds().toMutableSet()
        val checkedFiles = mutableMapOf<String, Boolean>()
        val coroutineContext = currentCoroutineContext()
        candidates.chunked(32).forEach { batch ->
            coroutineContext.ensureActive()
            data class Read(val id: String, val lyrics: com.longzhousz.ami.core.LocalLyrics, val entry: DocumentEntry, val key: String, val fingerprint: String?)
            val pending = mutableListOf<Read>()
            batch.forEach { entry ->
                coroutineContext.ensureActive()
                val id = NeteaseLyrics.idFromFilename(entry.name)!!
                val key = "lyric:$id"
                val fingerprint = ScanFingerprint.of("lyric-scan-v1", listOf(entry.stamp()))
                if (records[key]?.reusable(fingerprint, present, checkedFiles) == true) return@forEach
                try { pending += Read(id, NeteaseLyrics.decode(files.readBounded(entry.uri, 2 * 1024 * 1024)), entry, key, fingerprint) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { errors += "歌词 ${entry.name}：${e.message?.take(80) ?: "无法读取"}" }
            }
            // Content providers and JSON decoding must not hold the database write lock.
            if (pending.isNotEmpty()) repository.db.transaction {
                pending.forEach { item ->
                    repository.db.cacheLyrics(item.id, item.lyrics, item.entry.name, item.entry.uri.toString())
                    if (item.fingerprint != null) repository.db.saveScanRecord(ScanRecord(item.key, item.fingerprint, listOf(item.id)))
                    else repository.db.removeScanRecord(item.key)
                    present += item.id; imported++
                }
            }
        }
        return Report(imported, errors)
    }
    suspend fun attach(songId: String, uri: Uri) {
        currentCoroutineContext().ensureActive()
        val lyrics = NeteaseLyrics.decode(files.readBounded(uri, 2 * 1024 * 1024))
        repository.db.setLyrics(songId, lyrics, files.name(uri))
        repository.reload()
    }
}
