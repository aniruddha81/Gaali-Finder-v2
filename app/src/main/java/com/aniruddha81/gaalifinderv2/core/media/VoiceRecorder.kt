package com.aniruddha81.gaalifinderv2.core.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.IOException

/**
 * A thin wrapper around [MediaRecorder] for capturing a single voice clip.
 *
 * Deliberately not a Hilt singleton and not the app-wide [AudioPlayer]: a recorder is stateful,
 * short-lived, and belongs to the sheet that is on screen. The screen creates one, drives it
 * through [start] → [pause]/[resume] → [stop], and calls [release] when the sheet closes.
 *
 * Output is AAC in an MP4 container (`.m4a`) at a low mono bitrate, so a ~20 second take still
 * lands under the catalogue's 200 KB upload cap and plays back through the same [MediaPlayer]
 * the rest of the app already uses.
 */
class VoiceRecorder(private val context: Context) {

    enum class State { Idle, Recording, Paused, Stopped }

    var state: State = State.Idle
        private set

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    /** Wall-clock start of the current take, minus any time spent paused. */
    private var startElapsedMs: Long = 0L
    private var accumulatedMs: Long = 0L

    /**
     * Set once MediaRecorder reports the file-size ceiling has been hit (or is about to be).
     * The owning screen watches this and immediately calls [stop] so the take never grows past
     * what the catalogue will accept.
     */
    @Volatile
    var isLimitReached: Boolean = false
        private set

    /** Bytes written to the output file so far — cheap to poll on a UI tick. */
    val currentSizeBytes: Long
        get() = outputFile?.let { if (it.exists()) it.length() else 0L } ?: 0L

    /** Milliseconds of audio captured so far, live while recording. */
    val elapsedMs: Long
        get() = when (state) {
            State.Recording -> accumulatedMs + (nowMs() - startElapsedMs)
            State.Paused, State.Stopped -> accumulatedMs
            State.Idle -> 0L
        }

    /**
     * Begins a fresh recording, discarding anything from a previous take.
     *
     * @throws IllegalStateException if called while already recording
     * @throws IOException if the microphone or encoder could not be prepared
     */
    fun start() {
        check(state == State.Idle || state == State.Stopped) { "Recorder is already active" }

        releaseRecorder()
        deleteOutput()
        isLimitReached = false

        val target = File(recordingsDir(), "voice_${System.currentTimeMillis()}.m4a")
        val created = @Suppress("DEPRECATION") if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

        created.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(64_000)
            setOutputFile(target.absolutePath)
            // MediaRecorder stops writing once this ceiling is hit and fires the info listener
            // below. The sheet also polls currentSizeBytes, so the take is cut off even on the
            // devices where this callback is unreliable.
            setMaxFileSize(MAX_SIZE_BYTES)
            setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) {
                    isLimitReached = true
                }
            }
            prepare()
            start()
        }

        recorder = created
        outputFile = target
        accumulatedMs = 0L
        startElapsedMs = nowMs()
        state = State.Recording
    }

    /** Pauses without finalising the file. No-op unless currently recording. */
    fun pause() {
        val active = recorder ?: return
        if (state != State.Recording) return

        active.pause()
        accumulatedMs += nowMs() - startElapsedMs
        state = State.Paused
    }

    /** Resumes a paused take. No-op unless currently paused. */
    fun resume() {
        val active = recorder ?: return
        if (state != State.Paused) return

        active.resume()
        startElapsedMs = nowMs()
        state = State.Recording
    }

    /**
     * Finalises the file and returns it, or null if nothing usable was captured.
     *
     * Safe to call from either [State.Recording] or [State.Paused].
     */
    fun stop(): File? {
        val active = recorder ?: return null
        if (state == State.Recording) {
            accumulatedMs += nowMs() - startElapsedMs
        }

        val finished = runCatching {
            active.stop()
            true
        }.getOrDefault(false)
        active.release()
        recorder = null
        state = State.Stopped

        val file = outputFile
        if (!finished || file == null || !file.exists() || file.length() == 0L) {
            deleteOutput()
            return null
        }
        return file
    }

    /** Throws away the current take and any file it produced, returning to [State.Idle]. */
    fun discard() {
        releaseRecorder()
        deleteOutput()
        accumulatedMs = 0L
        startElapsedMs = 0L
        isLimitReached = false
        state = State.Idle
    }

    /** Releases native resources. Call from the owning screen's teardown. */
    fun release() {
        releaseRecorder()
        state = State.Stopped
    }

    private fun releaseRecorder() {
        recorder?.let { active ->
            runCatching { if (state == State.Recording || state == State.Paused) active.stop() }
            runCatching { active.release() }
        }
        recorder = null
    }

    private fun deleteOutput() {
        outputFile?.let { runCatching { it.delete() } }
        outputFile = null
    }

    private fun recordingsDir(): File =
        File(context.cacheDir, "recordings").apply { mkdirs() }

    private fun nowMs(): Long = System.currentTimeMillis()

    companion object {
        /** Anything shorter than this is treated as an accidental tap, not a recording. */
        const val MIN_DURATION_MS = 700L

        /**
         * Hard ceiling on the output file, a little under the catalogue's 200 KB upload cap so
         * the finished `.m4a` — container overhead included — still fits without the server
         * rejecting it. Recording is cut off the instant the file crosses this.
         */
        const val MAX_SIZE_BYTES = 190L * 1024
    }
}
