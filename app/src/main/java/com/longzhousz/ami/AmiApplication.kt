package com.longzhousz.ami

import android.app.Application
import com.longzhousz.ami.data.LibraryRepository

class AmiApplication : Application() {
    val repository by lazy { LibraryRepository(this) }
}
