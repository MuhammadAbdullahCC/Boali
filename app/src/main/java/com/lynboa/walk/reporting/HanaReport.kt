package com.lynboa.walk.reporting

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lynboa.walk.data.FitnessRepository
import com.lynboa.walk.domain.FitnessMath
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Hana's reporting data stays on the device. None of these fields are uploaded
 * or transmitted without a deliberate user action or a future approved API integration.
 */
object HanaReport {
    private const val PREF_NAME = "boali_hana_reporting"
    fun today(): LocalDate = LocalDate.now(ZoneId.of("Asia/Karachi"))
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun entry(context: Context, key: String, day: LocalDate = today()): String =
        prefs(context).getString(day.toString() + ":" + key, "").orEmpty()

    fun saveEntry(context: Context, key: String, value: String) {
        prefs(context).edit().putString(today().toString() + ":" + key, value.take(500)).apply()
    }

    fun recipientNumber(context: Context): String =
        prefs(context).getString("recipient_number", "").orEmpty()

    fun saveRecipientNumber(context: Context, value: String) {
        prefs(context).edit().putString("recipient_number", value.take(24)).apply()
        // Consent must be reconfirmed if the destination changes.
        prefs(context).edit().putBoolean("ali_consented", false).apply()
    }

    fun hasConsent(context: Context): Boolean =
        prefs(context).getBoolean("ali_consented", false)

    fun recordConsent(context: Context, confirmed: Boolean) {
        prefs(context).edit().putBoolean("ali_consented", confirmed).apply()
    }

    fun enabled(context: Context): Boolean =
        prefs(context).getBoolean("report_enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("report_enabled", enabled).apply()
    }

    fun recordedDate(context: Context): String =
        prefs(context).getString("last_report_date", "").orEmpty()

    fun latestSnapshot(context: Context): String =
        prefs(context).getString("last_report_text", "").orEmpty()

    fun saveSnapshot(context: Context, day: LocalDate, text: String) {
        prefs(context).edit()
            .putString("last_report_date", day.toString())
            .putString("last_report_text", text)
            .apply()
    }

    fun compose(context: Context, repo: FitnessRepository, day: LocalDate = today()): String {
        val progress = repo.progress.value
        val dateText = day.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
        fun log(key: String) = entry(context, key, day).ifBlank { "Not logged" }
        val km = FitnessMath.walkedKm(progress.steps, repo.strideCm.value)
        return listOf(
            "🤖 BoaLi | Hana Huang's Daily Report",
            "",
            "Hello Ali. Hana here, with an automated update.",
            "",
            "📅 Date: " + dateText,
            "🚶 Step-estimated walking distance: " + String.format(Locale.US, "%.2f km", km),
            "👣 Steps: " + progress.steps,
            "☕ Meals and drinks: " + log("meals"),
            "⏰ Wake-up: " + log("wakeup"),
            "🪥 Dental care: " + log("dental"),
            "💇 Hair care: " + log("hair"),
            "💧 Water logged: " + progress.waterMl + " ml",
            "📝 Notes: " + log("notes"),
            "",
            "Automatically prepared by BoaLi. Daily report window: 8 PM Pakistan time.",
            "These entries are user-reported; distance is estimated from steps."
        ).joinToString("\n")
    }

    fun consentRequest(): String =
        "Hi Ali! I'm setting up my personal BoaLi activity tracker. " +
        "Would you like to receive an automated daily report from Hana Huang " +
        "around 8 PM Pakistan time with my walking activity, meals, water, " +
        "and grooming notes? Please reply YES if you agree, or NO if not. " +
        "You can ask me to stop these updates whenever you want."

    /** Opens WhatsApp's composer. Android cannot silently send personal messages using intents. */
    fun openWhatsApp(context: Context, message: String) {
        val digits = recipientNumber(context).filter { it.isDigit() }
        val phone = if (digits.isBlank()) "" else "phone=" + digits + "&"
        val uri = Uri.parse("https://api.whatsapp.com/send?" + phone +
            "text=" + Uri.encode(message))
        context.startActivity(Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
