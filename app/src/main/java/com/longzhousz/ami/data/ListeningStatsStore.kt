package com.longzhousz.ami.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.longzhousz.ami.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

internal fun createListeningTables(db: SQLiteDatabase) {
    // Independent of songs: removing a source or a disposable cache cannot erase a yearbook.
    db.execSQL("""CREATE TABLE listening_tracks(id INTEGER PRIMARY KEY,music_key TEXT NOT NULL UNIQUE,
        title TEXT NOT NULL,artist_key TEXT NOT NULL,artist TEXT NOT NULL,album_key TEXT NOT NULL,
        album TEXT NOT NULL,cover TEXT,first_day INTEGER NOT NULL)""")
    db.execSQL("CREATE TABLE listening_song_day(day INTEGER NOT NULL,track INTEGER NOT NULL,millis INTEGER NOT NULL,PRIMARY KEY(day,track)) WITHOUT ROWID")
    db.execSQL("CREATE TABLE listening_hour(day INTEGER NOT NULL,hour INTEGER NOT NULL,millis INTEGER NOT NULL,PRIMARY KEY(day,hour)) WITHOUT ROWID")
    db.execSQL("CREATE TABLE listening_status(singleton INTEGER PRIMARY KEY CHECK(singleton=1),incomplete INTEGER NOT NULL DEFAULT 0)")
}

/** One bounded accumulator and writer. Nothing here loads the library or schedules a timer. */
class ListeningStatsStore(private val db: LibraryDatabase, private val scope: CoroutineScope) {
    private data class Bucket(val key: String, val day: LocalDate, val hour: Int)
    private val state = Any()
    private val accumulator = ListeningAccumulator()
    private val pending = LinkedHashMap<Bucket, ListeningSlice>()
    private val writer = Mutex()
    private var flushing = false
    private var incomplete = false
    internal val pendingCount get() = synchronized(state) { pending.size }

    fun sample(track: ListeningTrack?, playing: Boolean, elapsed: Long, wall: Long, zone: ZoneId) = synchronized(state) {
        accumulator.update(track, playing, elapsed, wall, zone) { slice ->
            if (slice.millis > 0) {
                val key = Bucket(slice.track.key, slice.day, slice.hour)
                val previous = pending[key]
                if (previous != null) pending[key] = slice.copy(millis = previous.millis + slice.millis)
                else if (pending.size < 256) pending[key] = slice
                else incomplete = true
            }
        }
    }

    fun flushAsync() {
        synchronized(state) {
            if (flushing || pending.isEmpty()) return
            flushing = true
        }
        scope.launch(Dispatchers.IO) {
            try { writer.withLock { flushPending() } }
            finally { synchronized(state) { flushing = false } }
        }
    }

    private fun flushPending() {
        while (true) {
            val (batch, lost) = synchronized(state) {
                if (pending.isEmpty()) return
                val rows = pending.values.toList(); pending.clear(); rows to incomplete
            }
            try { write(batch, lost) }
            catch (_: SQLiteException) {
                // Retry only at the next real playback checkpoint, with bounded memory.
                synchronized(state) {
                    for (slice in batch) {
                        val key = Bucket(slice.track.key, slice.day, slice.hour)
                        val newer = pending[key]
                        if (newer != null) pending[key] = newer.copy(millis = newer.millis + slice.millis)
                        else if (pending.size < 256) pending[key] = slice
                        else incomplete = true
                    }
                }
                return
            }
        }
    }

