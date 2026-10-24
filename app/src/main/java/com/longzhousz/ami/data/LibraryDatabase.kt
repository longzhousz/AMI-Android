package com.longzhousz.ami.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.core.LocalLyrics
import com.longzhousz.ami.core.PlaybackSnapshot
import org.json.JSONArray

data class ScanRecord(val key: String, val fingerprint: String, val ids: List<String>, val sources: List<String> = emptyList(), val artifacts: List<String> = emptyList())

class LibraryDatabase(context: Context, name: String = "ami.sqlite3") : SQLiteOpenHelper(context, name, null, 3) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE songs (
            id TEXT PRIMARY KEY, uri TEXT NOT NULL, source TEXT NOT NULL, folder TEXT NOT NULL,
            title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, album_artist TEXT NOT NULL,
            track INTEGER NOT NULL, disc INTEGER NOT NULL, duration INTEGER NOT NULL, format TEXT NOT NULL,
            cover TEXT, lyrics TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER, user_order INTEGER,
            favorite INTEGER NOT NULL DEFAULT 0, imported_at INTEGER NOT NULL, netease_id TEXT,
            lyric_translated TEXT NOT NULL DEFAULT '')""")
        db.execSQL("CREATE TABLE translations (cache_key TEXT PRIMARY KEY, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE actions (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name TEXT NOT NULL, source TEXT NOT NULL, status TEXT NOT NULL)")
        createLyricsTables(db)
        createPerformanceTables(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE songs ADD COLUMN netease_id TEXT")
            db.execSQL("ALTER TABLE songs ADD COLUMN lyric_translated TEXT NOT NULL DEFAULT ''")
            createLyricsTables(db)
        }
        if (oldVersion < 3) createPerformanceTables(db)
    }
    private fun createPerformanceTables(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX songs_import_order ON songs(imported_at,id)")
        db.execSQL("CREATE TABLE scan_cache (cache_key TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, ids TEXT NOT NULL, sources TEXT NOT NULL, artifacts TEXT NOT NULL)")
        db.execSQL("CREATE TABLE playback_queue (position INTEGER PRIMARY KEY, song_id TEXT NOT NULL)")
        db.execSQL("CREATE TABLE playback_checkpoint (singleton INTEGER PRIMARY KEY CHECK(singleton=1), queue_index INTEGER NOT NULL, position_ms INTEGER NOT NULL, repeat_mode INTEGER NOT NULL, shuffle INTEGER NOT NULL)")
    }
    private fun createLyricsTables(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX songs_netease_id ON songs(netease_id)")
        db.execSQL("CREATE TABLE netease_lyrics (music_id TEXT PRIMARY KEY, original TEXT NOT NULL, translated TEXT NOT NULL, name TEXT NOT NULL, source TEXT NOT NULL)")
        db.execSQL("CREATE TABLE lyric_overrides (song_id TEXT PRIMARY KEY REFERENCES songs(id) ON DELETE CASCADE, original TEXT NOT NULL, translated TEXT NOT NULL, name TEXT NOT NULL)")
    }

    @Synchronized fun songs(): List<Song> = readableDatabase.rawQuery("""SELECT s.*, o.original AS manual_original,
        o.translated AS manual_translated, o.name AS manual_name, n.original AS netease_original,
        n.translated AS netease_translated, n.name AS netease_name FROM songs s
        LEFT JOIN lyric_overrides o ON s.id=o.song_id LEFT JOIN netease_lyrics n ON s.netease_id=n.music_id
        ORDER BY s.imported_at,s.id""", null).use { c ->
        val columns = c.columnNames.withIndex().associate { it.value to it.index }
        fun s(key: String) = c.getString(columns.getValue(key)) ?: ""
        fun l(key: String) = c.getLong(columns.getValue(key))
        fun nullableLong(key: String) = columns.getValue(key).let { if (c.isNull(it)) null else c.getLong(it) }
        buildList {
            while (c.moveToNext()) add(Song(
                id = s("id"), uri = s("uri"), sourceUri = s("source"), folder = s("folder"), title = s("title"),
                artist = s("artist"), album = s("album"), albumArtist = s("album_artist"), track = l("track").toInt(),
                disc = l("disc").toInt(), duration = l("duration"), format = s("format"), cover = s("cover").ifBlank { null },
                lyrics = s("manual_original").ifBlank { s("lyrics").ifBlank { s("netease_original") } },
                lyricTranslation = if (s("manual_original").isNotBlank()) s("manual_translated") else if (s("lyrics").isBlank()) s("netease_translated") else s("lyric_translated"),
                lyricsSource = when { s("manual_original").isNotBlank() -> s("manual_name"); s("lyrics").isNotBlank() -> "同名 LRC"; s("netease_original").isNotBlank() -> "网易云 · ${s("netease_name")}"; else -> "" },
                manualLyrics = s("manual_original").isNotBlank(), neteaseId = s("netease_id").ifBlank { null },
                start = l("start"), end = nullableLong("end"), order = nullableLong("user_order")?.toInt(),
                favorite = l("favorite") != 0L, importedAt = l("imported_at"),
            ))
        }
    }

    @Synchronized fun songIds(): Set<String> = readableDatabase.rawQuery("SELECT id FROM songs", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
    @Synchronized fun cachedFileReferences(): Set<String> = readableDatabase.rawQuery("SELECT uri,cover FROM songs", null).use { c ->
        buildSet { while (c.moveToNext()) {
            c.getString(0)?.takeIf { it.startsWith("file:") }?.let { android.net.Uri.parse(it).path }?.let { add(it) }
            c.getString(1)?.let { add(it) }
        } }
    }
    @Synchronized fun lyricIds(): Set<String> = readableDatabase.rawQuery("SELECT music_id FROM netease_lyrics", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    @Synchronized fun scanRecords(): Map<String, ScanRecord> = readableDatabase.rawQuery("SELECT * FROM scan_cache", null).use { c ->
        fun strings(column: Int) = JSONArray(c.getString(column)).let { a -> (0 until a.length()).map { a.getString(it) } }
        buildMap { while (c.moveToNext()) runCatching { ScanRecord(c.getString(0), c.getString(1), strings(2), strings(3), strings(4)) }
            .getOrNull()?.let { put(it.key, it) } }
    }
    @Synchronized fun saveScanRecord(record: ScanRecord) {
        writableDatabase.insertWithOnConflict("scan_cache", null, ContentValues().apply {
            put("cache_key", record.key); put("fingerprint", record.fingerprint)
            put("ids", JSONArray(record.ids).toString()); put("sources", JSONArray(record.sources).toString()); put("artifacts", JSONArray(record.artifacts).toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun removeScanRecord(key: String) { writableDatabase.delete("scan_cache", "cache_key=?", arrayOf(key)) }

    /** Restore a queue without loading every lyric or joining the full collection. */
    @Synchronized fun playbackSongs(ids: List<String>): List<Song> = ids.distinct().chunked(400).flatMap { batch ->
        readableDatabase.rawQuery("SELECT id,uri,source,folder,title,artist,album,duration,cover,start,end FROM songs WHERE id IN (${batch.joinToString { "?" }})", batch.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(Song(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                artist = c.getString(5), album = c.getString(6), duration = c.getLong(7), cover = c.getString(8), start = c.getLong(9), end = if (c.isNull(10)) null else c.getLong(10))) }
        }
    }

    @Synchronized fun playbackSnapshot(): PlaybackSnapshot? = readableDatabase.rawQuery("SELECT queue_index,position_ms,repeat_mode,shuffle FROM playback_checkpoint WHERE singleton=1", null).use { c ->
        if (!c.moveToFirst()) return@use null
        val queue = readableDatabase.rawQuery("SELECT song_id FROM playback_queue ORDER BY position", null).use { q -> buildList { while (q.moveToNext()) add(q.getString(0)) } }
        PlaybackSnapshot(queue, c.getInt(0), c.getLong(1), c.getInt(2), c.getInt(3) != 0)
    }
    @Synchronized fun savePlayback(snapshot: PlaybackSnapshot, queueChanged: Boolean) = transaction {
        if (queueChanged) {
            writableDatabase.delete("playback_queue", null, null)
            val insert = writableDatabase.compileStatement("INSERT INTO playback_queue(position,song_id) VALUES (?,?)")
            insert.use { statement -> snapshot.queue.forEachIndexed { index, id ->
                statement.bindLong(1, index.toLong()); statement.bindString(2, id); statement.executeInsert()
            } }
        }
        writableDatabase.execSQL("INSERT OR REPLACE INTO playback_checkpoint(singleton,queue_index,position_ms,repeat_mode,shuffle) VALUES (1,?,?,?,?)",
            arrayOf<Any>(snapshot.index, snapshot.position.coerceAtLeast(0), snapshot.repeat, if (snapshot.shuffle) 1 else 0))
        Unit
    }

    @Synchronized fun upsert(song: Song) {
        val v = ContentValues().apply {
            put("id", song.id); put("uri", song.uri); put("source", song.sourceUri); put("folder", song.folder)
            put("title", song.title); put("artist", song.artist); put("album", song.album); put("album_artist", song.albumArtist)
            put("track", song.track); put("disc", song.disc); put("duration", song.duration); put("format", song.format)
            put("cover", song.cover); put("lyrics", song.lyrics); put("start", song.start); put("end", song.end)
            put("netease_id", song.neteaseId)
            put("lyric_translated", song.lyricTranslation)
        }
        // UPDATE preserves user order, favorites and the original import time on rescans.
        if (writableDatabase.update("songs", v, "id=?", arrayOf(song.id)) == 0) {
            v.put("imported_at", song.importedAt)
            writableDatabase.insertOrThrow("songs", null, v)
        }
    }

    @Synchronized fun favorite(id: String, value: Boolean) {
        writableDatabase.update("songs", ContentValues().apply { put("favorite", if (value) 1 else 0) }, "id=?", arrayOf(id))
    }
    @Synchronized fun reorder(ids: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEachIndexed { index, id -> db.update("songs", ContentValues().apply { put("user_order", index) }, "id=?", arrayOf(id)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun remove(id: String) { writableDatabase.delete("songs", "id=?", arrayOf(id)) }
    @Synchronized fun cacheLyrics(musicId: String, lyrics: LocalLyrics, name: String, source: String) {
        writableDatabase.insertWithOnConflict("netease_lyrics", null, ContentValues().apply {
            put("music_id", musicId); put("original", lyrics.original); put("translated", lyrics.translated)
            put("name", name); put("source", source)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun setLyrics(songId: String, lyrics: LocalLyrics, name: String) {
        writableDatabase.insertWithOnConflict("lyric_overrides", null, ContentValues().apply {
            put("song_id", songId); put("original", lyrics.original); put("translated", lyrics.translated); put("name", name)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun clearLyrics(songId: String) { writableDatabase.delete("lyric_overrides", "song_id=?", arrayOf(songId)) }
    @Synchronized fun lyricsCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM netease_lyrics", null).use { it.moveToFirst(); it.getInt(0) }
    @Synchronized fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { val result = block(); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
    @Synchronized fun translation(key: String): String? = readableDatabase.rawQuery("SELECT body FROM translations WHERE cache_key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    @Synchronized fun cacheTranslation(key: String, body: String) {
        writableDatabase.insertWithOnConflict("translations", null, ContentValues().apply { put("cache_key", key); put("body", body) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun log(name: String, source: String, status: String) {
        writableDatabase.insert("actions", null, ContentValues().apply { put("time", System.currentTimeMillis()); put("name", name); put("source", source); put("status", status) })
        writableDatabase.execSQL("DELETE FROM actions WHERE id NOT IN (SELECT id FROM actions ORDER BY id DESC LIMIT 200)")
    }
    @Synchronized fun actionLog(): List<String> = readableDatabase.rawQuery("SELECT time,name,source,status FROM actions ORDER BY id DESC LIMIT 50", null).use { c ->
        buildList { while (c.moveToNext()) add("${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(c.getLong(0)))}  ${c.getString(1)} · ${c.getString(2)} · ${c.getString(3)}") }
    }
}
