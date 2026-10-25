package com.longzhousz.ami.data

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import coil.Coil
import coil.memory.MemoryCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.AmiApplication
import com.longzhousz.ami.core.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FavoriteUpdateTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as AmiApplication
    @Test fun repeatedTapsFromAnOldSongToggleTheCurrentRecordAndUpdateAlbumViews()=runBlocking(Dispatchers.IO) {
        val repo=app.repository
        val prefix="qa-favorite-${System.nanoTime()}"
        val songs=(0 until 1000).map { Song("$prefix-$it","file:///unused",folder=prefix,title="Track $it",album="Album ${it/100}",importedAt=it.toLong()) }
        try {
            repo.load()
            repo.db.transaction { songs.forEach(repo.db::upsert) };repo.reload()
            val before=repo.library.value
            val unchanged=before.albums.first { it.title=="Album 0" }
            val started=System.nanoTime()
            repo.favorite(songs[812])
            val firstToggleMs=(System.nanoTime()-started)/1e6
            val after=repo.library.value
            assertTrue(after.songsById.getValue(songs[812].id).favorite)
            assertFalse(before.songsById.getValue(songs[812].id).favorite)
            assertSame(unchanged,after.albumsByKey[unchanged.key])
            assertTrue(after.albums.single { it.title=="Album 8" }.songs.single { it.id==songs[812].id }.favorite)
            val secondStarted=System.nanoTime()
            repo.favorite(songs[812]) // The original false-valued object is deliberately stale.
            val secondToggleMs=(System.nanoTime()-secondStarted)/1e6
            assertFalse(repo.library.value.songsById.getValue(songs[812].id).favorite)
            assertFalse(repo.db.songSummaries().single { it.id==songs[812].id }.favorite)
            val out=File(app.filesDir,"qa/performance/favorite-local-update.json").apply { parentFile!!.mkdirs() }
            out.writeText(JSONObject().put("tracks",1000).put("twoToggleMs",firstToggleMs+secondToggleMs).put("staleTapCorrect",true).put("unaffectedAlbumReused",true).toString(2))
        } finally { repo.db.transaction { songs.forEach { repo.db.remove(it.id) } };repo.reload() }
    }
    @Test fun memoryPressureClearsOptionalCachesAndLyricsRemainReadable()=runBlocking(Dispatchers.IO) {
        val repo=app.repository
        val song=Song("qa-pressure-${System.nanoTime()}","file:///unused",folder="qa",title="Pressure",lyrics="[00:00]Keep the original lyrics")
        val key=MemoryCache.Key(song.id)
        val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        try {
            repo.db.upsert(song)
            val summary=repo.db.songSummaries().single { it.id==song.id }
            assertEquals(song.lyrics,repo.lyrics.load(summary).original)
            val images=Coil.imageLoader(app).memoryCache!!
            images[key]=MemoryCache.Value(bitmap)
            assertTrue(repo.lyrics.cachedBytes>0);assertNotNull(images[key])
            instrumentation.runOnMainSync { app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) }
            assertEquals(0,repo.lyrics.cachedBytes);assertNull(images[key])
            assertEquals(song.lyrics,repo.lyrics.load(summary).original)
        } finally { repo.db.remove(song.id);repo.lyrics.trim();repo.reload();bitmap.recycle() }
    }
}
