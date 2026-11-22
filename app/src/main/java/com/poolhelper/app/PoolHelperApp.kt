package com.poolhelper.app

import android.app.Application

/** Installs the crash handler before anything else in the process runs. */
class PoolHelperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}
