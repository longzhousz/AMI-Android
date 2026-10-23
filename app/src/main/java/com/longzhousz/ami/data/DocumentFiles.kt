package com.longzhousz.ami.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.longzhousz.ami.core.NeteaseLyrics
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

data class DocumentEntry(val uri: Uri, val name: String, val folder: String, val relative: String, val modified: Long, val size: Long) {
    val extension get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val stem get() = name.substringBeforeLast('.')
}

class DocumentFiles(context: Context) {
    private val resolver = context.contentResolver
    companion object {
        fun identity(uri: Uri): String = "${uri.authority}:${DocumentsContract.getDocumentId(uri)}"
    }
    suspend fun walk(root: Uri, id: String, path: String, result: MutableList<DocumentEntry>, depth: Int = 0) {
        currentCoroutineContext().ensureActive()
        require(depth <= 48 && result.size < 100000) { "目录过深或文件过多，请分批导入" }
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE)
        val directories = mutableListOf<Pair<String, String>>()
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(root, id), projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                require(result.size < 100000) { "文件过多，请分批导入" }
                val childId = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val relative = if (path.isEmpty()) name else "$path/$name"
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) directories += childId to relative
                else result += DocumentEntry(DocumentsContract.buildDocumentUriUsingTree(root, childId), name,
                    "${root.authority}:$id", relative, cursor.getLong(3), cursor.getLong(4))
            }
        } ?: error("文件夹提供程序没有响应")
        directories.forEach { (childId, relative) -> walk(root, childId, relative, result, depth + 1) }
    }
    fun name(uri: Uri): String = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
    fun readBounded(uri: Uri, limit: Int): ByteArray = resolver.openInputStream(uri)?.use {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "文件过大" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("文件无法读取")
    fun readText(uri: Uri): String = NeteaseLyrics.text(readBounded(uri, 2 * 1024 * 1024))
}
