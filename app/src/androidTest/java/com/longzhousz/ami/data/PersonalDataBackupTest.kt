package com.longzhousz.ami.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PersonalDataBackupTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()
    private val databases = mutableListOf<LibraryDatabase>()
    private val job = SupervisorJob()
    private val day = LocalDate.of(2026, 10, 2)
    private val period = ListeningPeriod(ListeningScale.MONTH, day)
    private val song = Song(digest("old-file"), "file:///private/audio", folder = "content://private/folder", title = "迁移的歌", artist = "艺人", album = "专辑", duration = 180000, track = 1, disc = 1, favorite = true)
    private val track = song.listeningTrack()

    private fun database(): LibraryDatabase {
        val name = "qa-backup-${System.nanoTime()}.db"
        names += name
        return LibraryDatabase(context, name).also { databases += it }
    }

    private fun store(db: LibraryDatabase) = ListeningStatsStore(db, CoroutineScope(job + Dispatchers.IO))

    private fun codec(db: LibraryDatabase) = PersonalDataBackup(db, context.cacheDir)

    private fun export(
        db: LibraryDatabase,
        settings: Map<String, String> = emptyMap(),
    ): ByteArray = ByteArrayOutputStream().also { codec(db).write(it, settings) }.toByteArray()

    private fun restore(
        db: LibraryDatabase,
        bytes: ByteArray,
    ) = codec(db).read(ByteArrayInputStream(bytes)).use {
        it.merge()
        it.settings
    }

    private fun record(
        db: LibraryDatabase,
        millis: Long,
    ) = store(db).write(listOf(ListeningSlice(track, day, 12, millis)))

    private suspend fun total(db: LibraryDatabase): Long =
        store(db).report(period, day).let {
            assertEquals(it.totalMillis, it.hours.sum())
            assertEquals(it.totalMillis, it.artists.sumOf { rank -> rank.millis })
            it.totalMillis
        }

    @After fun cleanup() {
        runBlocking { job.cancelAndJoin() }
        databases.forEach { it.close() }
        names.forEach { context.deleteDatabase(it) }
    }

    @Test fun repeatIncrementalAndThreeDeviceImportsNeverDoubleCountOrEraseNewListening() =
        runBlocking {
            val a = database()
            val b = database()
            val c = database()
            record(a, 1000)
            val first = export(a)
            record(b, 2000)
            restore(b, first)
            restore(b, first)
            assertEquals(3000L, total(b))
            record(a, 1500)
            restore(b, export(a))
            restore(b, first) // Older snapshots cannot reduce the same origin's counters.
            assertEquals(4500L, total(b))
            record(c, 500)
            restore(c, export(b))
            restore(c, export(a))
            assertEquals(5000L, total(c))
            record(c, 700) // Native writes must only change origin 1, never multiply imported rows.
            assertEquals(5700L, total(c))
        }

    @Test fun clearingHistoryRotatesItsOriginWithoutChangingOtherPersonalData() =
        runBlocking {
            val a = database()
            val b = database()
            a.upsert(song)
            a.favorite(song.id, true)
            record(a, 1000)
            val old = export(a)
            store(a).clear()
            record(a, 500)
            restore(b, old)
            restore(b, export(a))
            assertEquals(1500L, total(b))
            assertTrue(a.songSummaries().single().favorite)
        }

    @Test fun realVersionFiveMigrationKeepsHistoryAndGivesItOneStableOrigin() =
        runBlocking {
            val name = "qa-backup-v5-${System.nanoTime()}.db"
            names += name
            legacyLibrary(context, name, 5) { old ->
                old.execSQL("CREATE TABLE listening_tracks(id INTEGER PRIMARY KEY,music_key TEXT NOT NULL UNIQUE,title TEXT NOT NULL,artist_key TEXT NOT NULL,artist TEXT NOT NULL,album_key TEXT NOT NULL,album TEXT NOT NULL,cover TEXT,first_day INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE listening_song_day(day INTEGER NOT NULL,track INTEGER NOT NULL,millis INTEGER NOT NULL,PRIMARY KEY(day,track)) WITHOUT ROWID")
                old.execSQL("CREATE TABLE listening_hour(day INTEGER NOT NULL,hour INTEGER NOT NULL,millis INTEGER NOT NULL,PRIMARY KEY(day,hour)) WITHOUT ROWID")
                old.execSQL("CREATE TABLE listening_status(singleton INTEGER PRIMARY KEY CHECK(singleton=1),incomplete INTEGER NOT NULL DEFAULT 0)")
                old.execSQL("INSERT INTO listening_tracks VALUES(1,'old-track','老歌','artist','艺人','album','专辑',NULL,?)", arrayOf(day.toEpochDay()))
                old.execSQL("INSERT INTO listening_song_day VALUES(?,1,1234)", arrayOf(day.toEpochDay()))
                old.execSQL("INSERT INTO listening_hour VALUES(?,12,1234)", arrayOf(day.toEpochDay()))
            }
            val old = LibraryDatabase(context, name).also { databases += it }
            assertEquals(6, old.readableDatabase.version)
            assertEquals(1234L, total(old))
            val another = database()
            restore(another, export(old))
            restore(another, export(old))
            assertEquals(1234L, total(another))
            record(old, 1000)
            assertEquals(2234L, total(old))
        }

    @Test fun backupExcludesCredentialsMediaPathsAndPlatformGrants() {
        val db = database()
        db.upsert(song.copy(cover = "/private/cover.jpg"))
        db.setLyrics(song.id, LocalLyrics("[00:00]原文", "译文"), "手动歌词")
        record(db, 1000)
        val name = "qa-backup-prefs-${System.nanoTime()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            prefs
                .edit()
                .putString("theme", "dark")
                .putString("style", "简洁")
                .putString("Cookie", "COOKIE-SECRET")
                .putString("apiKey", "API-SECRET")
                .putStringSet("roots", setOf("content://private/folder"))
                .putBoolean("bridgeEnabled", true)
                .putString("bridgeToken", "BRIDGE-SECRET")
                .commit()
            val json = export(db, PersonalDataBackup.settings(prefs)).toString(Charsets.UTF_8)
            listOf("COOKIE-SECRET", "API-SECRET", "BRIDGE-SECRET", "file:///", "content://", "/private/", "bridgeEnabled").forEach { assertFalse(it, json.contains(it)) }
            val restored = restore(database(), json.toByteArray())
            assertEquals(mapOf("theme" to "dark", "style" to "简洁"), restored)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun favoriteAndManualLyricsWaitForFolderScanAndSurviveAnotherMove() {
        val a = database()
        a.upsert(song)
        a.favorite(song.id, true)
        a.setLyrics(song.id, LocalLyrics("[00:00]原文", "译文"), "关联歌词")
        val key = digest("saved-translation")
        a.cacheTranslation(key, "[\"保留译文\"]")
        val b = database()
        val saved = export(a)
        restore(b, saved)
        assertTrue(b.songSummaries().isEmpty())
        val moved = song.copy(id = digest("new-file"), uri = "content://new", favorite = false)
        b.upsert(moved)
        b.applyBackupLinks()
        assertTrue(b.songSummaries().single().favorite)
        assertEquals("[00:00]原文", b.songLyrics(moved.id).original)
        assertEquals(listOf(song.id), b.translationAliases(moved.id))
        b.toggleFavorite(moved.id)
        b.setLyrics(moved.id, LocalLyrics("[00:00]新关联", ""), "当前关联")
        restore(b, saved)
        assertFalse(b.songSummaries().single().favorite)
        assertEquals("[00:00]新关联", b.songLyrics(moved.id).original)
        val c = database()
        restore(c, export(b))
        val third = moved.copy(id = digest("third-file"))
        c.upsert(third)
        c.applyBackupLinks()
        assertEquals("[00:00]新关联", c.songLyrics(third.id).original)
        assertEquals(setOf(song.id, moved.id), c.translationAliases(third.id).toSet())
        assertEquals("[\"保留译文\"]", c.translation(key))
    }

    @Test fun identicalMetadataDoesNotAttachFavoritesToAnArbitraryDuplicate() {
        val a = database()
        a.upsert(song)
        a.favorite(song.id, true)
        val saved = export(a)
        val b = database()
        b.upsert(song.copy(id = "duplicate-a", favorite = false))
        b.upsert(song.copy(id = "duplicate-b", favorite = false))
        restore(b, saved)
        assertTrue(b.songSummaries().none { it.favorite })
        b.upsert(song.copy(favorite = false))
        b.applyBackupLinks()
        assertEquals(listOf(song.id), b.songSummaries().filter { it.favorite }.map { it.id })
    }

    @Test fun corruptFutureDuplicateAndSecretBearingDocumentsCannotChangeExistingData() =
        runBlocking {
            val a = database()
            record(a, 1000)
            val bytes = export(a)
            val b = database()
            record(b, 2000)
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val variants =
                listOf(
                    bytes.copyOf(bytes.size / 2),
                    JSONObject(json.toString()).put("version", 99).toString().toByteArray(),
                    JSONObject(json.toString()).apply { getJSONObject("settings").put("apiKey", "SECRET") }.toString().toByteArray(),
                    JSONObject(json.toString()).apply { getJSONObject("tables").getJSONArray("listening_origins").let { it.put(it.getJSONArray(0)) } }.toString().toByteArray(),
                    JSONObject(json.toString()).apply { getJSONObject("tables").getJSONArray("listening_hour").getJSONArray(0).put(3, -1) }.toString().toByteArray(),
                    JSONObject(json.toString()).apply { getJSONObject("tables").put("listening_origins", JSONArray()) }.toString().toByteArray(),
                    json.toString().replace("\"version\":1", "\"version\":1,\"version\":1").toByteArray(),
                )
            for (invalid in variants) {
                assertThrows(Exception::class.java) { restore(b, invalid) }
                assertEquals(2000L, total(b))
            }
            assertTrue(
                context.cacheDir
                    .listFiles()
                    .orEmpty()
                    .none { it.name.startsWith("ami-restore-") },
            )
        }

    @Test fun cancellationInTheMergeRollsBackEveryTable() =
        runBlocking {
            val a = database()
            repeat(8) { n -> store(a).write(listOf(ListeningSlice(track.copy(key = "track:$n"), day, 12, 1000))) }
            val b = database()
            record(b, 2000)
            val before = export(b)
            codec(b).read(ByteArrayInputStream(export(a))).use { document ->
                var operations = 0
                assertThrows(CancellationException::class.java) { document.merge { if (++operations == 3) throw CancellationException("test") } }
            }
            assertEquals(2000L, total(b))
            assertArrayEquals(before, export(b))
        }
}
