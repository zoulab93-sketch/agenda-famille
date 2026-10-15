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
                       val colors: List<Int>, val key: String, var until: LocalDate? = null, var lane: Int = 0)
/** Un fil = une couloir (lane) et une couleur. Plusieurs séries en même temps → plusieurs fils côte à côte. */
private data class RailSeg(val lane: Int, val color: Int)
private data class Day(val date: LocalDate, val shifts: List<Pair<String, Int>>, val events: List<WEv>,
                       val cont: List<WEv> = emptyList(), val rails: List<RailSeg> = emptyList()) {
    val railOn: Boolean get() = rails.isNotEmpty()
}

// Les fils passent dans une gouttière à gauche des pastilles (plus jamais dessous)
private const val GUTTER = 16      // dp, largeur de la gouttière
private const val RAIL_W = 6       // dp, épaisseur d'un fil
private const val RAIL_MAX = 2     // fils côte à côte au maximum (un 3e se superpose au 2e)
private const val ROW_H = 23       // dp, hauteur d'une ligne d'événement (2 + pastille 19 + 2)
private fun railX(lane: Int) = 1 + lane * 8   // dp, bord gauche du fil dans la gouttière

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
    // Chaque série reçoit un couloir libre : deux séries qui se chevauchent ne se cachent plus l'une l'autre
    val laneEnd = mutableListOf<LocalDate>()
    for (r in runs.sortedBy { it.date }) {
        var l = laneEnd.indexOfFirst { it.isBefore(r.date) }
        if (l < 0) { laneEnd.add(r.until!!); l = laneEnd.size - 1 } else laneEnd[l] = r.until!!
        r.lane = l.coerceAtMost(RAIL_MAX - 1)
    }
    val days = mutableListOf<Day>()
    for (d in 0..14) {
        val date = today.plusDays(d.toLong())
        val list = evs.filter { it.date == date }.sortedWith(compareBy<WEv>({ it.start != null }, { it.start ?: "" }, { it.title }))
        // Journées entières en cours : elles ne sont écrites que le premier jour, ensuite seule la ligne grise continue
        val cont = runs.filter { date.isAfter(it.date) && !date.isAfter(it.until) }
        val sh = shifts[date] ?: emptyList<Pair<String, Int>>()
        val rails = runs.filter { !date.isBefore(it.date) && !date.isAfter(it.until) }
            .map { RailSeg(it.lane, it.colors.first()) }.distinctBy { it.lane }.sortedBy { it.lane }
        if (d == 0 || list.isNotEmpty() || sh.isNotEmpty() || cont.isNotEmpty()) days.add(Day(date, sh, list, cont, rails))
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
        // Un coude de départ par série (couloir + couleur), dessiné une fois
        val elbows = mutableMapOf<String, android.graphics.Bitmap>()
        days?.flatMap { it.events }?.filter { it.until != null }?.forEach { ev ->
            elbows.getOrPut(elbowKey(ev)) { makeElbow(context, ev.lane, ev.colors.first()) }
        }
        provideContent { Content(logged, days, warn, elbows) }
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

private fun elbowKey(ev: WEv) = "${ev.lane}|${ev.colors.first()}"

/** Rayon de l'arrondi : 9 dp quand la place le permet (le fil de droite a moins de place dans la gouttière). */
private fun elbowRadius(lane: Int): Float =
    minOf(9f, GUTTER - (railX(lane) + RAIL_W / 2f))

/** Coude de départ : sort du bord gauche de la pastille, file vers la gauche puis plonge à 90° dans son fil. */
private fun makeElbow(ctx: Context, lane: Int, color: Int): android.graphics.Bitmap {
    val d = ctx.resources.displayMetrics.density
    val w = (GUTTER * d).toInt().coerceAtLeast(1)
    val h = (ROW_H * d).toInt().coerceAtLeast(1)
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = RAIL_W * d
    }
    val xc = (railX(lane) + RAIL_W / 2f) * d      // milieu du fil
    val yc = ROW_H / 2f * d                         // milieu de la pastille
    val r = elbowRadius(lane) * d                   // rayon de l'arrondi (doux)
    val k = 0.5523f * r                             // quart de cercle parfait
    paint.strokeCap = android.graphics.Paint.Cap.BUTT
    val path = android.graphics.Path().apply {
        moveTo(w.toFloat(), yc)
        lineTo(xc + r, yc)
        cubicTo(xc + r - k, yc, xc, yc + r - k, xc, yc + r)
        lineTo(xc, h.toFloat())
    }
    canvas.drawPath(path, paint)
    return bmp
}

