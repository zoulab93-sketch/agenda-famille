
package fr.earthshade.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
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
private val RAIL = Color(0xFF6B7079)        // ligne grise des journées entières qui se suivent
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

// Hauteurs (dp) des lignes
private const val PAD = 10f
private const val H_HEADER = 24f

/* ------------------------------------------------------------------ */
/* Données                                                             */
/* ------------------------------------------------------------------ */
private data class WEv(val title: String, val date: LocalDate, val start: String?, val important: Boolean,
                       val colors: List<Int>, val key: String, var until: LocalDate? = null)
private data class Day(val date: LocalDate, val shifts: List<Pair<String, Int>>, val events: List<WEv>,
                       val cont: List<WEv> = emptyList()) {
    /** Un fil gris traverse cette journée (journée entière commencée avant, ou qui continue après) */
    val railOn: Boolean get() = cont.isNotEmpty() || events.any { it.until != null }
}

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
    // La base enregistre une ligne par jour : on ne garde que le premier jour de chaque série, les suivants ne sont plus écrits
    val allDay = evs.filter { it.start == null }
    val continuation = allDay.filter { ev -> allDay.any { it.key == ev.key && it.date == ev.date.minusDays(1) } }
    evs.removeAll { c -> continuation.any { it === c } }
    for (ev in evs.filter { it.start == null }) {
        var end = ev.date
        while (allDay.any { it.key == ev.key && it.date == end.plusDays(1) }) end = end.plusDays(1)
        if (end.isAfter(ev.date)) ev.until = end
    }
    val runs = evs.filter { it.until != null }
    val days = mutableListOf<Day>()
    for (d in 0..14) {
        val date = today.plusDays(d.toLong())
        val list = evs.filter { it.date == date }.sortedWith(compareBy<WEv>({ it.start != null }, { it.start ?: "" }, { it.title }))
        // Journées entières en cours : elles ne sont écrites que le premier jour, ensuite seule la ligne grise continue
        val cont = runs.filter { date.isAfter(it.date) && !date.isAfter(it.until) }
        val sh = shifts[date] ?: emptyList<Pair<String, Int>>()
        if (d == 0 || list.isNotEmpty() || sh.isNotEmpty() || cont.isNotEmpty()) days.add(Day(date, sh, list, cont))
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
        val elbow = makeElbow(context)
        provideContent { Content(logged, days, warn, elbow) }
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

private fun lum(c: Int): Double {
    val r = (c shr 16 and 255) / 255.0; val g = (c shr 8 and 255) / 255.0; val b = (c and 255) / 255.0
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** Texte foncé dès qu'une des couleurs de la pastille est claire (jaune, bleu clair…), blanc sinon. */
private fun textOnAll(colors: List<Int>): Color =
    if (colors.any { lum(it) > 0.5 }) Color(0xFF1E232A) else Color.White

/** Coude de départ du fil : part vers la gauche puis descend à 90° dans le fil (dessiné une fois, en bitmap). */
private fun makeElbow(ctx: Context): android.graphics.Bitmap {
    val d = ctx.resources.displayMetrics.density
    val w = (29 * d).toInt().coerceAtLeast(1)
    val h = (12 * d).toInt().coerceAtLeast(1)
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = RAIL.toArgb()
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 6 * d
    }
    val path = android.graphics.Path().apply {
        moveTo(0f, 3 * d)
        lineTo(20 * d, 3 * d)
        quadTo(26 * d, 3 * d, 26 * d, 9 * d)
        lineTo(26 * d, h.toFloat())
    }
    canvas.drawPath(path, paint)
    return bmp
}

private sealed class Item {
    class DayHead(val day: Day) : Item()
    class Line(val ev: WEv, val rail: Boolean, val elbow: Boolean) : Item()
    class Rail : Item()                       // journée sans rien d'autre : juste le fil gris
    class Note(val text: String, val date: LocalDate) : Item()
}

/** Ajoute un jour : son en-tête, ses lignes, et une simple ligne grise si rien d'autre ne s'y passe. */
private fun addDay(out: MutableList<Item>, day: Day, head: Boolean) {
    if (day.events.isEmpty() && day.cont.isNotEmpty()) { out.add(Item.Rail()); return }
    if (head) out.add(Item.DayHead(day))
    day.events.forEach { out.add(Item.Line(it, day.railOn, it.until != null)) }
}

/** Toute la liste : aujourd'hui d'abord, puis chaque jour suivant qui a quelque chose. On fait glisser pour voir la suite. */
private fun listItems(days: List<Day>): List<Item> {
    val out = mutableListOf<Item>()
    val today = days.firstOrNull() ?: return out
    if (today.events.isEmpty() && today.cont.isEmpty()) out.add(Item.Note("Rien de prévu aujourd'hui", today.date))
    else addDay(out, today, head = false)
    for (day in days.drop(1)) addDay(out, day, head = true)
    return out
}

/** Le fil gris, posé SOUS le contenu : il passe derrière les pastilles et les textes sans les cacher. */
@Composable
private fun WithRail(on: Boolean, elbow: android.graphics.Bitmap? = null, content: @Composable () -> Unit) {
    if (!on) { content(); return }
    Box(modifier = GlanceModifier.fillMaxWidth()) {
        Row(modifier = GlanceModifier.fillMaxSize()) {
            Spacer(GlanceModifier.width(23.dp))          // centré sous la pastille de 52 dp
            Box(modifier = GlanceModifier.width(6.dp).fillMaxHeight().background(RAIL)) {}
        }
        if (elbow != null) {
            Image(provider = ImageProvider(elbow), contentDescription = null, modifier = GlanceModifier.width(29.dp).height(12.dp))
        }
        content()
    }
}

@Composable
private fun ShiftChip(name: String, color: Int) {
    Box(modifier = GlanceModifier.background(Color(color)).cornerRadius(5.dp).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(name, style = TextStyle(color = ColorProvider(textOn(color)), fontSize = 10.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

/** Pastille arrondie autour de l'heure : une tranche de couleur par participant. */
@Composable
private fun TimePill(text: String, colors: List<Int>, small: Boolean) {
    Box(modifier = GlanceModifier.width(52.dp).height(19.dp).cornerRadius(6.dp), contentAlignment = Alignment.Center) {
        Row(modifier = GlanceModifier.fillMaxSize()) {
            for (c in colors) Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight().background(Color(c))) {}
        }
        Text(text, style = TextStyle(color = ColorProvider(textOnAll(colors)), fontSize = if (small) 10.sp else 11.5.sp,
            fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

@Composable
private fun Content(logged: Boolean, days: List<Day>?, warn: Boolean, elbow: android.graphics.Bitmap?) {
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
        LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
          items(listItems(days)) { item ->
            when (item) {
                is Item.DayHead -> WithRail(item.day.railOn) {
                    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 7.dp, bottom = 2.dp).clickable(openDay(item.day.date)),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(item.day.date.format(DateTimeFormatter.ofPattern("EEEE d", FR)).uppercase(FR),
                            style = TextStyle(color = ColorProvider(SOFT), fontSize = 10.5.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                        for ((name, color) in item.day.shifts) { Spacer(GlanceModifier.width(5.dp)); ShiftChip(name, color) }
                    }
                }
                is Item.Rail -> WithRail(true) {
                    Spacer(GlanceModifier.fillMaxWidth().height(14.dp))
                }
                is Item.Line -> WithRail(item.rail, if (item.elbow) elbow else null) {
                    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickable(openDay(item.ev.date)),
                        verticalAlignment = Alignment.CenterVertically) {
                        TimePill(item.ev.start ?: "Journée", item.ev.colors, item.ev.start == null)
                        Spacer(GlanceModifier.width(8.dp))
                        if (item.ev.important) Text("★ ", style = TextStyle(color = ColorProvider(STAR), fontSize = 12.5.sp))
                        Text(item.ev.title, modifier = GlanceModifier.defaultWeight(),
                            style = TextStyle(color = ColorProvider(INK), fontSize = 12.5.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                        item.ev.until?.let {
                            Text("jusqu'au ${it.dayOfMonth}", style = TextStyle(color = ColorProvider(SOFT), fontSize = 10.5.sp), maxLines = 1)
                        }
                    }
                }
                is Item.Note -> Text(item.text, modifier = GlanceModifier.padding(vertical = 2.dp).clickable(openDay(item.date)),
                    style = TextStyle(color = ColorProvider(SOFT), fontSize = 12.sp))
            }
          }
        }
    }
}