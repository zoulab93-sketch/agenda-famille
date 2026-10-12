package fr.earthshade.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.json.JSONArray
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/* ------------------------------------------------------------------ */
/* Couleurs et réglages                                                */
/* ------------------------------------------------------------------ */
private val BG = Color(0xE6171A1E)          // fond sombre légèrement transparent
private val INK = Color(0xFFECEEF1)
private val SOFT = Color(0xFF9AA1AB)
private val STAR = Color(0xFFE5B93C)
private val NEUTRAL: Int = 0xFF8E96A3.toInt()
private const val SITE = "https://zoulab93-sketch.github.io/agenda-famille/"
private val FR = Locale.FRENCH

// Les shifts d'EarthShade et leurs couleurs (comme le bouton couleurs de l'appli)
private val SHIFTS = mapOf(
    "matin" to ("Matin" to 0xFF90CAF8), "soir" to ("Soir" to 0xFF00887A), "soir rouge" to ("Soir rouge" to 0xFFE53935),
    "dom" to ("Dom" to 0xFFCF93D9), "consultation" to ("Consultation" to 0xFF3949AB), "ca" to ("CA" to 0xFFFB8C00),
    "formation" to ("Formation" to 0xFF00ACC2), "tutorat" to ("Tutorat" to 0xFF00ACC2),
    "rh" to ("RH" to 0xFFFFFFFF), "rtt" to ("RTT" to 0xFFFFFFFF)
)

// Hauteurs (dp) utilisées pour savoir combien de lignes tiennent dans le widget
private const val PAD = 10f
private const val H_HEADER = 24f
private const val H_DAY = 20f
private const val H_LINE = 18f
private const val H_MORE = 16f

/* ------------------------------------------------------------------ */
/* Données                                                             */
/* ------------------------------------------------------------------ */
private data class WEv(val title: String, val date: LocalDate, val start: String?, val important: Boolean,
                       val colors: List<Int>, val key: String, var until: LocalDate? = null)
private data class Day(val date: LocalDate, val shifts: List<Pair<String, Int>>, val events: List<WEv>)

private fun parseColor(s: String?): Int = try { android.graphics.Color.parseColor(s ?: "") } catch (e: Exception) { NEUTRAL }

private fun loadDays(ctx: Context): List<Day>? {
    val profilesTxt = Store.profiles(ctx) ?: return null
    val eventsTxt = Store.events(ctx) ?: return null
    val profiles = JSONArray(profilesTxt)
    val order = mutableMapOf<String, Int>()
    val colorOf = mutableMapOf<String, Int>()
    for (i in 0 until profiles.length()) {
        val p = profiles.getJSONObject(i)
        order[p.getString("id")] = i
        colorOf[p.getString("id")] = parseColor(p.optString("color"))
    }
    val today = LocalDate.now()
    val shifts = mutableMapOf<LocalDate, MutableList<Pair<String, Int>>>()
    val evs = mutableListOf<WEv>()
    val arr = JSONArray(eventsTxt)
    for (i in 0 until arr.length()) {
        val e = arr.getJSONObject(i)
        if (e.optBoolean("masque", false)) continue
        val date = runCatching { LocalDate.parse(e.getString("date")) }.getOrNull() ?: continue
        if (date.isBefore(today)) continue
        val title = e.optString("title", "").trim()
        val shift = SHIFTS[title.lowercase(FR)]
        if (shift != null) {   // un shift devient une étiquette à côté de la date
            val list = shifts.getOrPut(date) { mutableListOf() }
            if (list.none { it.first == shift.first }) list.add(shift.first to shift.second.toInt())
            continue
        }
        val ids = mutableListOf<String>()
        val parts = e.optJSONArray("event_participants")
        if (parts != null) for (j in 0 until parts.length()) parts.getJSONObject(j).optString("profile_id").takeIf { it.isNotEmpty() }?.let { ids.add(it) }
        ids.sortBy { order[it] ?: 99 }
        val colors = ids.map { colorOf[it] ?: NEUTRAL }.ifEmpty { listOf(NEUTRAL) }
        val start = e.optString("start_time", "").takeIf { it.isNotEmpty() && it != "null" }?.take(5)
        evs.add(WEv(title, date, start, e.optBoolean("important", false), colors, title.lowercase(FR) + "|" + ids.joinToString(",")))
    }
    // Journée entière sur plusieurs jours de suite : « jusqu'au N »
    val allDay = evs.filter { it.start == null }
    for (ev in allDay) {
        var end = ev.date
        while (allDay.any { it.key == ev.key && it.date == end.plusDays(1) }) end = end.plusDays(1)
        if (end.isAfter(ev.date)) ev.until = end
    }
    val days = mutableListOf<Day>()
    for (d in 0..7) {
        val date = today.plusDays(d.toLong())
        val list = evs.filter { it.date == date }.sortedWith(compareBy<WEv>({ it.start != null }, { it.start ?: "" }, { it.title }))
        val sh = shifts[date] ?: emptyList<Pair<String, Int>>()
        if (d == 0 || list.isNotEmpty() || sh.isNotEmpty()) days.add(Day(date, sh, list))
    }
    return days
}

