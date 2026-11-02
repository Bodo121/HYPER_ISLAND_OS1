package io.github.sakuraisland

data class EventRank(val key: String, val priority: Int, val time: Long, val expires: Long)
object Policy {
    fun select(events: Collection<EventRank>, now: Long): String? = events
        .filter { it.expires > now }.maxWithOrNull(compareBy<EventRank> { it.priority }.thenBy { it.time })?.key
    fun target(sdk: Int, device: String, version: String) = sdk == 34 && device == "taoyao" && version == "OS1.0.39.0.ULIEUXM"
    fun dimension(value: Int, min: Int, max: Int) = value.coerceIn(min, max)
}
