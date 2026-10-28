package com.longzhousz.ami

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.longzhousz.ami.core.*
import com.longzhousz.ami.data.PhoneLyrics
import com.longzhousz.ami.data.TranslationClient
import com.longzhousz.ami.playback.PhoneMediaAccess
import com.longzhousz.ami.playback.PhoneSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

class PhoneMusicViewModel internal constructor(app: Application, val session: PhoneSession, private val source: suspend (Song) -> SongLyrics) : AndroidViewModel(app) {
    constructor(app: Application) : this(app,PhoneSession(app),PhoneLyrics((app as AmiApplication).repository)::load)
    private val repository=(app as AmiApplication).repository
    val lyrics=MutableStateFlow(CurrentLyrics())
    val cover=MutableStateFlow<Pair<String,String>?>(null)
    val message=MutableStateFlow<String?>(null)
    private var observing: Job?=null
    private var translation: Job?=null
    fun start() {
        if (observing!=null) return
        repository.prefs.edit().putBoolean("phoneFollowing",true).apply()
        observing=viewModelScope.launch {
            session.start(stopLocal=true)
            launch { PhoneMediaAccess.connected.collect { if (it) { session.start(stopLocal=true);session.refresh() } else if (!PhoneSession.permitted(getApplication())) session.stop() } }
            launch { session.state.map { it.song }.distinctUntilChangedBy { it?.id }.collectLatest { song ->
                if (lyrics.value.songId==song?.id && lyrics.value.document?.original?.isNotBlank()==true) return@collectLatest
                translation?.cancel();lyrics.value=CurrentLyrics(song?.id,song?.lyricsVersion.orEmpty())
                if (song==null) return@collectLatest
                try {
                    val doc=withContext(Dispatchers.IO) { source(song).also { it.lines } };ensureActive()
                    val translated=withContext(Dispatchers.IO) { TranslationClient(repository).cached(song,doc.lines) }
                    ensureActive()
                    if (session.state.value.song?.id==song.id) lyrics.value=CurrentLyrics(song.id,song.lyricsVersion,doc,translated)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (session.state.value.song?.id==song.id) lyrics.value=CurrentLyrics(song.id,song.lyricsVersion,SongLyrics()) }
            } }
            launch { session.state.map { it.song?.id to it.artwork }.distinctUntilChanged().collectLatest { (id,bitmap) ->
                cover.value=null
                if (id==null || bitmap==null) return@collectLatest
                val file=try { withContext(Dispatchers.IO) {
                    val dir=File(getApplication<Application>().cacheDir,"phone-artwork").apply { mkdirs() }
                    val target=File(dir,"${digest(id)}.jpg")
                    val image=if (maxOf(bitmap.width,bitmap.height)>600) {
                        val scale=600.0/maxOf(bitmap.width,bitmap.height)
                        Bitmap.createScaledBitmap(bitmap,(bitmap.width*scale).toInt().coerceAtLeast(1),(bitmap.height*scale).toInt().coerceAtLeast(1),true)
                    } else bitmap
                    try { target.outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG,88,it)) } }
                    finally { if (image!==bitmap) image.recycle() }
                    dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
                    target
                } } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { return@collectLatest }
                ensureActive(); if (session.state.value.song?.id==id) cover.value=id to file.path
            } }
            awaitCancellation()
        }
    }
    fun stop() { observing?.cancel();observing=null;session.stop() }
    fun translate(force: Boolean=false) {
        val song=session.state.value.song ?: return
        val doc=lyrics.value.document ?: return
        if (lyrics.value.translating || doc.lines.isEmpty()) return
        translation=viewModelScope.launch {
            lyrics.value=lyrics.value.copy(translating=true)
            try {
                val result=withContext(Dispatchers.IO) { TranslationClient(repository).translate(song.copy(lyrics=doc.original,lyricTranslation=doc.translated),doc.lines,force) }
                if (session.state.value.song?.id==song.id && lyrics.value.document===doc) lyrics.value=lyrics.value.copy(translation=result,translating=false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (session.state.value.song?.id==song.id) { lyrics.value=lyrics.value.copy(translating=false);message.value=error.message?.take(120) ?: "翻译暂未完成" } }
        }
    }
    override fun onCleared() { stop();super.onCleared() }
}
