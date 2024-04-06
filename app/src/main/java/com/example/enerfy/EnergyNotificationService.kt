package com.example.enerfy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

class EnergyNotificationService : Service() {

    private var firstTime = true

    private val notificationChannel = "EnergyNotificationChannel"
    private val notificationId = 1

    private var notificationManager: NotificationManager? = null
    private var notificationBuilder: Notification.Builder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationBuilder = Notification.Builder(this, notificationChannel)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(notificationId, createInitialNotification())

        // Start your notification update logic here
        startNotificationUpdateTask()

        return START_STICKY
    }

    private fun createInitialNotification(): Notification {
        return this.notificationBuilder!!
            .setContentTitle("\uD83D\uDD0C  Today's Energy Prices  ⚡\uFE0F")
            .setSmallIcon(R.drawable.notification_icon)
            .setContentText("Loading...")
            .build()
    }

    private fun createForegroundNotification(priceData: Map<Date, Double>): Notification {
        if (firstTime){ // Setup the notification channel
            this.notificationBuilder!!
                .setContentTitle("\uD83D\uDD0C  Today's Energy Prices  ⚡\uFE0F")
                .setSmallIcon(R.drawable.notification_icon)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)

            val notificationChannel = NotificationChannel(notificationChannel, "Hourly Energy Prices", NotificationManager.IMPORTANCE_HIGH)
            notificationChannel.importance = NotificationManager.IMPORTANCE_HIGH
            notificationChannel.enableVibration(true)
            notificationChannel.vibrationPattern = longArrayOf(0, 1000, 500, 1000)
            notificationManager!!.createNotificationChannel(notificationChannel)

            firstTime = false
        }

        return this.notificationBuilder!!
            .setContentText(generateHourlyNotificationMessage(priceData))
            .setStyle(Notification.BigTextStyle().bigText(generateHourlyNotificationMessage(priceData)))
            .build()
    }

    private fun startNotificationUpdateTask() {
        CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                val priceData = EnergyPriceAPI().getTodaysEnergyPrices()
                notificationManager!!.notify(notificationId, createForegroundNotification(priceData))
                Thread.sleep(millisTillNextHour())
            }
        }
    }

    private fun millisTillNextHour(): Long {
        val now = LocalDateTime.now()
        val nextHour = now.plusHours(1).truncatedTo(ChronoUnit.HOURS)
        return now.until(nextHour, ChronoUnit.MILLIS)
    }

    private fun generateHourlyNotificationMessage(priceData: Map<Date, Double>): String{
        var returnString = ""
        val dateFormat = SimpleDateFormat("HH:mm", Locale.US)

        var lastPrice = 0.0
        priceData.keys.forEach { date ->
            val price = priceData[date]
            if (date == Date() || date.before(Date())) {
                returnString += "\uD83D\uDCA1Now: €%.2f\n".format(priceData[date])

                lastPrice = price!!
                return@forEach
            }

            val formattedDate = dateFormat.format(date)

            returnString += if (price!!.compareTo(lastPrice) > 0) {
                "\uD83D\uDCC8"
            } else if (price.compareTo(lastPrice) < 0) {
                "\uD83D\uDCC9"
            } else {
                "\uD83D\uDFF0"
            }
            returnString += "$formattedDate: €%.2f\n".format(price)

            lastPrice = price
        }

        return returnString
    }

}