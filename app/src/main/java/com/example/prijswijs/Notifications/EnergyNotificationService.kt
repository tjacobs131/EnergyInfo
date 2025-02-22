package com.example.prijswijs.Notifications

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.prijswijs.EnergyZeroAPI.EnergyPriceAPI
import com.example.prijswijs.Persistence.Persistence
import com.example.prijswijs.EnergyZeroAPI.PriceData
import com.example.prijswijs.EnergyZeroAPI.PricesUnavailableException
import com.example.prijswijs.Persistence.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class EnergyNotificationService : Service() {
    private val NOTIFICATION_ID = 1337420
    private val FINAL_NOTIFICATION_ID = 1338

    private val persistence: Persistence by lazy { Persistence(this) }
    private val settings: Settings by lazy { persistence.loadSettings(this) }
    private val notificationBuilder by lazy { NotificationBuilder(this, settings) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Create channels and start service in foreground with a processing notification.
        notificationBuilder.createNotificationChannels()
        startForeground(NOTIFICATION_ID, notificationBuilder.buildProcessingNotification())
        showNotification()
        return START_NOT_STICKY
    }

    private fun showNotification() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("PrijsWijs", "Fetching energy prices...")
                lateinit var prices: PriceData
                var warningMessage = ""
                try {
                    prices = EnergyPriceAPI().getTodaysEnergyPrices()
                } catch (ex: PricesUnavailableException) {
                    prices = ex.oldPrices
                    warningMessage = ex.message.toString()
                }

                Log.d("PrijsWijs", "Prices fetched successfully.")
                val message = generateHourlyNotificationMessage(prices, warningMessage)
                Log.d("PrijsWijs", "Showing message: $message")

                withContext(Dispatchers.Main) {
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(FINAL_NOTIFICATION_ID, notificationBuilder.buildFinalNotification(message))
                    Log.d("PrijsWijs", "Notification shown")

                    // Delay check to confirm the notification is active
                    Handler(Looper.getMainLooper()).postDelayed({
                        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        if (nm.activeNotifications.none { it.id == FINAL_NOTIFICATION_ID }) {
                            Log.d("PrijsWijs", "Notification not shown (after delay check), potentially an issue.")
                        } else {
                            Log.d("PrijsWijs", "Notification shown and confirmed.")
                        }
                    }, 15000)
                }

                // Stop service after showing the notification
                stopSelf()
            } catch (e: Exception) {
                Log.e("PrijsWijs", "Notification failed to show or data fetch error", e)
                val errorMessage = "⚠️ Failed to update prices. ⚠️\n${e.message}"
                withContext(Dispatchers.Main) {
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(FINAL_NOTIFICATION_ID, notificationBuilder.buildFinalNotification(errorMessage, isError = true))
                }
                stopSelf()
            }
        }
    }

    private fun generateHourlyNotificationMessage(priceData: PriceData, warningMessage: String): String {
        var returnString = if (warningMessage.isNotEmpty()) warningMessage else ""
        val dateFormat = SimpleDateFormat("HH:mm", Locale.US)
        val range = priceData.peakPrice - priceData.troughPrice

        val dateTimeEmojiTable = mapOf(
            4 to "\uD83C\uDF11",
            6 to "\uD83C\uDF05",
            8 to "🌄",
            16 to "☀️",
            19 to "🌆",
            23 to "\uD83C\uDF19"
        )

        val keysList = priceData.priceTimeMap.keys.toList()

        priceData.priceTimeMap.entries.forEachIndexed { index, entry ->
            val date = entry.key
            val price = entry.value
            var suffix = ""

            if (range > 0) {
                // Dynamic thresholds based on the range
                val peakThreshold1 = priceData.peakPrice - 0.1 * range
                val peakThreshold2 = priceData.peakPrice - 0.4 * range
                val troughThreshold1 = priceData.troughPrice + 0.1 * range
                val troughThreshold2 = priceData.troughPrice + 0.3 * range

                when {
                    price == priceData.priceTimeMap.values.min() -> suffix = "⭐"
                    range < 0.06 -> suffix = ""
                    price > peakThreshold1 && range > 0.15 -> suffix = "‼\uFE0F"
                    price == priceData.priceTimeMap.values.max() || price > peakThreshold2 -> suffix = "❗"
                    price < troughThreshold1 -> suffix = "⭐"
                    price < troughThreshold2 && range > 0.10 -> suffix = "🌱"
                    else -> suffix = ""
                }
            }

            if (index > 0) {
                val prevDate = keysList[index - 1]
                if (isNewDay(prevDate, date)) {
                    val currentDate = Calendar.getInstance().apply { time = date }
                    returnString += "\uD83C\uDF11 | —— ${currentDate.get(Calendar.DAY_OF_MONTH)} ${currentDate.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.US)} ——\n"
                }
            }

            if (index == 0) {
                returnString += "\uD83D\uDCA1 |  Now   -  €%.2f".format(price) + suffix + "\n"
            } else {
                val formattedDate = dateFormat.format(date)
                var hourValue = formattedDate.split(":")[0].toInt()
                while (!dateTimeEmojiTable.containsKey(hourValue)) {
                    hourValue = (hourValue + 1) % 24
                }
                returnString += dateTimeEmojiTable[hourValue] + " | $formattedDate  -  €%.2f".format(price) + suffix + "\n"
            }
        }
        return returnString
    }

    private fun isNewDay(date1: Date, date2: Date): Boolean {
        val cal1 = Calendar.getInstance().apply { time = date1 }
        val cal2 = Calendar.getInstance().apply { time = date2 }
        return cal1.get(Calendar.DAY_OF_YEAR) != cal2.get(Calendar.DAY_OF_YEAR) ||
                cal1.get(Calendar.YEAR) != cal2.get(Calendar.YEAR)
    }

    override fun onBind(intent: Intent?) = null
}
