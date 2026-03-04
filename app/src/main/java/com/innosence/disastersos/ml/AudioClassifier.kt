package com.innosence.disastersos.ml

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/*
 * ============================================================================
 *  AudioClassifier.kt — PHONE MICROPHONE AUDIO CAPTURE + AI INFERENCE
 * ============================================================================
 *
 *  TANGLISH EXPLANATION:
 *  =====================
 *  Idhu enna pannum na, phone oda microphone ah ON panni, audio data capture
 *  pannum. Aprom adha TensorFlow Lite model ku kudukum. Model paathudtu
 *  solum: "Idhu 92% chance scream, 5% chance wind, 3% chance silence."
 *
 *  Ippo namma real .tflite model illa, adhunala "simulated classifier"
 *  use panrom — random confidence values generate pannum. Later Edge
 *  Impulse la train panna real model drop-in replace pannalam.
 *
 *  FLOW:
 *  Phone Mic → AudioRecord (raw bytes) → processAudio() → confidence score
 *
 *  IMPORTANT CONCEPTS:
 *  - SAMPLE_RATE (16000 Hz) = per second 16,000 audio samples edukurom.
 *    Idhu speech/scream detect panna standard rate. Music ku 44100 Hz
 *    use pannuvanga, but namma ku 16000 pothum — battery save aagum.
 *
 *  - RECORDING_LENGTH (1 second) = Oru second audio eduthudtu analyze panrom.
 *    Romba naal record panna venaam — scream typically 0.5-2 seconds dhan.
 *
 *  - AudioRecord = Android oda low-level microphone API. MediaRecorder
 *    madhiri file save pannadhu — namma ku raw bytes venum, file venaam.
 * ============================================================================
 */

class AudioClassifier(private val context: Context) {

    companion object {
        private const val TAG = "AudioClassifier"

        /*
         * SAMPLE_RATE — Per second evvalavu audio points edukanum?
         * 16000 Hz = 16,000 points/second. Idhu speech recognition ku
         * industry standard. Higher = better quality but more battery drain.
         */
        private const val SAMPLE_RATE = 16000

        /*
         * RECORDING_LENGTH_SECONDS — Evvalavu neram record panrom per cycle?
         * 1 second = oru cycle. Every second, namma AI oru prediction pannum.
         * "Idhu scream ah illaya?" nu second ku oru thadava check pannum.
         */
        private const val RECORDING_LENGTH_SECONDS = 1

        /*
         * Total number of audio samples per recording cycle.
         * 16000 samples/sec × 1 sec = 16000 samples per cycle.
         */
        private const val RECORDING_LENGTH = SAMPLE_RATE * RECORDING_LENGTH_SECONDS

        // Audio label categories — namma model differentiate panra sounds
        val LABELS = arrayOf("background_noise", "scream", "sos_tap", "crash")
    }

    /*
     * AudioRecord — Android oda raw microphone access.
     * Idhu phone oda mic hardware ku directly connect aagum.
     * "?" = nullable, because mic permission illa na null aagum.
     */
    private var audioRecord: AudioRecord? = null

    // Whether the classifier is currently recording
    private var isRecording = false

    /*
     * ────────────────────────────────────────────────────────────────
     *  initialize() — Microphone setup panrom
     * ────────────────────────────────────────────────────────────────
     *  Idhu AudioRecord object create pannum. Oru water pipe open
     *  panra madhiri — mic irundhu audio "flow" aaga start aagum.
     *
     *  Parameters explained:
     *  - MediaRecorder.AudioSource.MIC → "Phone oda built-in mic use pannu"
     *  - CHANNEL_IN_MONO → Single channel (stereo venaam, battery waste)
     *  - ENCODING_PCM_16BIT → Each sample 16-bit number ah store aagum
     *    (standard quality, -32768 to +32767 range)
     */
    fun initialize(): Boolean {
        return try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize.coerceAtLeast(RECORDING_LENGTH * 2)
            )