/* ------------------------------------------------------------------ */
/* Le widget                                                           */
/* ------------------------------------------------------------------ */
class EsWidget : GlanceAppWidget() {
    // Exact : on reçoit la vraie taille de la zone, et le widget la remplit entièrement
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val logged = Store.isLoggedIn(context)
        val days = if (logged) runCatching { loadDays(context) }.getOrNull() else null
        val warn = Store.error(context) != null
        provideContent { Content(logged, days, warn) }
    }
}

class EsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EsWidget()
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        SyncWorker.schedule(context)
        SyncWorker.now(context)
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        SyncWorker.now(context)
    }
}

private fun openDay(date: LocalDate) =
    actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$SITE?jour=$date")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

private fun textOn(c: Int): Color {
    val r = (c shr 16 and 255) / 255.0; val g = (c shr 8 and 255) / 255.0; val b = (c and 255) / 255.0
    return if (0.2126 * r + 0.7152 * g + 0.0722 * b > 0.55) Color(0xFF1E232A) else Color.White
}

private sealed class Item {
    class DayHead(val day: Day) : Item()
    class Line(val ev: WEv) : Item()
    class More(val n: Int, val date: LocalDate) : Item()
    class Note(val text: String, val date: LocalDate) : Item()
}

/** Ce qui tient dans la hauteur disponible : aujourd'hui d'abord, puis les jours suivants. */
private fun plan(days: List<Day>, heightDp: Float): List<Item> {
    var budget = heightDp - 2 * PAD - H_HEADER
    val out = mutableListOf<Item>()
    val today = days.firstOrNull() ?: return out
    if (today.events.isEmpty()) {
        if (budget >= H_LINE) { out.add(Item.Note("Rien de prévu aujourd'hui", today.date)); budget -= H_LINE }
    } else {
        val total = today.events.size
        var shown = 0
        for (ev in today.events) {
            val left = total - shown - 1
            val need = H_LINE + (if (left > 0) H_MORE else 0f)
            if (budget < need && !(left == 0 && budget >= H_LINE)) break
            out.add(Item.Line(ev)); budget -= H_LINE; shown++
        }
        if (shown < total && budget >= H_MORE) { out.add(Item.More(total - shown, today.date)); budget -= H_MORE }
    }
    for (day in days.drop(1)) {
        if (budget < H_DAY) break
        val need = H_DAY + (if (day.events.isNotEmpty()) H_LINE else 0f)
        if (budget < need) break
        out.add(Item.DayHead(day)); budget -= H_DAY
        for ((i, ev) in day.events.withIndex()) {
            val left = day.events.size - i - 1
            if (budget < H_LINE || (left > 0 && budget < H_LINE + H_MORE && budget < 2 * H_LINE)) {
                if (budget >= H_MORE) { out.add(Item.More(day.events.size - i, day.date)); budget -= H_MORE }
                return out
            }
            out.add(Item.Line(ev)); budget -= H_LINE
        }
    }
    return out
}

