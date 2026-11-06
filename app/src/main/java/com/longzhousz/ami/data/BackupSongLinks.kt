package com.longzhousz.ami.data

import android.database.sqlite.SQLiteDatabase
import com.longzhousz.ami.core.LocalLyrics
import com.longzhousz.ami.core.digest
import org.json.JSONArray
import java.util.Locale

internal fun createBackupLinks(db: SQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE backup_song_links(
        reference TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,title TEXT NOT NULL,artist TEXT NOT NULL,album TEXT NOT NULL,
        duration INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER,track INTEGER NOT NULL,disc INTEGER NOT NULL,
        favorite INTEGER NOT NULL,user_order INTEGER,original TEXT NOT NULL,translated TEXT NOT NULL,name TEXT NOT NULL,
        aliases TEXT NOT NULL,applied_to TEXT)""",
    )
    db.execSQL("CREATE INDEX backup_song_links_match ON backup_song_links(fingerprint)")
    db.execSQL("CREATE INDEX backup_song_links_applied ON backup_song_links(applied_to)")
}

private fun songFingerprint(row: android.database.Cursor): String =
    digest(
        JSONArray()
            .apply {
                for (i in 1..3) put(row.getString(i).trim().lowercase(Locale.ROOT))
                for (i in 4..8) put(if (row.isNull(i)) org.json.JSONObject.NULL else row.getLong(i))
            }.toString(),
    )

/** Only metadata and explicit lyric associations; never media URIs, folder grants or cover paths. */
internal fun LibraryDatabase.captureBackupLinks() {
    val sql = writableDatabase
    sql.rawQuery("SELECT id,title,artist,album,duration,start,end,track,disc,COALESCE(user_order,-1),favorite FROM songs", null).use { rows ->
        while (rows.moveToNext()) {
            val id = rows.getString(0)
            val saved =
                sql.rawQuery("SELECT reference,aliases FROM backup_song_links WHERE applied_to=? LIMIT 1", arrayOf(id)).use {
                    if (it.moveToFirst()) it.getString(0) to it.getString(1) else id to "[]"
                }
            val aliases = JSONArray(saved.second).let { a -> (0 until a.length()).map { a.getString(it) } }.toMutableSet()
            aliases += id
            val lyric =
                sql.rawQuery("SELECT original,translated,name FROM lyric_overrides WHERE song_id=?", arrayOf(id)).use {
                    if (it.moveToFirst()) listOf(it.getString(0), it.getString(1), it.getString(2)) else listOf("", "", "")
                }
            sql.execSQL(
                "INSERT OR REPLACE INTO backup_song_links VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    saved.first,
                    songFingerprint(rows),
                    rows.getString(1),
                    rows.getString(2),
                    rows.getString(3),
                    rows.getLong(4),
                    rows.getLong(5),
                    if (rows.isNull(6)) null else rows.getLong(6),
                    rows.getInt(7),
                    rows.getInt(8),
                    rows.getInt(10),
                    rows.getLong(9).takeIf { it >= 0 },
                    lyric[0],
                    lyric[1],
                    lyric[2],
                    JSONArray(aliases.toList().takeLast(32)).toString(),
                    id,
                ),
            )
        }
    }
}

/** Apply once, after the scan has finished. Ambiguous duplicate recordings wait for an exact ID. */
internal fun LibraryDatabase.applyBackupLinks() =
    transaction {
        val sql = writableDatabase
        if (!sql.rawQuery("SELECT 1 FROM backup_song_links WHERE applied_to IS NULL LIMIT 1", null).use { it.moveToFirst() }) return@transaction
        val matches = mutableMapOf<String, MutableSet<String>>()
        sql.rawQuery("SELECT id,title,artist,album,duration,start,end,track,disc,COALESCE(user_order,-1),favorite FROM songs", null).use { rows ->
            while (rows.moveToNext()) matches.getOrPut(songFingerprint(rows)) { mutableSetOf() }.add(rows.getString(0))
        }
        // Group once; large sets of identical metadata must not cause repeated full scans.
        val duplicateCounts =
            sql.rawQuery("SELECT fingerprint,COUNT(*) FROM backup_song_links GROUP BY fingerprint", null).use { rows ->
                buildMap { while (rows.moveToNext()) put(rows.getString(0), rows.getInt(1)) }
            }
        val pending =
            sql.rawQuery("SELECT reference FROM backup_song_links WHERE applied_to IS NULL", null).use { rows ->
                buildList { while (rows.moveToNext()) add(rows.getString(0)) }
            }
        for (pendingReference in pending) {
            applyBackupLink(pendingReference, matches, duplicateCounts)
        }
    }

internal fun LibraryDatabase.translationAliases(id: String): List<String> =
    readableDatabase.rawQuery("SELECT aliases FROM backup_song_links WHERE applied_to=? LIMIT 1", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) emptyList() else JSONArray(c.getString(0)).let { a -> (0 until a.length()).map { a.getString(it) }.filter { it != id } }
    }

private fun LibraryDatabase.applyBackupLink(
    pendingReference: String,
    matches: Map<String, Set<String>>,
    duplicateCounts: Map<String, Int>,
) {
    val sql = writableDatabase
    sql.rawQuery("SELECT reference,fingerprint,aliases,favorite,user_order,original,translated,name FROM backup_song_links WHERE reference=?", arrayOf(pendingReference)).use { rows ->
        if (!rows.moveToFirst()) return@use
        val reference = rows.getString(0)
        val candidates = matches[rows.getString(1)].orEmpty()
        val aliases = JSONArray(rows.getString(2)).let { a -> (0 until a.length()).map { a.getString(it) } }
        val exact = aliases.filter { it in candidates }.distinct()
        val duplicates = duplicateCounts.getValue(rows.getString(1))
        val id = exact.singleOrNull() ?: candidates.singleOrNull()?.takeIf { duplicates == 1 } ?: return@use
        sql.execSQL("UPDATE songs SET favorite=MAX(favorite,?),user_order=COALESCE(user_order,?) WHERE id=?", arrayOf<Any?>(rows.getInt(3), if (rows.isNull(4)) null else rows.getInt(4), id))
        val hasLyrics = sql.rawQuery("SELECT 1 FROM lyric_overrides WHERE song_id=?", arrayOf(id)).use { it.moveToFirst() }
        if (!hasLyrics && rows.getString(5).isNotBlank()) setLyrics(id, LocalLyrics(rows.getString(5), rows.getString(6)), rows.getString(7))
        sql.execSQL("UPDATE backup_song_links SET applied_to=? WHERE reference=?", arrayOf(id, reference))
    }
}
