package com.longzhousz.ami.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.longzhousz.ami.core.LocalLyrics

/** Caller holds the live transaction. IDs are translated; install-local origin 1 is never replaced. */
internal fun mergePersonalData(
    library: LibraryDatabase,
    stage: SQLiteDatabase,
    checkActive: () -> Unit,
) {
    val live = library.writableDatabase
    val origins = mutableMapOf<Long, Long>()
    stage.rawQuery("SELECT id,source FROM listening_origins", null).use { rows ->
        while (rows.moveToNext()) {
            val source = rows.getString(1)
            live.execSQL("INSERT OR IGNORE INTO listening_origins(source) VALUES(?)", arrayOf(source))
            origins[rows.getLong(0)] =
                live.rawQuery("SELECT id FROM listening_origins WHERE source=?", arrayOf(source)).use {
                    it.moveToFirst()
                    it.getLong(0)
                }
        }
    }
    val tracks = mutableMapOf<Long, Long>()
    stage.rawQuery("SELECT * FROM listening_tracks", null).use { rows ->
        while (rows.moveToNext()) {
            checkActive()
            live.execSQL("INSERT OR IGNORE INTO listening_tracks(music_key,title,artist_key,artist,album_key,album,cover,first_day) VALUES(?,?,?,?,?,?,?,?)", Array(8) { value(rows, it + 1) })
            val key = rows.getString(1)
            live.execSQL("UPDATE listening_tracks SET first_day=MIN(first_day,?) WHERE music_key=?", arrayOf<Any>(rows.getLong(8), key))
            tracks[rows.getLong(0)] =
                live.rawQuery("SELECT id FROM listening_tracks WHERE music_key=?", arrayOf(key)).use {
                    it.moveToFirst()
                    it.getLong(0)
                }
        }
    }
    mergeCounters(live, stage, origins, tracks, checkActive)
    if (stage.rawQuery("SELECT 1 FROM listening_status WHERE incomplete=1", null).use { it.moveToFirst() }) {
        live.execSQL("INSERT OR REPLACE INTO listening_status(singleton,incomplete) VALUES(1,1)")
    }
    mergeDocuments(library, stage, checkActive)
}

private fun mergeCounters(
    live: SQLiteDatabase,
    stage: SQLiteDatabase,
    origins: Map<Long, Long>,
    tracks: Map<Long, Long>,
    checkActive: () -> Unit,
) {
    for ((table, dimension) in listOf("listening_song_day" to "track", "listening_hour" to "hour")) {
        live.compileStatement("INSERT INTO $table(day,$dimension,origin,millis) VALUES(?,?,?,?) ON CONFLICT(day,$dimension,origin) DO UPDATE SET millis=MAX(millis,excluded.millis)").use { insert ->
            stage.rawQuery("SELECT day,$dimension,origin,millis FROM $table", null).use { rows ->
                var count = 0
                while (rows.moveToNext()) {
                    if (++count % 128 == 0) checkActive()
                    insert.bindLong(1, rows.getLong(0))
                    insert.bindLong(2, if (dimension == "track") tracks.getValue(rows.getLong(1)) else rows.getLong(1))
                    insert.bindLong(3, origins.getValue(rows.getLong(2)))
                    insert.bindLong(4, rows.getLong(3))
                    insert.executeInsert()
                }
            }
        }
    }
}

private fun mergeDocuments(
    library: LibraryDatabase,
    stage: SQLiteDatabase,
    checkActive: () -> Unit,
) {
    val live = library.writableDatabase
    for (table in listOf("backup_song_links", "translations")) {
        stage.rawQuery("SELECT * FROM $table", null).use { rows ->
            val columns = rows.columnNames.joinToString()
            live.compileStatement("INSERT OR IGNORE INTO $table($columns) VALUES(${rows.columnNames.joinToString { "?" }})").use { insert ->
                while (rows.moveToNext()) {
                    checkActive()
                    for (i in 0 until rows.columnCount) {
                        when {
                            rows.isNull(i) -> insert.bindNull(i + 1)
                            rows.getType(i) == Cursor.FIELD_TYPE_INTEGER -> insert.bindLong(i + 1, rows.getLong(i))
                            else -> insert.bindString(i + 1, rows.getString(i))
                        }
                    }
                    insert.executeInsert()
                }
            }
        }
    }
    stage.rawQuery("SELECT music_id,original,translated,name FROM netease_lyrics", null).use { rows ->
        while (rows.moveToNext()) {
            checkActive()
            val id = rows.getString(0)
            val exists = live.rawQuery("SELECT 1 FROM netease_lyrics WHERE music_id=?", arrayOf(id)).use { it.moveToFirst() }
            if (!exists) library.cacheLyrics(id, LocalLyrics(rows.getString(1), rows.getString(2)), rows.getString(3), "backup")
        }
    }
}

private fun value(
    row: Cursor,
    index: Int,
): Any? =
    when {
        row.isNull(index) -> null
        row.getType(index) == Cursor.FIELD_TYPE_INTEGER -> row.getLong(index)
        else -> row.getString(index)
    }