@Composable
private fun ShiftChip(name: String, color: Int) {
    Box(modifier = GlanceModifier.background(Color(color)).cornerRadius(5.dp).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(name, style = TextStyle(color = ColorProvider(textOn(color)), fontSize = 10.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

@Composable
private fun Bar(colors: List<Int>) {
    val h = 15f / colors.size
    Column(modifier = GlanceModifier.width(5.dp).height(15.dp)) {
        for (c in colors) Box(modifier = GlanceModifier.width(5.dp).height(h.dp).background(Color(c))) {}
    }
}

@Composable
private fun Content(logged: Boolean, days: List<Day>?, warn: Boolean) {
    val size = LocalSize.current
    val base = GlanceModifier.fillMaxSize().background(BG).cornerRadius(22.dp).padding(horizontal = 12.dp, vertical = PAD.dp)
    if (!logged || days == null) {
        Column(modifier = base.clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("EarthShade", style = TextStyle(color = ColorProvider(INK), fontSize = 15.sp, fontWeight = FontWeight.Bold))
            Text(if (!logged) "Touchez ici pour vous connecter" else "Chargement…",
                style = TextStyle(color = ColorProvider(SOFT), fontSize = 12.sp))
        }
        return
    }
    val today = days.first()
    Column(modifier = base) {
        // En-tête : Aujourd'hui · date · shifts · ↻
        Row(modifier = GlanceModifier.fillMaxWidth().height(H_HEADER.dp).clickable(openDay(today.date)), verticalAlignment = Alignment.CenterVertically) {
            Text("Aujourd'hui", style = TextStyle(color = ColorProvider(INK), fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            Spacer(GlanceModifier.width(6.dp))
            Text(today.date.format(DateTimeFormatter.ofPattern("EEEE d", FR)),
                style = TextStyle(color = ColorProvider(SOFT), fontSize = 11.5.sp), maxLines = 1)
            for ((name, color) in today.shifts) { Spacer(GlanceModifier.width(5.dp)); ShiftChip(name, color) }
            Spacer(GlanceModifier.defaultWeight())
            if (warn) Text("⚠ ", style = TextStyle(color = ColorProvider(SOFT), fontSize = 12.sp))
            Text("↻", modifier = GlanceModifier.padding(start = 6.dp).clickable(actionRunCallback<RefreshAction>()),
                style = TextStyle(color = ColorProvider(SOFT), fontSize = 15.sp))
        }
        for (item in plan(days, size.height.value)) {
            when (item) {
                is Item.DayHead -> Row(modifier = GlanceModifier.fillMaxWidth().height(H_DAY.dp).padding(top = 4.dp).clickable(openDay(item.day.date)),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(item.day.date.format(DateTimeFormatter.ofPattern("EEEE d", FR)).uppercase(FR),
                        style = TextStyle(color = ColorProvider(SOFT), fontSize = 10.5.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    for ((name, color) in item.day.shifts) { Spacer(GlanceModifier.width(5.dp)); ShiftChip(name, color) }
                }
                is Item.Line -> Row(modifier = GlanceModifier.fillMaxWidth().height(H_LINE.dp).clickable(openDay(item.ev.date)),
                    verticalAlignment = Alignment.CenterVertically) {
                    Bar(item.ev.colors)
                    Spacer(GlanceModifier.width(6.dp))
                    Text(item.ev.start ?: "Journée", modifier = GlanceModifier.width(40.dp),
                        style = TextStyle(color = ColorProvider(SOFT), fontSize = if (item.ev.start == null) 10.sp else 11.5.sp), maxLines = 1)
                    if (item.ev.important) Text("★ ", style = TextStyle(color = ColorProvider(STAR), fontSize = 12.5.sp))
                    Text(item.ev.title, modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(color = ColorProvider(INK), fontSize = 12.5.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    item.ev.until?.let {
                        Text("jusqu'au ${it.dayOfMonth}", style = TextStyle(color = ColorProvider(SOFT), fontSize = 10.5.sp), maxLines = 1)
                    }
                }
                is Item.More -> Text("+${item.n} autre${if (item.n > 1) "s" else ""}",
                    modifier = GlanceModifier.height(H_MORE.dp).padding(start = 11.dp).clickable(openDay(item.date)),
                    style = TextStyle(color = ColorProvider(SOFT), fontSize = 11.sp))
                is Item.Note -> Text(item.text, modifier = GlanceModifier.height(H_LINE.dp).clickable(openDay(item.date)),
                    style = TextStyle(color = ColorProvider(SOFT), fontSize = 12.sp))
            }
        }
    }
}