    internal fun write(batch: List<ListeningSlice>, lost: Boolean = false) = db.transaction {
        val sql = db.writableDatabase
        val ids = batch.groupBy { it.track.key }.mapValues { (_, rows) ->
            val track = rows.last().track
            val first = rows.minOf { it.day.toEpochDay() }
            sql.execSQL("INSERT OR IGNORE INTO listening_tracks(music_key,title,artist_key,artist,album_key,album,cover,first_day) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(track.key, track.title, track.artistKey, track.artist, track.albumKey, track.album, track.cover, first))
            sql.rawQuery("SELECT id,artist_key,artist,album_key,album,first_day,title,cover FROM listening_tracks WHERE music_key=?", arrayOf(track.key)).use { row ->
                check(row.moveToFirst())
                // A legacy restored queue must not replace a known artist/album ID with a name.
                val artistKey = if (track.artistKey.startsWith("netease-artist:") || !row.getString(1).startsWith("netease-artist:")) track.artistKey else row.getString(1)
                val artist = if (artistKey == track.artistKey) track.artist else row.getString(2)
                val albumKey = if (track.albumKey.startsWith("netease-album:") || !row.getString(3).startsWith("netease-album:")) track.albumKey else row.getString(3)
                val album = if (albumKey == track.albumKey) track.album else row.getString(4)
                val firstDay = minOf(first, row.getLong(5))
                val cover = track.cover ?: row.getString(7)
                if (artistKey != row.getString(1) || artist != row.getString(2) || albumKey != row.getString(3) || album != row.getString(4) ||
                    firstDay != row.getLong(5) || track.title != row.getString(6) || cover != row.getString(7)) {
                    sql.update("listening_tracks", ContentValues().apply {
                        put("title", track.title); put("artist_key", artistKey); put("artist", artist)
                        put("album_key", albumKey); put("album", album); put("cover", cover); put("first_day", firstDay)
                    }, "id=?", arrayOf(row.getLong(0).toString()))
                }
                row.getLong(0)
            }
        }
        for (slice in batch) {
            val day = slice.day.toEpochDay(); val id = ids.getValue(slice.track.key)
            sql.execSQL("INSERT OR IGNORE INTO listening_song_day(day,track,millis) VALUES(?,?,0)", arrayOf(day, id))
            sql.execSQL("UPDATE listening_song_day SET millis=millis+? WHERE day=? AND track=?", arrayOf(slice.millis, day, id))
            sql.execSQL("INSERT OR IGNORE INTO listening_hour(day,hour,millis) VALUES(?,?,0)", arrayOf<Any>(day, slice.hour))
            sql.execSQL("UPDATE listening_hour SET millis=millis+? WHERE day=? AND hour=?", arrayOf<Any>(slice.millis, day, slice.hour))
        }
        if (lost) sql.execSQL("INSERT OR REPLACE INTO listening_status(singleton,incomplete) VALUES(1,1)")
    }

    suspend fun report(period: ListeningPeriod, today: LocalDate = LocalDate.now()): ListeningReport = withContext(Dispatchers.IO) {
        writer.withLock {
            flushPending()
            db.transaction { readReport(period, today) }
        }
    }

    private fun readReport(period: ListeningPeriod, today: LocalDate): ListeningReport {
        val sql = db.readableDatabase
        val start = period.start.toEpochDay(); val end = period.end.toEpochDay()
        val queryEnd = minOf(end, today.plusDays(1).toEpochDay())
        val args = arrayOf(start.toString(), queryEnd.toString())
        fun scalar(query: String, values: Array<String>? = args): Long = sql.rawQuery(query, values).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0 }
        val firstDay = sql.rawQuery("SELECT MIN(first_day) FROM listening_tracks", null).use { if (it.moveToFirst() && !it.isNull(0)) LocalDate.ofEpochDay(it.getLong(0)) else null }
        val lost = scalar("SELECT incomplete FROM listening_status WHERE singleton=1", null) != 0L || synchronized(state) { incomplete }
        val total = scalar("SELECT SUM(millis) FROM listening_song_day WHERE day>=? AND day<?")
        val songs = scalar("SELECT COUNT(DISTINCT track) FROM listening_song_day WHERE day>=? AND day<?").toInt()
        val daily = sql.rawQuery("SELECT day,SUM(millis) FROM listening_song_day WHERE day>=? AND day<? GROUP BY day", args).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getLong(1)) }
        }
        val days = (start until end).map { day ->
            val date = LocalDate.ofEpochDay(day)
            ListeningDay(date, daily[day] ?: 0, firstDay != null && date >= firstDay && date <= today)
        }
        val top = sql.rawQuery("""SELECT t.music_key,t.title,SUM(s.millis) AS time,t.artist,t.cover
            FROM listening_song_day s JOIN listening_tracks t ON t.id=s.track WHERE s.day>=? AND s.day<?
            GROUP BY t.id ORDER BY time DESC,t.music_key LIMIT 10""", args).use { c ->
            buildList { while (c.moveToNext()) add(ListeningRank(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4))) }
        }
        fun groups(column: String, limit: Int): List<ListeningRank> = sql.rawQuery("""SELECT t.${column}_key,MAX(t.$column),SUM(s.millis) AS time,
            CASE WHEN COUNT(DISTINCT t.artist_key)>1 THEN '多位艺人' ELSE MAX(t.artist) END,MAX(t.cover)
            FROM listening_song_day s JOIN listening_tracks t ON t.id=s.track WHERE s.day>=? AND s.day<?
            GROUP BY t.${column}_key ORDER BY time DESC,t.${column}_key LIMIT $limit""", args).use { c ->
            buildList { while (c.moveToNext()) add(ListeningRank(c.getString(0), c.getString(1), c.getLong(2), if (column == "album") c.getString(3) else "", if (column == "album") c.getString(4) else null)) }
        }
        val artists = groups("artist", 5).let { rows ->
            val rest = total - rows.sumOf { it.millis }
            if (rest > 0) rows + ListeningRank("other", "其他", rest) else rows
        }
        val hours = MutableList(7 * 24) { 0L }
        sql.rawQuery("SELECT day,hour,millis FROM listening_hour WHERE day>=? AND day<?", args).use { c ->
            while (c.moveToNext()) {
                val weekday = LocalDate.ofEpochDay(c.getLong(0)).dayOfWeek.value - 1
                hours[weekday * 24 + c.getInt(1)] += c.getLong(2)
            }
        }
        val newTime = scalar("""SELECT SUM(s.millis) FROM listening_song_day s JOIN listening_tracks t ON t.id=s.track
            WHERE s.day>=? AND s.day<? AND t.first_day>=?""", arrayOf(start.toString(), queryEnd.toString(), start.toString()))
        val previous = period.move(-1)
        val elapsedDays = (minOf(today.plusDays(1), period.end).toEpochDay() - start).coerceAtLeast(0)
        val previousEnd = minOf(previous.end.toEpochDay(), previous.start.toEpochDay() + elapsedDays)
        val previousTime = if (!lost && firstDay != null && firstDay <= previous.start && firstDay <= period.start && elapsedDays > 0)
            scalar("SELECT SUM(millis) FROM listening_song_day WHERE day>=? AND day<?", arrayOf(previous.start.toEpochDay().toString(), previousEnd.toString())) else null
        return ListeningReport(period, firstDay, total, songs, days, top, artists, groups("album", 6), hours, newTime, previousTime, lost)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        writer.withLock {
            db.transaction {
                for (table in listOf("listening_song_day", "listening_hour", "listening_tracks", "listening_status")) db.writableDatabase.delete(table, null, null)
            }
            // No database work while holding the sampler lock on the playback thread.
            synchronized(state) {
                pending.clear(); accumulator.reset(); incomplete = false
            }
        }
    }
}
