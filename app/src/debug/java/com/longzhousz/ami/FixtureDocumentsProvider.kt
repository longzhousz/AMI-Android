package com.longzhousz.ami

import android.database.Cursor
import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** Private debug-only document fixtures, sharing the instrumented target's runtime. */
class FixtureDocumentsProvider : ContentProvider() {
    private lateinit var directory: File
    override fun onCreate(): Boolean {
        directory = File(context!!.cacheDir, "saf-fixtures").apply { mkdirs() }
        wav("single.wav", 2); wav("album.wav", 6)
        File(directory, "single.lrc").writeText("\uFEFF[ar:Fixture]\n[00:00.00]First line\n[00:01.00]Second line")
        File(directory, "album.cue").writeText("TITLE \"Fixture Album\"\nPERFORMER \"Fixture Artist\"\nFILE \"album.wav\" WAVE\nTRACK 01 AUDIO\nTITLE \"First\"\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nTITLE \"Second\"\nINDEX 01 00:03:00\n")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF315BD6.toInt()) }
        File(directory, "cover.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        File(directory, "netease").mkdirs(); File(directory, "lyrics").mkdirs()
        File(directory, "netease/track.ncm").writeBytes(ncm(File(directory, "single.wav").readBytes()))
        val mixed = "{\"t\":-1000,\"c\":[{\"tx\":\"作词: \"},{\"tx\":\"n-buna\"}]}\n" +
            "{\"t\":-500,\"c\":[{\"tx\":\"作曲: \"},{\"tx\":\"n-buna\"}]}\n[00:00.430]First line\n[00:01.200]Second line"
        File(directory, "lyrics/1234567890123").writeText(JSONObject().put("briefDesc", "").put("karaokeLyric", "").put("lrc", mixed)
            .put("translatedLyric", "[00:00.430]第一行\n[00:01.200]第二行").toString())
        File(directory, "lyrics/manual.lrc").writeText("[00:00.430]Manual lyric")
        File(directory, "lyrics/invalid.txt").writeText("{not valid JSON")
        return true
    }
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE, Document.COLUMN_FLAGS)
    private fun row(id: String): Map<String, Any> {
        val file = if (id in setOf("root", "alias")) directory else File(directory, id)
        return mapOf(Document.COLUMN_DOCUMENT_ID to id, Document.COLUMN_DISPLAY_NAME to if (id == "root") "Fixtures" else file.name,
            Document.COLUMN_MIME_TYPE to when { file.isDirectory -> Document.MIME_TYPE_DIR; id.endsWith("wav") -> "audio/wav"; id.endsWith("jpg") -> "image/jpeg"; else -> "text/plain" },
            Document.COLUMN_LAST_MODIFIED to file.lastModified(), Document.COLUMN_SIZE to file.length(), Document.COLUMN_FLAGS to 0)
    }
    private fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cols = projection ?: columns
        return MatrixCursor(cols).apply { val values = row(documentId); addRow(cols.map { values[it] }.toTypedArray()) }
    }
    private fun queryChildDocuments(parentId: String, projection: Array<out String>?): Cursor {
        val cols = projection ?: columns
        val parent = if (parentId in setOf("root", "alias")) directory else File(directory, parentId)
        return MatrixCursor(cols).apply { parent.listFiles()!!.filter { parent != directory || it.isFile }.sortedBy { it.name }.forEach { file ->
            val id = file.relativeTo(directory).invariantSeparatorsPath
            val values = row(id); addRow(cols.map { values[it] }.toTypedArray())
        } }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        if (uri.lastPathSegment == "children") queryChildDocuments(DocumentsContract.getDocumentId(uri), projection) else queryDocument(DocumentsContract.getDocumentId(uri), projection)
    override fun getType(uri: Uri): String = row(DocumentsContract.getDocumentId(uri))[Document.COLUMN_MIME_TYPE] as String
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val documentId = DocumentsContract.getDocumentId(uri)
        val file = File(directory, documentId)
        require(mode == "r" && file.isFile && file.canonicalPath.startsWith(directory.canonicalPath + "/"))
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    private fun wav(name: String, seconds: Int) {
        val dataSize = 44100 * seconds * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize)
        File(directory, name).writeBytes(buffer.array())
    }
    private fun ncm(audio: ByteArray): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        fun int(value: Int) { repeat(4) { output.write((value ushr (8 * it)) and 255) } }
        fun encrypt(bytes: ByteArray, key: String) = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES")); doFinal(bytes)
        }
        output.write("CTENFDAM".toByteArray()); output.write(byteArrayOf(0, 0))
        val seed = "test-key-material".toByteArray()
        val keyData = encrypt("neteasecloudmusic".toByteArray() + seed, "hzHRAmso5kInbaxW").map { (it.toInt() xor 0x64).toByte() }.toByteArray()
        int(keyData.size); output.write(keyData)
        val meta = "163 key(Don't modify):".toByteArray() + Base64.getEncoder().encode(encrypt("music:{\"musicId\":1234567890123,\"musicName\":\"NetEase Fixture\",\"format\":\"wav\"}".toByteArray(), "#14ljk_!\\]&0U<'("))
        val encoded = meta.map { (it.toInt() xor 0x63).toByte() }.toByteArray()
        int(encoded.size); output.write(encoded); output.write(ByteArray(9)); int(0)
        val state = IntArray(256) { it }
        var j = 0
        repeat(256) { i -> j = (j + state[i] + (seed[i % seed.size].toInt() and 255)) and 255; val old = state[i]; state[i] = state[j]; state[j] = old }
        audio.forEachIndexed { index, b -> val x = (index + 1) and 255; output.write(b.toInt() xor state[(state[x] + state[(state[x] + x) and 255]) and 255]) }
        return output.toByteArray()
    }
}
