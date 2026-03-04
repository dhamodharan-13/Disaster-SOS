package com.innosence.disastersos.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.innosence.disastersos.MainActivity
import com.innosence.disastersos.R

/*
 * ============================================================================
 *  MeshService.kt — BACKGROUND GUARDIAN
 * ============================================================================
 *
 *  TANGLISH EXPLANATION:
 *  =====================
 *  Normally Android app ah minimize pannaa (home button press), app
 *  "background" ku pogum. Android battery save panna, background apps
 *  ah kill pannidum. Aanaa namma disaster app la idhu DANGEROUS!
 *
 *  Example scenario: Victim phone pocket la vechirukkaan. Screen off.
 *  Ippo vera phone irundhu SOS message varadhu. Normal app anaa,
 *  Android already kill pannirukkum — message miss aagum! 😱
 *
 *  SOLUTION: Foreground Service!
 *  ─────────────────────────────
 *  "Foreground Service" = Android ku solrom: "Idhu important service da,
 *  kill pannaadhey!" Android accept pannum, but oru condition:
 *  notification bar la oru permanent notification kaattanum.
 *  (WhatsApp call la "Ongoing call" nu notification varum la? Same concept.)
 *
 *  Adhu namma ku ok — user ku "Disaster SOS is active" nu status
 *  bar la theriyum. Reassuring ah irukum.
 *
 *  WHAT THIS SERVICE DOES:
 *  1. App background la irukumbothum mesh network alive ah vachirukum
 *  2. Incoming SOS messages continuously listen pannum
 *  3. Notification bar la "SOS Active" nu kaattum
 *  4. Android kill pannaa kuda, automatically restart aagum
 * ============================================================================
 */

class MeshService : Service() {

    companion object {
        private const val TAG = "MeshService"

        /*
         * NOTIFICATION_CHANNEL_ID — Android 8.0+ la notifications ku
         * "channel" create pannanum. Idhu user ku control kudukum:
         * "Idha mute pannanumaa?" nu decide panna. Namma SOS channel
         * ah HIGH importance kudukurom — miss aagakkoodaadhu!
         */
        private const val NOTIFICATION_CHANNEL_ID = "disaster_sos_mesh"
        private const val NOTIFICATION_ID = 1

        /**
         * Service ah START panna helper function.
         *
         * TANGLISH: Static function madhiri — object create pannama
         * MeshService.start(context) nu call pannalam.
         * startForegroundService() use panrom (Android 8.0+ requirement).
         */
        fun start(context: Context) {
            val intent = Intent(context, MeshService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Service ah STOP panna helper function.
         */
        fun stop(context: Context) {
            val intent = Intent(context, MeshService::class.java)
            context.stopService(intent)
        }
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  onCreate() — Service first time create aanaa run aagum
     * ────────────────────────────────────────────────────────────────
     *  TANGLISH: Service "birth" — notification channel create panrom.
     */
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "MeshService created ✅")
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  onStartCommand() — Service START command vandhaa run aagum
     * ────────────────────────────────────────────────────────────────
     *  
     *  TANGLISH:
     *  Idhu la 2 mukkiyamana vishayam nadakkum:
     *  1. startForeground() — Android ku solrom "kill pannaadhey!"
     *  2. Return START_STICKY — "Android crash pannaa, restart pannu!"
     *
     *  START_STICKY meaning:
     *  Android sometimes RAM kammi na background services kill pannum.
     *  START_STICKY return pannaa, Android memory free aana udan
     *  namma service ah automatically restart pannum. Disaster app ku
     *  idhu CRITICAL — eppovume running irukanum!
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Notification create panni foreground service ah start panrom
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        Log.d(TAG, "🛡️ MeshService started in foreground — protected from kill!")

        // START_STICKY = "Kill pannaa restart pannu" 
        return START_STICKY
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  createNotificationChannel() — Notification tube create panrom
     * ────────────────────────────────────────────────────────────────
     *
     *  TANGLISH:
     *  Android 8.0 la irundhu, notification anuppa "channel" create
     *  pannanum — TV channels madhiri. User specific channel ah
     *  mute/unmute panna mudiyum.
     *
     *  Namma channel: "Mesh Network Status" — HIGH importance
     *  kudukurom so user miss pannamaataan.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Mesh Network Status",  // User ku theriyura per
                NotificationManager.IMPORTANCE_LOW  // LOW = no sound, just icon
                // HIGH use pannaa every second beep aagum — annoying!
            ).apply {
                description = "Shows when the disaster mesh network is active"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  createNotification() — Status bar notification create panrom
     * ────────────────────────────────────────────────────────────────
     *
     *  TANGLISH:
     *  Idhu status bar la kaattum notification — "Disaster SOS Active"
     *  Tap pannaa app open aagum. User ku confidence kudukum:
     *  "Enna phone protect panniduchu" nu theriyum.
     */
    private fun createNotification(): Notification {
        // Notification tap pannaa MainActivity open aagum
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE  // Security requirement in Android 12+
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("🛡️ Disaster SOS Active")
            .setContentText("Mesh network is running — ready to relay SOS signals")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)  // Placeholder icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)  // User swipe panni dismiss panna mudiyaadhu — always visible
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  onBind() — Required override, but we don't use it
     * ────────────────────────────────────────────────────────────────
     *  TANGLISH: "Bound service" ku use aagum, but namma "started
     *  service" use panrom — adhunala null return panrom.
     *  (Android requirement — implement pannama compile aagaadhu)
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /*
     * ────────────────────────────────────────────────────────────────
     *  onDestroy() — Service close aana cleanup
     * ────────────────────────────────────────────────────────────────
     */
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MeshService destroyed")
    }
}
