package io.github.sakuraisland

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.SharedPreferences

private val Sakura = Color(0xFFFF7597)
private val Paper = Color(0xFFF3F3F5)
private val Muted = Color(0xFF9E9EA8)
private val Panel = Color(0xFF161618)
private val Glass = Color(0xFF28282D)
private val Hairline = Color(0x22FFFFFF)
private val Black = Color(0xFF08080B)

@Composable
fun SakuraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Sakura, onPrimary = Color.Black,
            background = Black, surface = Panel, onSurface = Paper,
            surfaceVariant = Glass, onSurfaceVariant = Muted, outline = Hairline
        ),
        content = content
    )
}

@Composable
private fun SectionHeader(name: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(top = 12.dp, bottom = 5.dp)) {
        Text(name.uppercase(), color = Sakura, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel)
        .border(1.dp, Hairline, RoundedCornerShape(22.dp)).padding(17.dp), content = content)
}

@Composable
private fun ConfigToggle(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Paper, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(detail, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Switch(checked = checked, onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(checkedTrackColor = Sakura, checkedThumbColor = Color.Black))
    }
}

@Composable
private fun ConfigSlider(title: String, current: Float, range: ClosedFloatingPointRange<Float>, unit: String, onSet: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Paper, fontSize = 13.sp)
            Text("${current.toInt()} $unit", color = Sakura, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(value = current.coerceIn(range.start, range.endInclusive), onValueChange = onSet, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Sakura, activeTrackColor = Sakura))
    }
}

@Composable
private fun PreviewControls(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, fontSize = 12.sp, color = Sakura)
    }
}

@Composable
private fun PreviewIsland(state: String, expanded: Boolean, changeExpanded: () -> Unit) {
    val accent = when (state) {
        "Charging" -> Color(0xFF00E676)
        "Notification" -> Color(0xFF8BC9EF)
        "Call" -> Color(0xFF7DD9BE)
        else -> Sakura
    }
    Box(Modifier.fillMaxWidth().height(if (expanded) 216.dp else 114.dp)
        .clip(RoundedCornerShape(18.dp)).background(Color(0xFF0D0C12))
        .animateContentSize(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)),
        contentAlignment = Alignment.TopCenter) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 17.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("9:41", color = Muted, fontSize = 11.sp)
            Text("▮  ◕  ▰", color = Muted, fontSize = 10.sp)
        }
        val shape = RoundedCornerShape(if (expanded) 26.dp else 22.dp)
        Column(Modifier.padding(top = 13.dp).width(if (expanded) 318.dp else if (state == "Idle") 48.dp else 174.dp)
            .height(if (expanded) 178.dp else 39.dp).shadow(12.dp, shape)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(
                if (expanded) accent.copy(alpha = 0.31f) else Color.Black,
                Color(0xFF070609), Color.Black
            )))
            .border(0.8.dp, Hairline, shape)
            .clickable { changeExpanded() }
            .padding(horizontal = if (expanded) 16.dp else 10.dp, vertical = if (expanded) 14.dp else 8.dp),
            verticalArrangement = Arrangement.Center) {
            if (expanded) {
                Spacer(Modifier.height(16.dp)) // camera clearance: demo only
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(53.dp).clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(accent, Color(0xFF251124)))),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(when (state) { "Notification" -> "New message"; "Charging" -> "Charging"; "Call" -> "Incoming call"; else -> "Sakura Dreams" },
                            color = Paper, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when (state) { "Charging" -> "Battery status preview"; "Notification" -> "Message preview"; "Call" -> "Tap to answer in your dialer"; else -> "Demo artist" },
                            color = Muted, fontSize = 12.sp, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(15.dp))
                LinearProgressIndicator(progress = { 0.48f }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                    color = accent, trackColor = Color.White.copy(alpha = 0.15f))
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SkipPrevious, null, tint = Paper)
                    Spacer(Modifier.width(26.dp))
                    Icon(Icons.Rounded.PlayCircleFilled, null, tint = Paper, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.width(26.dp))
                    Icon(Icons.Rounded.SkipNext, null, tint = Paper)
                }
            } else if (state != "Idle") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                        .background(accent.copy(alpha = .65f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.MusicNote, null, modifier = Modifier.size(13.dp), tint = Color.White)
                    }
                    Spacer(Modifier.width(46.dp)) // camera clearance
                    Icon(if (state == "Charging") Icons.Rounded.Bolt else Icons.Rounded.GraphicEq, null,
                        tint = accent, modifier = Modifier.size(19.dp))
                }
            }
        }
        Box(Modifier.align(Alignment.TopCenter).padding(top = 24.dp).size(12.dp).background(Color(0xFF050508), CircleShape))
    }
}

