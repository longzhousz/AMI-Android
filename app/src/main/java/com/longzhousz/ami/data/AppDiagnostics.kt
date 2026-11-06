package com.longzhousz.ami.data

import android.os.StrictMode
import com.longzhousz.ami.core.ErrorCode
import com.longzhousz.ami.core.ErrorJournal
import java.util.concurrent.Executor

object AppDiagnostics {
    val errors = ErrorJournal()

    fun enableDevelopmentChecks() {
        val listener = Executor { it.run() }
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyListener(listener) { errors.record(ErrorCode.STRICT_MODE, it) }
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy
                .Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyListener(listener) { errors.record(ErrorCode.STRICT_MODE, it) }
                .build(),
        )
    }
}
