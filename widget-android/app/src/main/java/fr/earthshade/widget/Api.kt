package fr.earthshade.widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Échanges avec Supabase (le même serveur que l'appli EarthShade), sans bibliothèque :
 * connexion par email + mot de passe, puis lecture des profils et des événements.
 * Les droits sont ceux du compte connecté : on ne voit que son propre foyer.
 */
object Api {
    private const val BASE = "https://dobozrtzmzbxfgpcjesq.supabase.co"
    private const val KEY = "sb_publishable_65knnUG6E1RCT95lEW7apg_uta_GZnY"

    class AuthException(message: String) : IOException(message)

    private fun request(method: String, path: String, body: String?, token: String?): Pair<Int, String> {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("apikey", KEY)
            conn.setRequestProperty("Authorization", "Bearer " + (token ?: KEY))
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun saveSession(ctx: Context, json: JSONObject) {
        val expiresIn = json.optLong("expires_in", 3600L)
        Store.saveSession(
            ctx,
            access = json.getString("access_token"),
            refresh = json.getString("refresh_token"),
            expiresAtMs = System.currentTimeMillis() + (expiresIn - 120) * 1000
        )
    }

    /** Connexion avec l'email et le mot de passe d'EarthShade. */
    fun login(ctx: Context, email: String, password: String) {
        val body = JSONObject().put("email", email.trim()).put("password", password).toString()
        val (code, text) = request("POST", "/auth/v1/token?grant_type=password", body, null)
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).optString("error_description", JSONObject(text).optString("msg")) }.getOrNull()
            throw AuthException(if (code == 400) "Email ou mot de passe incorrect." else (msg ?: "Erreur $code"))
        }
        saveSession(ctx, JSONObject(text))
        Store.setEmail(ctx, email.trim())
    }

    /** Un jeton valide : celui qu'on a, ou un nouveau obtenu avec le jeton de renouvellement. */
    private fun token(ctx: Context): String {
        val access = Store.access(ctx)
        if (access != null && System.currentTimeMillis() < Store.accessExpiresAt(ctx)) return access
        val refresh = Store.refresh(ctx) ?: throw AuthException("Pas connecté.")
        val (code, text) = request("POST", "/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", refresh).toString(), null)
        if (code in 400..499) { Store.logout(ctx); throw AuthException("Session expirée : reconnecte-toi dans l'appli ES Widget.") }
        if (code !in 200..299) throw IOException("Serveur indisponible ($code)")
        saveSession(ctx, JSONObject(text))
        return Store.access(ctx)!!
    }

    /** Télécharge les profils et les événements d'aujourd'hui à J+14, et les range pour le widget. */
    fun sync(ctx: Context) {
        val t = token(ctx)
        val from = LocalDate.now()
        val to = from.plusDays(14)
        val (c1, profiles) = request("GET", "/rest/v1/profiles?select=id,name,color,user_id", null, t)
        if (c1 == 401) { Store.clearAccess(ctx); throw IOException("Jeton refusé, nouvel essai au prochain passage") }
        if (c1 !in 200..299) throw IOException("Profils : erreur $c1")
        val q = "/rest/v1/events?select=id,title,date,start_time,end_time,important,masque,event_participants(profile_id)" +
            "&date=gte.$from&date=lte.$to&order=date.asc,start_time.asc.nullsfirst"
        val (c2, events) = request("GET", q, null, t)
        if (c2 !in 200..299) throw IOException("Événements : erreur $c2")
        // Vérifie que ce sont bien des listes avant d'enregistrer
        JSONArray(profiles); JSONArray(events)
        Store.saveData(ctx, profiles, events)
    }
}
