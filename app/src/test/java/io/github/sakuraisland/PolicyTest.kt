package io.github.sakuraisland

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    @Test fun marketingNameDoesNotMaskFullBuild() {
        assertEquals("OS1.0.39.0.ULIEUXM", Policy.romVersion("", "OS1.0.39.0.ULIEUXM", "OS1.0"))
        assertEquals("OS1.0.39.0.ULIEUXM", Policy.romVersion("OS1.0.39.0.ULIEUXM", "V816.0.39.0.ULIEUXM", "OS1.0"))
    }
    @Test fun unknownBuildDoesNotBroadenCompatibility() {
        assertEquals("unknown", Policy.romVersion("", "", "OS1.0"))
        assertEquals("unknown", Policy.romVersion("39.0", "V816.0.39.0.ULIEUXM", "OS1.0"))
    }
    @Test fun fullHyperBuildTakesPrecedenceOverStaleFallback() {
        val resolved = Policy.romVersion("OS1.0.40.0.ULIEUXM", "OS1.0.39.0.ULIEUXM", "OS1.0")
        assertFalse(Policy.target(34, "taoyao", resolved))
    }
    @Test fun callsPreemptMediaAndExpiryRestoresMedia() {
        val events = listOf(EventRank("media", 40, 1, Long.MAX_VALUE), EventRank("call", 100, 2, 20))
        assertEquals("call", Policy.select(events, 10)); assertEquals("media", Policy.select(events, 20))
    }
    @Test fun newestEqualPriorityWins() {
        assertEquals("b", Policy.select(listOf(EventRank("a", 60, 1, 20), EventRank("b", 60, 2, 20)), 3))
    }
    @Test fun expiredAndEmptyAreHidden() {
        assertNull(Policy.select(emptyList(), 0)); assertNull(Policy.select(listOf(EventRank("a", 1, 0, 10)), 10))
    }
    @Test fun allAppsCoversNormalAppNotifications() {
        assertTrue(Policy.allowNotificationPackage("com.google.android.apps.messaging", true, "com.whatsapp"))
        assertTrue(Policy.allowNotificationPackage("com.whatsapp", true, ""))
        assertFalse(Policy.allowNotificationPackage("", true, ""))
    }
    @Test fun packageFilterUsesExactIdsNotSubstrings() {
        assertTrue(Policy.allowNotificationPackage("org.telegram.messenger", false, "com.whatsapp, org.telegram.messenger"))
        assertFalse(Policy.allowNotificationPackage("org.telegram.messenger.fake", false, "org.telegram.messenger"))
        assertFalse(Policy.allowNotificationPackage("com.google.android.apps.messaging", false, "com.whatsapp"))
        assertFalse(Policy.allowNotificationPackage("com.whatsapp", false, ""))
    }
    @Test fun alertCardsGrowForLongerMessagesButStayBounded() {
        assertEquals(1, Policy.alertPreviewLines("Hey"))
        assertEquals(2, Policy.alertPreviewLines("A".repeat(45)))
        assertEquals(4, Policy.alertPreviewLines("A".repeat(500)))
        assertEquals(3, Policy.alertPreviewLines("short\\nother message of medium length"))
    }
    @Test fun exactTargetOnly() {
        assertTrue(Policy.target(34, "taoyao", "OS1.0.39.0.ULIEUXM"))
        assertFalse(Policy.target(35, "taoyao", "OS1.0.39.0.ULIEUXM"))
        assertFalse(Policy.target(34, "other", "OS1.0.39.0.ULIEUXM"))
        assertFalse(Policy.target(34, "taoyao", "OS1.0.38.0.ULIEUXM"))
    }
}
