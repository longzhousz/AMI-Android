package com.longzhousz.ami.data

import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.longzhousz.ami.core.ListeningScale
import com.longzhousz.ami.core.TranslationModels
import org.json.JSONArray
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CodingErrorAction

/** A versioned, allowlisted document, not a database dump. All methods run on the IO dispatcher. */
internal class PersonalDataBackup(
    private val library: LibraryDatabase,
    private val directory: File,
) {
    private data class Table(
        val name: String,
        val definition: String,
        val maximum: Int = 400_000,
    ) {
        val columns = definition.split(',').map { it.trim().substringBefore(' ') }
        val numbers = definition.split(',').map { it.contains("INTEGER") }
        val nullable = definition.split(',').map { !it.contains("NOT NULL") && !it.contains("PRIMARY KEY") }
    }

    companion object {
        const val MAX_BYTES = 64L * 1024 * 1024
        private val tables =
            listOf(
                Table("listening_origins", "id INTEGER PRIMARY KEY,source TEXT NOT NULL UNIQUE", 50_000),
                Table("listening_tracks", "id INTEGER PRIMARY KEY,music_key TEXT NOT NULL UNIQUE,title TEXT NOT NULL,artist_key TEXT NOT NULL,artist TEXT NOT NULL,album_key TEXT NOT NULL,album TEXT NOT NULL,cover TEXT,first_day INTEGER NOT NULL", 50_000),
                Table("listening_song_day", "day INTEGER NOT NULL,track INTEGER NOT NULL,origin INTEGER NOT NULL,millis INTEGER NOT NULL"),
                Table("listening_hour", "day INTEGER NOT NULL,hour INTEGER NOT NULL,origin INTEGER NOT NULL,millis INTEGER NOT NULL"),
                Table("listening_status", "singleton INTEGER PRIMARY KEY,incomplete INTEGER NOT NULL", 1),
                Table("backup_song_links", "reference TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,title TEXT NOT NULL,artist TEXT NOT NULL,album TEXT NOT NULL,duration INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER,track INTEGER NOT NULL,disc INTEGER NOT NULL,favorite INTEGER NOT NULL,user_order INTEGER,original TEXT NOT NULL,translated TEXT NOT NULL,name TEXT NOT NULL,aliases TEXT NOT NULL", 50_000),
                Table("translations", "cache_key TEXT PRIMARY KEY,body TEXT NOT NULL", 50_000),
                Table("netease_lyrics", "music_id TEXT PRIMARY KEY,original TEXT NOT NULL,translated TEXT NOT NULL,name TEXT NOT NULL", 50_000),
            )
        private val identifier = Regex("[A-Za-z0-9_:-]{1,160}")
        private val hash = Regex("[a-f0-9]{64}")
        private val origin = Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
        private val picture = Regex("https://p[1-4]\\.music\\.126\\.net/[^?#\\s]{1,1900}")

        fun settings(prefs: SharedPreferences): Map<String, String> =
            prefs.all.filterKeys { it in BackupSettings.keys }.mapValues { it.value.toString() }.also {
                it.forEach { (key, value) -> BackupSettings.validate(key, value) }
            }
    }

    fun write(
        output: OutputStream,
        settings: Map<String, String>,
        checkActive: () -> Unit = {},
    ) {
        settings.forEach { (key, value) -> BackupSettings.validate(key, value) }
        library.transaction {
            library.captureBackupLinks()
            val writer = JsonWriter(BoundedOutput(output).writer(Charsets.UTF_8))
            writer
                .beginObject()
                .name("format")
                .value("ami.personal")
                .name("version")
                .value(1)
            writer.name("settings").beginObject()
            settings.forEach { (key, value) -> writer.name(key).value(value) }
            writer.endObject().name("tables").beginObject()
            for (table in tables) {
                writer.name(table.name).beginArray()
                library.readableDatabase.rawQuery("SELECT ${table.columns.joinToString()} FROM ${table.name}", null).use { rows ->
                    var count = 0
                    while (rows.moveToNext()) {
                        if (++count % 128 == 0) checkActive()
                        require(count <= table.maximum)
                        writer.beginArray()
                        for (i in table.columns.indices) {
                            when {
                                rows.isNull(i) -> writer.nullValue()
                                table.numbers[i] -> writer.value(rows.getLong(i))
                                table.columns[i] == "cover" -> writer.value(rows.getString(i).takeIf(picture::matches))
                                else -> writer.value(rows.getString(i).also { validateField(table.columns[i], it) })
                            }
                        }
                        writer.endArray()
                    }
                }
                writer.endArray()
            }
            writer.endObject().endObject()
            writer.flush()
            checkActive()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Close staging on cancellation and parser errors alike.
    fun read(
        input: InputStream,
        checkActive: () -> Unit = {},
    ): RestoreData {
        val file = File.createTempFile("ami-restore-", ".db", directory)
        val stage = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            for (table in tables) stage.execSQL("CREATE TABLE ${table.name}(${table.definition})")
            stage.execSQL("CREATE UNIQUE INDEX song_counter ON listening_song_day(day,track,origin)")
            stage.execSQL("CREATE UNIQUE INDEX hour_counter ON listening_hour(day,hour,origin)")
            val decoder =
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            val reader = JsonReader(java.io.InputStreamReader(BoundedInput(input), decoder))
            val seen = mutableSetOf<String>()
            val settings = linkedMapOf<String, String>()
            stage.beginTransaction()
            try {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(seen.add(name))
                    when (name) {
                        "format" -> require(reader.nextString() == "ami.personal")
                        "version" -> require(integer(reader) == 1L)
                        "settings" -> readSettings(reader, settings)
                        "tables" -> readTables(reader, stage, checkActive)
                        else -> error("Unknown backup field")
                    }
                }
                reader.endObject()
                require(reader.peek() == JsonToken.END_DOCUMENT && seen == setOf("format", "version", "settings", "tables"))
                validateStage(stage)
                checkActive()
                stage.setTransactionSuccessful()
            } finally {
                stage.endTransaction()
            }
            return RestoreData(file, stage, settings)
        } catch (failure: Throwable) {
            // This is the resource boundary: close and remove staging on cancellation and parse failure alike.
            stage.close()
            SQLiteDatabase.deleteDatabase(file)
            throw failure
        }
    }

    private fun readSettings(
        reader: JsonReader,
        values: MutableMap<String, String>,
    ) {
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            require(key in BackupSettings.keys && key !in values && reader.peek() == JsonToken.STRING)
            val value = reader.nextString()
            BackupSettings.validate(key, value)
            values[key] = value
        }
        reader.endObject()
    }

    private fun readTables(
        reader: JsonReader,
        stage: SQLiteDatabase,
        checkActive: () -> Unit,
    ) {
        val seen = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            val table = tables.single { it.name == name }
            require(seen.add(name))
            reader.beginArray()
            stage.compileStatement("INSERT INTO $name VALUES(${table.columns.joinToString { "?" }})").use { insert ->
                var count = 0
                while (reader.hasNext()) {
                    if (++count % 128 == 0) checkActive()
                    require(count <= table.maximum)
                    reader.beginArray()
                    for (i in table.columns.indices) {
                        when {
                            reader.peek() == JsonToken.NULL -> {
                                require(table.nullable[i])
                                reader.nextNull()
                                insert.bindNull(i + 1)
                            }

                            table.numbers[i] -> {
                                insert.bindLong(i + 1, integer(reader))
                            }

                            else -> {
                                require(reader.peek() == JsonToken.STRING)
                                val value = reader.nextString()
                                validateField(table.columns[i], value)
                                insert.bindString(i + 1, value)
                            }
                        }
                    }
                    reader.endArray()
                    insert.executeInsert()
                }
            }
            reader.endArray()
        }
        reader.endObject()
        require(seen == tables.map { it.name }.toSet())
    }

    private fun integer(reader: JsonReader): Long {
        require(reader.peek() == JsonToken.NUMBER)
        return reader.nextString().also { require(Regex("-?(0|[1-9][0-9]{0,17})").matches(it)) }.toLong()
    }

    private fun validateStage(stage: SQLiteDatabase) {
        fun empty(query: String) = require(!stage.rawQuery(query, null).use { it.moveToFirst() })
        empty("SELECT 1 FROM listening_song_day s LEFT JOIN listening_tracks t ON t.id=s.track LEFT JOIN listening_origins o ON o.id=s.origin WHERE t.id IS NULL OR o.id IS NULL OR s.day NOT BETWEEN 0 AND 85000 OR s.millis NOT BETWEEN 1 AND 100000000 LIMIT 1")
        empty("SELECT 1 FROM listening_hour h LEFT JOIN listening_origins o ON o.id=h.origin WHERE o.id IS NULL OR h.day NOT BETWEEN 0 AND 85000 OR h.hour NOT BETWEEN 0 AND 23 OR h.millis NOT BETWEEN 1 AND 100000000 LIMIT 1")
        empty("SELECT 1 FROM listening_tracks WHERE id<1 OR first_day NOT BETWEEN 0 AND 85000 LIMIT 1")
        empty("SELECT 1 FROM listening_origins WHERE id<1 LIMIT 1")
        empty("SELECT 1 FROM listening_status WHERE singleton<>1 OR incomplete NOT IN (0,1) LIMIT 1")
        empty("SELECT 1 FROM backup_song_links WHERE duration NOT BETWEEN 0 AND 604800000 OR start NOT BETWEEN 0 AND 604800000 OR end<start OR end>604800000 OR track NOT BETWEEN 0 AND 100000 OR disc NOT BETWEEN 0 AND 100000 OR favorite NOT IN (0,1) OR user_order<0 LIMIT 1")
        // Every day has the same denominator in rankings and the hour chart, including imported history.
        empty("SELECT 1 FROM (SELECT day,origin,SUM(millis) total FROM listening_song_day GROUP BY day,origin EXCEPT SELECT day,origin,SUM(millis) FROM listening_hour GROUP BY day,origin) LIMIT 1")
        empty("SELECT 1 FROM (SELECT day,origin,SUM(millis) total FROM listening_hour GROUP BY day,origin EXCEPT SELECT day,origin,SUM(millis) FROM listening_song_day GROUP BY day,origin) LIMIT 1")
    }

    private fun validateField(
        column: String,
        value: String,
    ) {
        require(value.length <= 2_000_000)
        when (column) {
            "reference", "music_key", "artist_key", "album_key" -> {
                require(identifier.matches(value))
            }

            "fingerprint", "cache_key" -> {
                require(hash.matches(value))
            }

            "source" -> {
                require(origin.matches(value))
            }

            "cover" -> {
                require(picture.matches(value))
            }

            "music_id" -> {
                require(Regex("[0-9]{1,20}").matches(value))
            }

            "title", "artist", "album", "name" -> {
                require(value.length <= 2048)
            }

            "aliases" -> {
                val aliases = JSONArray(value)
                require(aliases.length() in 1..32)
                for (i in 0 until aliases.length()) require(identifier.matches(aliases.getString(i)))
            }

            "body" -> {
                val lines = JSONArray(value)
                require(lines.length() <= 3000)
                for (i in 0 until lines.length()) require(lines.get(i) is String)
            }
        }
    }

    inner class RestoreData internal constructor(
        private val file: File,
        private val stage: SQLiteDatabase,
        val settings: Map<String, String>,
    ) : AutoCloseable {
        fun merge(checkActive: () -> Unit = {}) =
            library.transaction {
                mergePersonalData(library, stage, checkActive)
                library.applyBackupLinks()
                checkActive()
            }

        override fun close() {
            stage.close()
            SQLiteDatabase.deleteDatabase(file)
        }
    }

    private class BoundedInput(
        input: InputStream,
    ) : FilterInputStream(input) {
        private var bytes = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = `in`.read(buffer, offset, length).also { if (it > 0) count(it) }

        private fun count(size: Int) {
            bytes += size
            require(bytes <= MAX_BYTES)
        }
    }

    private class BoundedOutput(
        output: OutputStream,
    ) : FilterOutputStream(output) {
        private var bytes = 0L

        override fun write(value: Int) {
            count(1)
            out.write(value)
        }

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) {
            count(length)
            out.write(buffer, offset, length)
        }

        private fun count(size: Int) {
            bytes += size
            require(bytes <= MAX_BYTES)
        }
    }
}

internal object BackupSettings {
    val keys = setOf("theme", "style", "translationModels.selection", "translationModels.autoUpdate", "onlineCacheLimit", "listeningScale")

    fun validate(
        key: String,
        value: String,
    ) {
        require(
            when (key) {
                "theme" -> value in setOf("system", "light", "dark")
                "style" -> value.length <= 10_000
                "translationModels.selection" -> TranslationModels.validSelection(value)
                "translationModels.autoUpdate" -> value in setOf("true", "false")
                "onlineCacheLimit" -> value.toLongOrNull() in OnlineAudioCache.LIMITS
                "listeningScale" -> value in ListeningScale.entries.map { it.name }
                else -> false
            },
        )
    }
}
