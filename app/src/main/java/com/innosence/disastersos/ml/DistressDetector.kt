package com.innosence.disastersos.ml

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.innosence.disastersos.data.SOSPacket
import java.util.UUID

/*
 * ============================================================================
 *  DistressDetector.kt — THE DECISION MAKER
 * ============================================================================
 *
 *  TANGLISH EXPLANATION:
 *  =====================
 *  AudioClassifier audio classify pannum — "92% scream" nu solum.
 *  But classify panradhu mattum pothaadhula? Yaaro decide pannanum:
 *  "92% scream na SOS anuppanuma?"
 *
 *  Adhu dhan DistressDetector oda velai:
 *    1. Continuously (every 2 seconds) AudioClassifier ah call pannum
 *    2. Result paakkum: "Highest confidence evlo?"
 *    3. Threshold (85%) ku mela irundha → SOS PACKET CREATE PANNUM
 *    4. Callback function call pannum → MainActivity ku "alert da!" nu solum
 *
 *  ANALOGY:
 *  ────────
 *  AudioClassifier = Security camera (paakkum, record pannum)
 *  DistressDetector = Security guard (camera feed paathudtu,
 *                     "thief irukku!" nu alarm press pannum)
 *
 *  WHY SEPARATE FILES?
 *  ═══════════════════
 *  "Single Responsibility Principle" nu oru software rule irukku:
 *  "Each class should do ONE thing well."
 *    - AudioClassifier → Audio capture + classify panna mattum
 *    - DistressDetector → Decision making + SOS trigger panna mattum
 *  
 *  Idhu nalla practice because:
 *    - Bug fix easy — "SOS timing thapu" na DistressDetector la paaru
 *    - "Audio quality bad" na AudioClassifier la paaru
 *    - Testing easy — oru class ah test panna marutha class interfere aagaadhu
 * ============================================================================
 */

