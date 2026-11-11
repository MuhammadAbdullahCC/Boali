package com.lynboa.walk.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.lynboa.walk.data.FitnessRepository
import com.lynboa.walk.domain.FitnessMath
import com.lynboa.walk.reporting.HanaReminderWorker
import com.lynboa.walk.reporting.HanaReport
import java.time.LocalDate
import java.util.Locale

private val HanaGold = Color(0xFFFFCA85)
private val Chalk = Color(0xFFFFF1F7)

@Composable
fun HanaScreen(repo: FitnessRepository) {
    val context = LocalContext.current
    val progress by repo.progress.collectAsState()
    val stride by repo.strideCm.collectAsState()
    var phone by remember { mutableStateOf(HanaReport.recipientNumber(context)) }
    var consent by remember { mutableStateOf(HanaReport.hasConsent(context)) }
    var scheduled by remember { mutableStateOf(HanaReport.enabled(context)) }
    var preview by remember {
        mutableStateOf(
            if (HanaReport.recordedDate(context) == LocalDate.now().toString())
                HanaReport.latestSnapshot(context)
            else HanaReport.compose(context, repo)
        )
    }
    val validNumber = phone.filter { it.isDigit() }.length in 10..15

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp)
            .background(Brush.linearGradient(listOf(Color(0xFF49351D),
                Color(0xFF201727), Color(0xFF37203B))), RoundedCornerShape(24.dp))
            .border(1.dp, HanaGold.copy(alpha = .6f), RoundedCornerShape(24.dp))
            .padding(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("HANA HUANG", color = Chalk, fontSize = 29.sp,
                    fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("THE EVENING REPORTER   /   8:00 PM", color = HanaGold,
                    fontSize = 10.sp, letterSpacing = 1.sp)
                Text("“I keep records, not excuses. Your report will be ready tonight.”",
                    color = Chalk, fontSize = 13.sp)
                Text("Boa tracks. Lyn maps. Hana reports.", color = Color.LightGray,
                    fontSize = 11.sp)
            }
        }

        HanaCard {
            Text("TODAY'S SNAPSHOT", color = HanaGold, fontWeight = FontWeight.Bold)
            Text("" + progress.steps + " steps  •  " +
                String.format(Locale.US, "%.2f km", FitnessMath.walkedKm(progress.steps, stride)) +
                " step-estimated  •  " + progress.waterMl + " ml water",
                color = Chalk, fontSize = 14.sp)
            Text("Walking distance is estimated from steps, not a summed GPS route.",
                color = Color.LightGray, fontSize = 11.sp)
        }

        HanaCard {
            Text("DAILY ENTRIES", color = HanaGold, fontWeight = FontWeight.Bold)
            Text("These details remain on your phone until you choose to share them.",
                color = Color.LightGray, fontSize = 11.sp)
            HanaInput(context, "wakeup", "Wake-up time", "e.g. 2:00 PM")
            HanaInput(context, "meals", "Meals, tea and other drinks", "e.g. one cup of tea so far", 3)
            HanaInput(context, "dental", "Dental care", "e.g. special dental routine completed")
            HanaInput(context, "hair", "Hair care", "e.g. special hair care completed")
            HanaInput(context, "notes", "Other daily notes", "Optional", 3)
        }

        HanaCard {
            Text("ALI · WHATSAPP DELIVERY", color = HanaGold, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = phone,
                onValueChange = {
                    phone = it.take(24)
                    HanaReport.saveRecipientNumber(context, phone)
                    consent = false
                },
                label = { Text("Ali's phone number with country code") },
                placeholder = { Text("e.g. 923001234567") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Text("Choose the number carefully. Reports contain personal daily activity.",
                color = Color.LightGray, fontSize = 11.sp)
            OutlinedButton(onClick = {
                HanaReport.openWhatsApp(context, HanaReport.consentRequest())
            }, enabled = validNumber) {
                Text("Ask Ali for permission")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = consent, onCheckedChange = {
                    consent = it
                    HanaReport.recordConsent(context, it)
                })
                Text("Ali has explicitly agreed to receive these daily reports.",
                    color = Chalk, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
            Text("Only mark this after receiving Ali's YES. His reply is not read automatically.",
                color = Color.LightGray, fontSize = 11.sp)
        }

        HanaCard {
            Text("EVENING REPORT SETTINGS", color = HanaGold, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Prepare a report around 8:00 PM", color = Chalk, fontSize = 14.sp)
                    Text("Pakistan local time (when your phone uses Asia/Karachi)",
                        color = Color.LightGray, fontSize = 11.sp)
                }
                Switch(checked = scheduled, onCheckedChange = {
                    scheduled = it
                    HanaReport.setEnabled(context, it)
                    HanaReminderWorker.schedule(context)
                })
            }
            Text("Android may delay scheduled work on battery-saving devices. " +
                "Hana saves a report and posts a notification; WhatsApp does not " +
                "permit silent sending from a regular Android app.",
                color = Color.LightGray, fontSize = 12.sp)
            Button(onClick = { preview = HanaReport.compose(context, repo) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = HanaGold, contentColor = Color(0xFF271522))) {
                Text("Regenerate report preview")
            }
        }

        HanaCard {
            Text("HANA'S REPORT", color = HanaGold, fontWeight = FontWeight.Bold)
            Text(preview, color = Chalk, fontSize = 12.sp, lineHeight = 19.sp)
            Button(
                enabled = consent && validNumber,
                onClick = { HanaReport.openWhatsApp(context, preview) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = HanaGold, contentColor = Color(0xFF271522))
            ) {
                Text("Review and send in WhatsApp")
            }
            Text("This opens WhatsApp with the message ready. You must tap Send. " +
                "Fully unattended delivery requires a separately configured " +
                "WhatsApp Business Platform sender and approved template.",
                color = Color.LightGray, fontSize = 11.sp)
        }
    }
}

@Composable
private fun HanaInput(context: Context, key: String, title: String,
                      hint: String, lines: Int = 1) {
    var value by remember(key) { mutableStateOf(HanaReport.entry(context, key)) }
    OutlinedTextField(
        value = value,
        onValueChange = {
            value = it.take(500)
            HanaReport.saveEntry(context, key, value)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(title) },
        placeholder = { Text(hint) },
        minLines = lines,
        maxLines = if (lines == 1) 2 else 5
    )
}

@Composable
private fun HanaCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)
        .background(Color(0xAA3F2E3A), RoundedCornerShape(22.dp))
        .border(1.dp, HanaGold.copy(alpha = .4f), RoundedCornerShape(22.dp))
        .padding(17.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content)
}
