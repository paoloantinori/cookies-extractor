package com.cookiesextractor.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure-JVM model and diffing for inbound monitor alerts (COK-26). The gateway state
 * document is {"alerts":[{"service":"...","url":"...","state":"expired","ts":...}]};
 * [diff] decides which alerts deserve a notification by comparing against the
 * previously seen per-service states: notify on ok->expired (a first-seen expired
 * counts), clear on expired->ok. No android imports.
 */
object MonitorAlerts {

    const val STATE_EXPIRED = "expired"
    const val STATE_OK = "ok"

    data class Alert(val service: String, val url: String, val state: String, val ts: Long)

    sealed interface Decision {
        data class Notify(val alert: Alert) : Decision
        data class Clear(val service: String) : Decision
        data object None : Decision
    }

    /**
     * Parses the state document. Null on malformed input (an unparsable body is skipped,
     * never thrown); entries without a service name are dropped; a missing state defaults
     * to ok. A service listed more than once keeps only its LAST entry: a gateway that
     * appends heartbeats instead of keying by service must not alternate Notify/Clear.
     */
    fun parseState(body: String): List<Alert>? = try {
        val arr = JSONObject(body).getJSONArray("alerts")
        // linked map keeps the document order across the collapse
        val byService = LinkedHashMap<String, Alert>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val service = o.optString("service")
            if (service.isBlank()) continue
            byService[service] = Alert(service, o.optString("url"), o.optString("state", STATE_OK), o.optLong("ts"))
        }
        byService.values.toList()
    } catch (e: Exception) {
        null
    }

    /**
     * Diffs the freshly fetched alerts against the previously seen states. Services absent
     * from the fetched list are ignored: the gateway owns the service set, and a rotating
     * list must not produce phantom recoveries.
     */
    fun diff(alerts: List<Alert>, seen: Map<String, String>): List<Decision> =
        alerts.mapNotNull { alert ->
            val previous = seen[alert.service]
            when {
                alert.state == STATE_EXPIRED && previous != STATE_EXPIRED -> Decision.Notify(alert)
                alert.state == STATE_OK && previous == STATE_EXPIRED -> Decision.Clear(alert.service)
                else -> Decision.None
            }
        }
}
