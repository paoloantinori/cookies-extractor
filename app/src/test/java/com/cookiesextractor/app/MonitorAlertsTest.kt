package com.cookiesextractor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorAlertsTest {

    private fun alert(service: String, state: String) =
        MonitorAlerts.Alert(service, "https://s.example", state, 1_000L)

    // ---- parseState ----

    @Test
    fun parsesTheDocumentedSchema() {
        val body = """{"alerts":[
            {"service":"davmail","url":"https://g.example/davmail","state":"expired","ts":42},
            {"service":"wiki","url":"https://w.example","state":"ok","ts":43}
        ]}"""
        val alerts = MonitorAlerts.parseState(body)!!
        assertEquals(2, alerts.size)
        assertEquals(MonitorAlerts.Alert("davmail", "https://g.example/davmail", "expired", 42), alerts[0])
        assertEquals(MonitorAlerts.Alert("wiki", "https://w.example", "ok", 43), alerts[1])
    }

    @Test
    fun missingStateDefaultsToOk() {
        val alerts = MonitorAlerts.parseState("""{"alerts":[{"service":"s","url":"u","ts":1}]}""")!!
        assertEquals(MonitorAlerts.STATE_OK, alerts.single().state)
    }

    @Test
    fun blankServiceEntriesAreDropped() {
        val alerts = MonitorAlerts.parseState(
            """{"alerts":[{"service":"","url":"u"},{"service":"real","url":"u"}]}"""
        )!!
        assertEquals(listOf("real"), alerts.map { it.service })
    }

    @Test
    fun duplicateServicesCollapseToTheLastEntry() {
        // a gateway appending heartbeats instead of keying by service must not
        // alternate Notify/Clear on every poll
        val alerts = MonitorAlerts.parseState(
            """{"alerts":[
                {"service":"wiki","url":"u","state":"ok","ts":1},
                {"service":"wiki","url":"u","state":"expired","ts":2}
            ]}"""
        )!!
        assertEquals(1, alerts.size)
        assertEquals(MonitorAlerts.STATE_EXPIRED, alerts.single().state)
    }

    @Test
    fun malformedBodiesYieldNull() {
        assertNull(MonitorAlerts.parseState("not json"))
        assertNull(MonitorAlerts.parseState("""{"no_alerts":true}"""))
        assertNull(MonitorAlerts.parseState("""{"alerts":"not-an-array"}"""))
    }

    // ---- diff transitions ----

    @Test
    fun firstSeenExpiredNotifies() {
        val decisions = MonitorAlerts.diff(listOf(alert("davmail", MonitorAlerts.STATE_EXPIRED)), emptyMap())
        assertEquals(listOf(MonitorAlerts.Decision.Notify(alert("davmail", MonitorAlerts.STATE_EXPIRED))), decisions)
    }

    @Test
    fun unchangedExpiredDoesNotReNotify() {
        val decisions = MonitorAlerts.diff(
            listOf(alert("davmail", MonitorAlerts.STATE_EXPIRED)),
            mapOf("davmail" to MonitorAlerts.STATE_EXPIRED),
        )
        assertEquals(listOf<MonitorAlerts.Decision>(MonitorAlerts.Decision.None), decisions)
    }

    @Test
    fun expiredToOkClears() {
        val decisions = MonitorAlerts.diff(
            listOf(alert("davmail", MonitorAlerts.STATE_OK)),
            mapOf("davmail" to MonitorAlerts.STATE_EXPIRED),
        )
        assertEquals(listOf(MonitorAlerts.Decision.Clear("davmail")), decisions)
    }

    @Test
    fun okToOkIsSilent() {
        val decisions = MonitorAlerts.diff(
            listOf(alert("davmail", MonitorAlerts.STATE_OK)),
            mapOf("davmail" to MonitorAlerts.STATE_OK),
        )
        assertEquals(listOf<MonitorAlerts.Decision>(MonitorAlerts.Decision.None), decisions)
    }

    @Test
    fun servicesAbsentFromTheFetchAreIgnored() {
        // the gateway owns the service set: a rotating list must not produce phantom clears
        val decisions = MonitorAlerts.diff(emptyList(), mapOf("davmail" to MonitorAlerts.STATE_EXPIRED))
        assertTrue(decisions.isEmpty())
    }
}
