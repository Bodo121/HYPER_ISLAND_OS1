package io.github.sakuraisland

data class EventRank(val key: String, val priority: Int, val time: Long, val expires: Long)
object Policy {
    /** The marketing name can be only OS1.0; never treat it as the full ROM build. */
    fun romVersion(hyperIncremental: String, buildIncremental: String, marketingName: String): String {
        val full = Regex("^OS[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+\\.[A-Z0-9]+$")
        return listOf(hyperIncremental, buildIncremental, marketingName).map { it.trim() }
            .firstOrNull { full.matches(it) } ?: "unknown"
    }
    fun select(events: Collection<EventRank>, now: Long): String? = events
        .filter { it.expires > now }.maxWithOrNull(compareBy<EventRank> { it.priority }.thenBy { it.time })?.key
    fun target(sdk: Int, device: String, version: String) = sdk == 34 && device == "taoyao" && version == "OS1.0.39.0.ULIEUXM"
    /** All apps by default; explicit comma-separated filter when user opts out. */
    fun allowNotificationPackage(packageName: String, allApps: Boolean, allowlist: String): Boolean {
        if (packageName.isBlank()) return false
        return allApps || allowlist.split(',').any { it.trim() == packageName }
    }
    /** Conservative approximation for 310dp alert cards; actual TextView wraps at most 4 lines. */
    fun alertPreviewLines(text: String): Int = text.split('\n').sumOf { line ->
        ((line.length + 30) / 31).coerceAtLeast(1)
    }.coerceIn(1,6)

    /** Pixel-space camera safety; text never occupies the measured physical lens region. */
    fun compactSideWidth(totalPx: Int, cameraWidthPx: Int, marginPx: Int): Int =
        ((totalPx - cameraWidthPx - 2 * marginPx).coerceAtLeast(0)) / 2

    /** Bounded and predictable height for a content-sized notification panel. */
    fun alertCardHeightDp(lines: Int, cameraClearanceDp: Int, withActions: Boolean): Int {
        val textHeight = 28 + lines.coerceIn(1, 6) * 19
        return (cameraClearanceDp + 34 + maxOf(44, textHeight) +
            (if (withActions) 48 else 12) + 14).coerceIn(126, 320)
    }

    /** Critical/ongoing events are never eligible for native heads-up suppression. */
    fun mayReplaceHeadsUp(kind: String, hasFullScreenIntent: Boolean,
                          clearable: Boolean, isOngoing: Boolean, secret: Boolean): Boolean =
        kind == "notification" && !hasFullScreenIntent && clearable && !isOngoing && !secret

    fun validOemBatteryLevel(oem: Float, systemLevel: Int): Boolean =
        systemLevel in 0..100 && oem in 1f..100f &&
            kotlin.math.abs(oem - systemLevel) < 3f

    /** One reusable queue rotation rule, with wraparound and expiry handled by caller. */
    fun nextAlertKey(keys: List<String>, current: String?): String? {
        if (keys.isEmpty()) return null
        val index = keys.indexOf(current)
        return keys[(index + 1) % keys.size]
    }

    fun dimension(value: Int, min: Int, max: Int) = value.coerceIn(min, max)
}
