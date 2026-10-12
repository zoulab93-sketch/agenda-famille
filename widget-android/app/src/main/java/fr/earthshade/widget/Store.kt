package fr.earthshade.widget

import android.content.Context
import android.content.SharedPreferences

/** Petite mémoire de l'appli, privée sur le téléphone : la session et la dernière copie des événements. */
object Store {
    private fun p(ctx: Context): SharedPreferences = ctx.getSharedPreferences("es_widget", Context.MODE_PRIVATE)

    fun saveSession(ctx: Context, access: String, refresh: String, expiresAtMs: Long) {
        p(ctx).edit().putString("access", access).putString("refresh", refresh).putLong("access_exp", expiresAtMs).commit()
    }
    fun access(ctx: Context): String? = p(ctx).getString("access", null)
    fun refresh(ctx: Context): String? = p(ctx).getString("refresh", null)
    fun accessExpiresAt(ctx: Context): Long = p(ctx).getLong("access_exp", 0L)
    fun clearAccess(ctx: Context) { p(ctx).edit().remove("access").putLong("access_exp", 0L).commit() }
    fun isLoggedIn(ctx: Context): Boolean = refresh(ctx) != null

    fun setEmail(ctx: Context, email: String) { p(ctx).edit().putString("email", email).apply() }
    fun email(ctx: Context): String? = p(ctx).getString("email", null)

    fun saveData(ctx: Context, profiles: String, events: String) {
        p(ctx).edit().putString("profiles", profiles).putString("events", events)
            .putLong("synced_at", System.currentTimeMillis()).remove("error").commit()
    }
    fun profiles(ctx: Context): String? = p(ctx).getString("profiles", null)
    fun events(ctx: Context): String? = p(ctx).getString("events", null)
    fun syncedAt(ctx: Context): Long = p(ctx).getLong("synced_at", 0L)

    fun setError(ctx: Context, msg: String?) { p(ctx).edit().putString("error", msg).commit() }
    fun error(ctx: Context): String? = p(ctx).getString("error", null)

    fun logout(ctx: Context) {
        p(ctx).edit().remove("access").remove("refresh").remove("access_exp")
            .remove("profiles").remove("events").remove("synced_at").commit()
    }
}