            Log.d(TAG, "AudioClassifier initialized successfully ✅")
            true
        } catch (e: SecurityException) {
            // Mic permission illa na idhu varum
            Log.e(TAG, "Microphone permission denied! ❌", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioRecord ❌", e)
            false
        }
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  classifyAudio() — Oru second audio eduthudtu classify panrom
     * ────────────────────────────────────────────────────────────────
     *
     *  Idhu enna return pannum:
     *  Map<String, Int> = {"scream": 92, "background_noise": 5, "sos_tap": 2, "crash": 1}
     *  
     *  Appo namma code check pannum:
     *  "scream" = 92% → 85% threshold ku mela irukku → SOS TRIGGER! 🚨
     *
     *  CURRENT STATUS: Simulated classifier (random values).
     *  TODO: Replace with real TFLite inference when model is trained.
     */
    fun classifyAudio(): Map<String, Int> {
        if (audioRecord == null) {
            Log.w(TAG, "AudioRecord not initialized, returning empty result")
            return emptyMap()
        }

        return try {
            // Step 1: Mic la irundhu raw audio bytes read panrom
            val audioBuffer = ShortArray(RECORDING_LENGTH)

            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.startRecording()
                audioRecord?.read(audioBuffer, 0, RECORDING_LENGTH)
                audioRecord?.stop()
            }

            // Step 2: Audio data analyze panrom
            // Ippo simulated version — audio oda volume check panni
            // approximate classification panrom
            val result = simulateClassification(audioBuffer)

            Log.d(TAG, "Classification result: $result")
            result

        } catch (e: Exception) {
            Log.e(TAG, "Classification failed ❌", e)
            emptyMap()
        }
    }

    /*
     * ────────────────────────────────────────────────────────────────
     *  simulateClassification() — TEMPORARY simulated AI
     * ────────────────────────────────────────────────────────────────
     *
     *  Idhu real AI illa — oru smart placeholder dhan.
     *  Audio volume (amplitude) check pannum:
     *    - Volume romba high ah irundha → "Maybe scream" nu higher
     *      confidence kudukum
     *    - Volume low ah irundha → "Probably background noise" nu solum
     *
     *  WHY simulated? Namma Edge Impulse la model train panra varaikkum
     *  idhu use panrom. Real model varapo, idhu replace aagum.
     *
     *  REAL MODEL INTEGRATION (future):
     *  1. Edge Impulse la model train pannu
     *  2. .tflite file export pannu
     *  3. assets/ folder la podhu
     *  4. Idhu function la TFLite interpreter use pannu
     */
    private fun simulateClassification(audioBuffer: ShortArray): Map<String, Int> {
        // Calculate RMS (Root Mean Square) — audio volume oda measure
        // Higher RMS = louder sound
        val rms = calculateRMS(audioBuffer)

        /*
         * Simple threshold-based simulation:
         * - RMS > 5000 → Very loud → Likely scream (high confidence)
         * - RMS > 2000 → Moderate → Could be something (medium confidence)
         * - RMS < 2000 → Quiet → Probably background noise
         *
         * Real model prediction la, neural network idha vitta accurate
         * ah pannum because it learns PATTERNS, not just volume.
         * For example, a car horn is loud but has a steady frequency.
         * A scream is loud AND has specific frequency variations.
         */
        return if (rms > 5000) {
            mapOf(
                "scream" to (70 + (Math.random() * 25).toInt()),
                "background_noise" to (5 + (Math.random() * 10).toInt()),
                "sos_tap" to (Math.random() * 10).toInt(),
                "crash" to (Math.random() * 10).toInt()
            )
        } else if (rms > 2000) {
            mapOf(
                "scream" to (30 + (Math.random() * 30).toInt()),
                "background_noise" to (30 + (Math.random() * 20).toInt()),
                "sos_tap" to (10 + (Math.random() * 15).toInt()),
                "crash" to (Math.random() * 15).toInt()
            )
        } else {
            mapOf(
                "scream" to (Math.random() * 15).toInt(),
                "background_noise" to (70 + (Math.random() * 25).toInt()),
                "sos_tap" to (Math.random() * 10).toInt(),
                "crash" to (Math.random() * 5).toInt()
            )
        }
    }

    /*
     * RMS = Root Mean Square — audio signal oda "average loudness"
     * Formula: sqrt(sum(sample²) / n)
     *
     * Simple ah sonna: Ellaa audio points ah square pannu (negative
     * values handle panna), average edhu, aprom square root edhu.
     * Result = oru single number that represents "how loud was this sound?"
     */
    private fun calculateRMS(buffer: ShortArray): Double {
        var sum = 0.0
        for (sample in buffer) {
            sum += sample.toDouble() * sample.toDouble()
        }
        return Math.sqrt(sum / buffer.size)
    }

    /*
     * cleanup() — Resources release panrom
     * Mic hardware release pannama poita, vera apps mic use panna mudiyaadhu.
     */
    fun cleanup() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.d(TAG, "AudioClassifier cleaned up ✅")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error", e)
        }
    }
}
