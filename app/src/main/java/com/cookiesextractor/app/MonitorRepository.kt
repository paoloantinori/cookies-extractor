package com.cookiesextractor.app

import android.content.Context
import org.json.JSONObject

/**
 * Persists the monitor configuration and seen-state bookkeeping (COK-26). Everything the
 * user can configure (gateway URL, token, poll period, enabled) lives here; nothing is
 * hardcoded beyond defaults. Uses applicationContext so it never retains an Activity.
 */
class MonitorRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** The state-document URL; null/blank means unconfigured. */
    var gatewayUrl: String?
        get() = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_URL, value?.trim()?.trimEnd('/')).apply()

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_TOKEN, value?.trim()).apply()

    /** Poll period in minutes; clamped to the platform JobScheduler floor and a sane ceiling. */
    var periodMinutes: Int
        get() = prefs.getInt(KEY_PERIOD, DEFAULT_PERIOD_MINUTES)
        set(value) = prefs.edit().putInt(KEY_PERIOD, value.coerceIn(MIN_PERIOD_MINUTES, MAX_PERIOD_MINUTES)).apply()

    var lastResult: String?
        get() = prefs.getString(KEY_LAST_RESULT, null)
        set(value) = prefs.edit().putString(KEY_LAST_RESULT, value).apply()

    /** Per-service last-seen state, the dedup bookkeeping for [MonitorAlerts.diff]. */
    fun seenStates(): Map<String, String> {
        val json = prefs.getString(KEY_SEEN, null) ?: return emptyMap()
        return try {
            val o = JSONObject(json)
            buildMap { for (key in o.keys()) put(key, o.getString(key)) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun setSeenStates(states: Map<String, String>) {
        val o = JSONObject()
        states.forEach { (service, state) -> o.put(service, state) }
        prefs.edit().putString(KEY_SEEN, o.toString()).apply()
    }

    companion object {
        private const val PREFS = "monitor"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_URL = "gateway_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_PERIOD = "period_minutes"
        private const val KEY_LAST_RESULT = "last_result"
        private const val KEY_SEEN = "seen_states"

        const val DEFAULT_PERIOD_MINUTES = 15
        const val MIN_PERIOD_MINUTES = 15
        const val MAX_PERIOD_MINUTES = 240
    }
}
