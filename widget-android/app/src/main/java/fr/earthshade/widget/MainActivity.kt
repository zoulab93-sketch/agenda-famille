package fr.earthshade.widget

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.runBlocking
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

/** L'écran de l'appli : se connecter une fois avec son compte EarthShade, puis ajouter le widget. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncWorker.schedule(this)
        show()
    }

    override fun onResume() {
        super.onResume()
        show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(s: String, size: Float = 16f, bold: Boolean = false): TextView = TextView(this).apply {
        setText(s); textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun show() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(40), dp(22), dp(22))
        }
        root.addView(label("EarthShade · widget", 24f, true))

        if (!Store.isLoggedIn(this)) {
            root.addView(label("Connecte-toi avec ton compte EarthShade (même email et même mot de passe). Il n'y a besoin de le faire qu'une fois."))
            val email = EditText(this).apply {
                hint = "Email"; setText(Store.email(this@MainActivity) ?: "")
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }
            val pwd = EditText(this).apply {
                hint = "Mot de passe"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val status = label("", 14f)
            val btn = Button(this).apply { text = "Se connecter" }
            btn.setOnClickListener {
                val e = email.text.toString(); val p = pwd.text.toString()
                if (e.isBlank() || p.isEmpty()) { status.text = "Remplis l'email et le mot de passe."; return@setOnClickListener }
                btn.isEnabled = false; status.text = "Connexion…"
                thread {
                    try {
                        Api.login(this, e, p)
                        try { Api.sync(this) } catch (_: Exception) { }
                        runBlocking { EsWidget().updateAll(this@MainActivity) }
                        runOnUiThread { SyncWorker.schedule(this); show() }
                    } catch (ex: Exception) {
                        runOnUiThread { status.text = ex.message ?: "Connexion impossible."; btn.isEnabled = true }
                    }
                }
            }
            root.addView(email); root.addView(pwd); root.addView(btn); root.addView(status)
        } else {
            root.addView(label("Connecté : ${Store.email(this) ?: ""}"))
            val at = Store.syncedAt(this)
            root.addView(label(if (at > 0) "Dernière mise à jour : " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)) else "Pas encore de mise à jour.", 14f))
            Store.error(this)?.let { root.addView(label("Dernier essai : $it", 14f)) }
            root.addView(label("Pour ajouter le widget : appui long sur un espace vide de l'écran d'accueil, puis Widgets, puis EarthShade. Ensuite, appui long sur le widget pour l'agrandir ou le réduire.", 15f))
            root.addView(Button(this).apply {
                text = "Actualiser maintenant"
                setOnClickListener { SyncWorker.now(this@MainActivity); text = "Actualisation demandée" }
            })
            root.addView(View(this).apply { minimumHeight = dp(24) })
            root.addView(Button(this).apply {
                text = "Se déconnecter"
                setOnClickListener {
                    Store.logout(this@MainActivity)
                    thread { runBlocking { EsWidget().updateAll(this@MainActivity) } }
                    show()
                }
            })
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