class DistressDetector(
    private val context: Context,
    /*
     * onDistressDetected — Idhu oru CALLBACK FUNCTION.
     *
     * Callback na enna? "Velai mudinjadha phone pannu" nu solla madhiri.
     * Namma MainActivity ku solrom: "Distress detect aana udan, idha run pannu."
     * MainActivity la namma define panniya code idhu vazhiya execute aagum.
     *
     * Real-world analogy:
     * Nee security guard kitta solra: "Thief vandhaa ennaku call pannu."
     * "Ennaku call pannu" → adhu dhan callback.
     * Guard thief ah paathaa, un number ku call pannum.
     */
    private val onDistressDetected: (SOSPacket) -> Unit
) {
    companion object {
        private const val TAG = "DistressDetector"

        /*
         * DETECTION_INTERVAL_MS — Evvalavu neram ku oru thadava check panrom?
         * 2000ms = 2 seconds. Every 2 seconds, mic open pannu, 1 second audio
         * edhu, classify pannu, result paaru.
         *
         * WHY 2 SECONDS?
         * - 1 second = too frequent, battery drain aagum
         * - 5 seconds = too slow, scream miss aagalaam
         * - 2 seconds = good balance between accuracy and battery life
         */
        private const val DETECTION_INTERVAL_MS = 2000L

        /*
         * CONFIDENCE_THRESHOLD — Minimum confidence level to trigger SOS.
         * 85% = "AI is 85% sure this is a real distress sound"
         *
         * WHY 85%?
         * - 50% = too low, car horns and dogs might trigger false alarms
         * - 95% = too high, might miss real screams that are partially muffled
         * - 85% = sweet spot — catches most real screams, blocks most noise
         */
        private const val CONFIDENCE_THRESHOLD = 85
    }

    // The audio classifier — does the actual mic recording + AI work
    private val audioClassifier = AudioClassifier(context)

    /*
     * HandlerThread — Background thread for audio processing.
     *
     * TANGLISH: Android la "main thread" (UI thread) la heavy calculation
     * pannakkoodaadhu. UI thread la mic recording panna, app freeze aagum
     * (button press panna response varaadhu). Adhunala, separate thread
     * la audio processing panrom.
     *
     * Thread = "Oru separate worker". Main thread = "Cashier at counter".
     * Namma cashier ah ("main thread") kitchen velai (audio processing)
     * panna sollave maatom — customer wait pannuvanga (UI freezes).
     * Adhukku separate kitchen worker (background thread) podrom.
     */
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    // Whether we're currently running the detection loop
    private var isRunning = false

    // Unique node ID for this phone (same as MainActivity's node ID in production)
    private val nodeId = UUID.randomUUID().toString().take(12)

    /*
     * ────────────────────────────────────────────────────────────────
     *  startListening() — Detection loop START
     * ────────────────────────────────────────────────────────────────
     *  
     *  TANGLISH:
     *  "Security guard ah duty ku anuppu." Guard every 2 seconds camera
     *  check pannum. Suspicious activity irundha alarm press pannum.
     *
     *  HOW:
     *  1. Background thread create panrom
     *  2. AudioClassifier initialize panrom (mic hardware grab)
     *  3. Detection loop start panrom (every 2 seconds repeat)
     */
    fun startListening() {
        if (isRunning) {
            Log.d(TAG, "Already listening, skipping start")
            return
        }

        // Step 1: Background thread create panrom
        handlerThread = HandlerThread("DistressDetectorThread").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        // Step 2: AudioClassifier initialize panrom
        val initialized = audioClassifier.initialize()
        if (!initialized) {
            Log.e(TAG, "Failed to initialize audio — mic permission irukka?")
            return
        }

        isRunning = true
        Log.d(TAG, "🎤 Distress detection started! Listening every ${DETECTION_INTERVAL_MS}ms...")

        // Step 3: Detection loop start panrom
        handler?.post(detectionRunnable)
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  detectionRunnable — The repeated detection cycle
     * ────────────────────────────────────────────────────────────────
     *
     *  TANGLISH:
     *  Idhu oru "loop" madhiri — oru cycle mudinjadha, 2 seconds wait panni,
     *  adhe cycle ah innoru thadava run pannum.
     *
     *  Oru cycle la enna nadakkum:
     *  1. Mic la 1 second audio record pannum
     *  2. AI model ku kudukum
     *  3. Result paakkum: highest confidence category enna?
     *  4. Threshold ku mela irundha → SOS trigger pannum
     *  5. 2 seconds wait pannum
     *  6. Step 1 ku thirumbi pogum
     *
     *  "Runnable" = "Run pannakoodiya code block" — Java/Kotlin la
     *  background work define panna use panrom.
     */
    private val detectionRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            try {
                // Step 1: Classify the current audio
                val results = audioClassifier.classifyAudio()

                if (results.isNotEmpty()) {
                    // Step 2: Find the highest confidence category
                    val bestMatch = results.maxByOrNull { it.value }

                    if (bestMatch != null) {
                        val category = bestMatch.key
                        val confidence = bestMatch.value

                        Log.d(TAG, "Detection: $category ($confidence%)")

                        // Step 3: Is it a distress sound above our threshold?
                        if (category != "background_noise" && confidence >= CONFIDENCE_THRESHOLD) {
                            Log.w(TAG, "🚨 DISTRESS DETECTED! $category at $confidence%")

                            // Step 4: Create SOS packet and trigger callback
                            val sosPacket = SOSPacket(
                                nodeId = nodeId,
                                latitude = 0.0,   // MainActivity will fill GPS later
                                longitude = 0.0,
                                distressType = category.uppercase(),
                                confidence = confidence,
                                timestamp = System.currentTimeMillis(),
                                batteryLevel = 0,  // MainActivity will fill battery later
                                hopCount = 0
                            )

                            // "CALLBACK CALL PANROM" — MainActivity ku signal anuppurom
                            onDistressDetected(sosPacket)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Detection cycle error", e)
            }

            // Step 5: 2 seconds wait panni, innoru cycle run pannu
            if (isRunning) {
                handler?.postDelayed(this, DETECTION_INTERVAL_MS)
            }
        }
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  stopListening() — Detection loop STOP
     * ────────────────────────────────────────────────────────────────
     *  "Security guard ah duty la irundhu withdraw pannu."
     *  Mic release pannum, background thread stop pannum.
     *  Battery save aaga idhu mukkiyam — user "Stop" press pannaa
     *  mic immediately off aaganum.
     */
    fun stopListening() {
        isRunning = false
        handler?.removeCallbacks(detectionRunnable)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        audioClassifier.cleanup()
        Log.d(TAG, "⏹ Distress detection stopped")
    }
}
