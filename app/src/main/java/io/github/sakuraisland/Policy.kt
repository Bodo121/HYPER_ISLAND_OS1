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
    fun dimension(value: Int, min: Int, max: Int) = value.coerceIn(min, max)
}