private sealed class Item {
    class DayHead(val day: Day, val rails: List<RailSeg>) : Item()
    class Line(val ev: WEv, val rails: List<RailSeg>, val elbow: Boolean) : Item()
    class Rail(val rails: List<RailSeg>) : Item()   // journée sans rien d'autre : juste les fils
    class Note(val text: String, val date: LocalDate) : Item()
}

/** Ajoute un jour : son en-tête, ses lignes, et une ligne « fil seul » si rien d'autre ne s'y passe.
 *  Un fil ne commence qu'à la ligne de son premier jour (avec le coude), pas au-dessus. */
private fun addDay(out: MutableList<Item>, day: Day, head: Boolean) {
    val active = day.cont.map { RailSeg(it.lane, it.colors.first()) }.distinctBy { it.lane }.toMutableList()
    if (head) out.add(Item.DayHead(day, active.toList()))
    if (day.events.isEmpty() && active.isNotEmpty()) { out.add(Item.Rail(active.toList())); return }
    for (ev in day.events) {
        val starts = ev.until != null
        out.add(Item.Line(ev, active.toList(), starts))
        if (starts && active.none { it.lane == ev.lane }) active.add(RailSeg(ev.lane, ev.colors.first()))
    }
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

/** Chaque ligne de la liste : la gouttière avec ses fils (et le coude éventuel), puis le contenu à droite. */
@Composable
private fun Gutter(rails: List<RailSeg>, elbow: android.graphics.Bitmap? = null, elbowSeg: RailSeg? = null,
                   content: @Composable () -> Unit) {
    Box(modifier = GlanceModifier.fillMaxWidth()) {
        Row(modifier = GlanceModifier.fillMaxSize()) {
            var x = 0
            for (r in rails.sortedBy { it.lane }) {
                val gap = railX(r.lane) - x
                if (gap > 0) Spacer(GlanceModifier.width(gap.dp))
                Box(modifier = GlanceModifier.width(RAIL_W.dp).fillMaxHeight().background(Color(r.color))) {}
                x = railX(r.lane) + RAIL_W
            }
        }
        if (elbow != null) {
            Image(provider = ImageProvider(elbow), contentDescription = null,
                modifier = GlanceModifier.width(GUTTER.dp).height(ROW_H.dp))
        }
        if (elbowSeg != null) {
            // Sous l'arrondi, le fil continue jusqu'au bas de la ligne, quelle que soit sa hauteur réelle (plus de petit trou)
            val top = ROW_H / 2f + elbowRadius(elbowSeg.lane) - 1f
            Row(modifier = GlanceModifier.fillMaxSize()) {
                Spacer(GlanceModifier.width(railX(elbowSeg.lane).dp))
                Column(modifier = GlanceModifier.width(RAIL_W.dp).fillMaxHeight()) {
                    Spacer(GlanceModifier.height(top.dp))
                    Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight().background(Color(elbowSeg.color))) {}
                }
            }
        }
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Spacer(GlanceModifier.width(GUTTER.dp))
            Box(modifier = GlanceModifier.defaultWeight()) { content() }
        }
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
private fun Content(logged: Boolean, days: List<Day>?, warn: Boolean, elbows: Map<String, android.graphics.Bitmap>) {
    val base = GlanceModifier.fillMaxSize().background(BG).cornerRadius(22.dp)
        .padding(start = 4.dp, end = 12.dp, top = PAD.dp, bottom = PAD.dp)
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
        Row(modifier = GlanceModifier.fillMaxWidth().height(H_HEADER.dp).padding(start = 8.dp).clickable(openDay(today.date)), verticalAlignment = Alignment.CenterVertically) {
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
                is Item.DayHead -> Gutter(item.rails) {
                    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 7.dp, bottom = 2.dp).clickable(openDay(item.day.date)),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(item.day.date.format(DateTimeFormatter.ofPattern("EEEE d", FR)).uppercase(FR),
                            style = TextStyle(color = ColorProvider(SOFT), fontSize = 10.5.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                        for ((name, color) in item.day.shifts) { Spacer(GlanceModifier.width(5.dp)); ShiftChip(name, color) }
                    }
                }
                is Item.Rail -> Gutter(item.rails) {
                    Spacer(GlanceModifier.fillMaxWidth().height(14.dp))
                }
                is Item.Line -> Gutter(item.rails,
                    if (item.elbow) elbows[elbowKey(item.ev)] else null,
                    if (item.elbow) RailSeg(item.ev.lane, item.ev.colors.first()) else null) {
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
                is Item.Note -> Gutter(emptyList()) {
                    Text(item.text, modifier = GlanceModifier.padding(vertical = 2.dp).clickable(openDay(item.date)),
                        style = TextStyle(color = ColorProvider(SOFT), fontSize = 12.sp))
                }
            }
          }
        }
    }
}
