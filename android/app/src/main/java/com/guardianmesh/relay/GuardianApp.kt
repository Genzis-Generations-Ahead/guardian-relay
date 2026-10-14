package com.guardianmesh.relay

import android.app.Application
import android.content.Context
import com.guardianmesh.relay.data.GuardianRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * If the main app already has an Application class, just call Guardian.init(this) from its onCreate().
 *
 * This one also owns the two app-scoped things the UI layer needs: a coroutine scope that
 * outlives any screen, and the single [GuardianRepository]. They're handed out through
 * [repository] rather than a DI framework — one repository and one scope is not worth
 * Hilt, and keeping it this way means the core module stays dependency-free.
 *
 * Constructing the repository is also what starts mirroring Room into `Guardian.config`,
 * so the SOS core sees the user's contacts and backend URL from the first launch onward.
 */
class GuardianApp : Application() {

    val appScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    val repository: GuardianRepository by lazy { GuardianRepository.create(this, appScope) }

    override fun onCreate() {
        super.onCreate()
        Guardian.init(this)
        // Touch it once so the Room → Guardian.config mirror is live before any screen opens.
        repository
    }

    companion object {
        /**
         * The repository, from anywhere with a Context.
         *
         * Falls back to building one if the process was started by a service in a context
         * where the Application subclass isn't ours (ROM-specific restarts, instrumentation),
         * so a caller never has to handle a null.
         */
        fun repository(context: Context): GuardianRepository {
            val app = context.applicationContext
            return (app as? GuardianApp)?.repository
                ?: GuardianRepository.create(app, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        }
    }
}
