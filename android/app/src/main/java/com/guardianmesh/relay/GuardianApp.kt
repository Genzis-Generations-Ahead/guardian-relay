package com.guardianmesh.relay

import android.app.Application

/** If the main app already has an Application class, just call Guardian.init(this) from its onCreate(). */
class GuardianApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Guardian.init(this)
    }
}