@Composable
fun SakuraSettingsScreen(
    prefs: SharedPreferences,
    save: (String, String) -> Unit,
    onReport: () -> Unit,
    onTestNotification: () -> Unit,
    onDisable: () -> Unit
) {
    val values = remember {
        mutableStateMapOf<String, String>().apply {
            Config.defaults.forEach { (k, default) -> put(k, prefs.getString(k, default) ?: default) }
        }
    }
    fun set(k: String, v: String) { values[k] = v; save(k, v) }
    fun enabled(k: String) = values[k] == "true"
    fun number(k: String, min: Float, max: Float): Float =
        (values[k]?.toFloatOrNull() ?: Config.defaults[k]?.toFloatOrNull() ?: min).coerceIn(min, max)
    var chosen by remember { mutableStateOf("Music") }
    var expanded by remember { mutableStateOf(true) }
    var apps by remember { mutableStateOf(values["apps"] ?: "") }
    Scaffold(containerColor = Black, topBar = {
        Column(Modifier.fillMaxWidth().background(Black).padding(start = 21.dp, end = 21.dp, top = 18.dp, bottom = 10.dp)) {
            Text("SAKURA LAB", color = Sakura, fontSize = 10.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
            Text("SakuraIsland", color = Paper, fontWeight = FontWeight.Bold, fontSize = 30.sp)
            Text("HyperOS 1  •  Xiaomi 12 Lite  •  experimental", color = Muted, fontSize = 12.sp)
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 35.dp)) {
            item {
                SectionHeader("Live preview", "A visual demo; the real island uses SystemUI notifications and media sessions.")
                GlassCard {
                    PreviewIsland(chosen, expanded) { expanded = !expanded }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf("Idle", "Music", "Notification", "Charging", "Call").forEach {
                            TextButton(onClick = { chosen = it; expanded = it != "Idle" },
                                contentPadding = PaddingValues(horizontal = 3.dp)) {
                                Text(it.take(5), color = if (chosen == it) Sakura else Muted, fontSize = 10.sp)
                            }
                        }
                    }
                    Text("Tap the preview to morph. Not a live system overlay.", fontSize = 11.sp, color = Muted)
                }
            }
            item {
                SectionHeader("Engine", "Requires LSPosed with System UI as its only scope; reboot after changing module scope.")
                GlassCard {
                    ConfigToggle("Enable SakuraIsland", "Show activity cards above the status bar", enabled("enabled")) { set("enabled", it.toString()) }
                    HorizontalDivider(color = Hairline)
                    ConfigToggle("Persistent camera pill", "Keep a small passive camera outline when idle", enabled("idle")) { set("idle", it.toString()) }
                }
            }
            item {
                SectionHeader("Activities")
                GlassCard {
                    listOf(Triple("media","Music sessions","Albums, playback and controls"),
                        Triple("notifications","Notifications","Selected app messages and alerts"),
                        Triple("calls","Calls","Incoming or missed call notices"),
                        Triple("timers","Timers and alarms","Notifications published by timer apps"),
                        Triple("charging","Charging","Real battery percentage")).forEachIndexed { idx, (k, t, d) ->
                        if (idx > 0) HorizontalDivider(color = Hairline)
                        ConfigToggle(t,d,enabled(k)) { set(k,it.toString()) }
                    }
                }
            }
            item {
                SectionHeader("Appearance")
                GlassCard {
                    ConfigToggle("Ambient album colors", "Use artwork-derived gradients", enabled("chroma")) { set("chroma",it.toString()) }
                    HorizontalDivider(color = Hairline)
                    ConfigToggle("Motion effects", "Animated equalizer and capsule morphing", enabled("motion")) { set("motion",it.toString()) }
                    ConfigSlider("Compact width", number("width",120f,240f), 120f..240f, "dp") { set("width",it.toInt().toString()) }
                    ConfigSlider("Minimum height", number("height",28f,52f),28f..52f,"dp") { set("height",it.toInt().toString()) }
                    ConfigSlider("Camera vertical offset",number("offset",-4f,24f),-4f..24f,"dp") { set("offset",it.toInt().toString()) }
                    ConfigSlider("Morph duration",number("speed",100f,700f),100f..700f,"ms") { set("speed",it.toInt().toString()) }
                    Text("Actual camera placement is measured from display cutout insets, not assumed from the mockup.", fontSize = 11.sp, color = Muted)
                }
            }
            item {
                SectionHeader("Notification apps")
                GlassCard {
                    OutlinedTextField(value = apps, onValueChange = { apps = it.take(1024) },
                        label = { Text("Comma-separated package IDs") }, minLines = 2, maxLines = 4,
                        modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Sakura, focusedLabelColor = Sakura))
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { set("apps",apps) }, colors = ButtonDefaults.buttonColors(containerColor = Sakura, contentColor = Color.Black)) {
                        Text("Save app filter")
                    }
                }
            }
            item {
                SectionHeader("Diagnostics & recovery")
                GlassCard {
                    Text("SystemUI hooks are experimental. If the status bar crashes, disable the module in LSPosed or your root manager's safe mode and reboot.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onReport, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.BugReport, null)
                        Spacer(Modifier.width(8.dp)); Text("View SystemUI report")
                    }
                    OutlinedButton(onClick = onTestNotification, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.NotificationsActive, null)
                        Spacer(Modifier.width(8.dp)); Text("Post test notification")
                    }
                    TextButton(onClick = onDisable, modifier = Modifier.fillMaxWidth()) {
                        Text("Disable island rendering", color = Color(0xFFFFA0A9))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("0.3 experimental  ·  No telemetry  ·  No overlay permission", color = Muted, fontSize = 11.sp)
            }
        }
    }
}
