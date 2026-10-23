package com.longzhousz.ami.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.longzhousz.ami.core.NeteaseLyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class LyricImporter(context: Context, private val repository: LibraryRepository) {
    private val files = DocumentFiles(context)
    data class Report(val imported: Int, val errors: List<String>)

    suspend fun folders(roots: Set<String>, musicEntries: List<DocumentEntry> = emptyList()): Report {
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
        val coroutineContext = currentCoroutineContext()
        candidates.chunked(32).forEach { batch ->
            coroutineContext.ensureActive()
            repository.db.transaction {
                batch.forEach { entry ->
                    coroutineContext.ensureActive()
                    repository.importState.value = ImportState(true, imported, candidates.size, "网易云歌词 · ${entry.name}", errors.takeLast(20))
                    try {
                        val lyrics = NeteaseLyrics.decode(files.readBounded(entry.uri, 2 * 1024 * 1024))
                        repository.db.cacheLyrics(NeteaseLyrics.idFromFilename(entry.name)!!, lyrics, entry.name, entry.uri.toString())
                        imported++
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { errors += "歌词 ${entry.name}：${e.message?.take(80) ?: "无法读取"}" }
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
