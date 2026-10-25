package com.longzhousz.ami.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/** Real pre-split layouts; migration tests must not pretend a new database is an old schema. */
internal fun legacyLibrary(context: Context, name: String, version: Int, seed: (SQLiteDatabase) -> Unit) {
    context.deleteDatabase(name)
    val path=context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
    SQLiteDatabase.openOrCreateDatabase(path,null).use { db ->
        db.execSQL("""CREATE TABLE songs (id TEXT PRIMARY KEY,uri TEXT NOT NULL,source TEXT NOT NULL,folder TEXT NOT NULL,
            title TEXT NOT NULL,artist TEXT NOT NULL,album TEXT NOT NULL,album_artist TEXT NOT NULL,track INTEGER NOT NULL,
            disc INTEGER NOT NULL,duration INTEGER NOT NULL,format TEXT NOT NULL,cover TEXT,lyrics TEXT NOT NULL,start INTEGER NOT NULL,
            end INTEGER,user_order INTEGER,favorite INTEGER NOT NULL DEFAULT 0,imported_at INTEGER NOT NULL,netease_id TEXT,lyric_translated TEXT NOT NULL DEFAULT '')""")
        db.execSQL("CREATE TABLE translations (cache_key TEXT PRIMARY KEY,body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE actions (id INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL,name TEXT NOT NULL,source TEXT NOT NULL,status TEXT NOT NULL)")
        db.execSQL("CREATE INDEX songs_netease_id ON songs(netease_id)")
        db.execSQL("CREATE TABLE netease_lyrics (music_id TEXT PRIMARY KEY,original TEXT NOT NULL,translated TEXT NOT NULL,name TEXT NOT NULL,source TEXT NOT NULL)")
        db.execSQL("CREATE TABLE lyric_overrides (song_id TEXT PRIMARY KEY REFERENCES songs(id) ON DELETE CASCADE,original TEXT NOT NULL,translated TEXT NOT NULL,name TEXT NOT NULL)")
        if (version>=3) {
            db.execSQL("CREATE INDEX songs_import_order ON songs(imported_at,id)")
            db.execSQL("CREATE TABLE scan_cache (cache_key TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,ids TEXT NOT NULL,sources TEXT NOT NULL,artifacts TEXT NOT NULL)")
            db.execSQL("CREATE TABLE playback_queue (position INTEGER PRIMARY KEY,song_id TEXT NOT NULL)")
            db.execSQL("CREATE TABLE playback_checkpoint (singleton INTEGER PRIMARY KEY CHECK(singleton=1),queue_index INTEGER NOT NULL,position_ms INTEGER NOT NULL,repeat_mode INTEGER NOT NULL,shuffle INTEGER NOT NULL)")
        }
        seed(db);db.version=version
    }
}
