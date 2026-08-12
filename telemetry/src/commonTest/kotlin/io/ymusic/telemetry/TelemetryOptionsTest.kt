package io.ymusic.telemetry

import io.sentry.kotlin.multiplatform.SentryEvent
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb
import io.sentry.kotlin.multiplatform.protocol.SentryException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryOptionsTest {

    @Test
    fun coded_error_groups_by_its_code_alone() {
        val event = SentryEvent()
        event.setTag(SentryLogger.CODE_TAG, "E20011")
        event.setTag(SentryLogger.TRAIL_TAG, "Discover/fetchHomeFeed/http")
        assertEquals(listOf("E20011"), fingerprintPolicy(event).fingerprint?.toList())
    }

    @Test
    fun non_coded_capture_groups_by_type_plus_trail() {
        val event = SentryEvent()
        event.setTag(SentryLogger.TRAIL_TAG, "Discover/fetchHomeFeed/http")
        event.exceptions = mutableListOf(SentryException(type = "SocketTimeoutException"))
        assertEquals(
            listOf("SocketTimeoutException", "Discover/fetchHomeFeed/http"),
            fingerprintPolicy(event).fingerprint?.toList(),
        )
    }

    @Test
    fun uncaught_crash_keeps_default_grouping() {
        val event = SentryEvent()
        event.exceptions = mutableListOf(SentryException(type = "NullPointerException"))
        assertTrue(fingerprintPolicy(event).fingerprint.isNullOrEmpty())
    }

    @Test
    fun set_cookie_values_are_redacted_from_breadcrumbs() {
        val crumb = Breadcrumb(message = "HTTP 200 youtube.com set-cookie=[SID=secret; Path=/ | HSID=alsosecret]")
        assertEquals("HTTP 200 youtube.com set-cookie=[redacted]", scrubBreadcrumb(crumb).message)
        val names = Breadcrumb(message = "GET youtube.com/watch cookies=SID,HSID")
        assertEquals("GET youtube.com/watch cookies=SID,HSID", scrubBreadcrumb(names).message)
    }
}
