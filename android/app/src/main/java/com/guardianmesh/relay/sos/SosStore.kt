package com.guardianmesh.relay.sos

import android.content.Context
import org.json.JSONObject

/** Persists the active SOS so tracking resumes if Android kills and restarts the process. */
internal object SosStore {
    private const val PREFS = "guardian_sos"
    private const val KEY = "active"

    data class Saved(val event: SosEvent, val seq: Int, val lastSmsAt: Long, val bleActive: Boolean)

    fun save(ctx: Context, event: SosEvent, seq: Int, lastSmsAt: Long, bleActive: Boolean) {
        val o = JSONObject()
            .put("event", event.toJson())
            .put("seq", seq)
            .put("lastSmsAt", lastSmsAt)
            .put("ble", bleActive)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    fun load(ctx: Context): Saved? = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let {
            val o = JSONObject(it)
            Saved(SosEvent.fromJson(o.getJSONObject("event")), o.optInt("seq"), o.optLong("lastSmsAt"), o.optBoolean("ble"))
        }
    } catch (_: Throwable) { null }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
