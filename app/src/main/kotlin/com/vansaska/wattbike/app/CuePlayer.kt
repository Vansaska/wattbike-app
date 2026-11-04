package com.vansaska.wattbike.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.vansaska.wattbike.core.Cue
import java.util.Locale

/**
 * Speaks ride cues. Uses a recorded clip from res/raw/cue_<name> if the app has one,
 * otherwise the phone's text-to-speech. Music is ducked while a cue plays so it cuts through.
 */
class CuePlayer(private val context: Context) {
    private val tag = "CuePlayer"
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()

    private var ttsReady = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) setUpTts()
    }

    private var player: MediaPlayer? = null

    private fun setUpTts() {
        tts.setAudioAttributes(attrs)
        tts.language = Locale.UK
        tts.setSpeechRate(1.05f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { audio.abandonAudioFocusRequest(focus) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { audio.abandonAudioFocusRequest(focus) }
        })
    }

    @SuppressLint("DiscouragedApi")
    private fun clipFor(cue: Cue): Int =
        context.resources.getIdentifier("cue_" + cue.name.lowercase(Locale.ROOT), "raw", context.packageName)

    fun hasRecordedClips(): Boolean = Cue.entries.any { clipFor(it) != 0 }

    fun play(cue: Cue) {
        audio.requestAudioFocus(focus)
        val clip = clipFor(cue)
        if (clip != 0) {
            player?.release()
            player = MediaPlayer.create(context, clip, attrs, audio.generateAudioSessionId())?.apply {
                setOnCompletionListener {
                    it.release()
                    if (player === it) player = null
                    audio.abandonAudioFocusRequest(focus)
                }
                start()
            }
            if (player != null) return
        }
        if (ttsReady) {
            tts.speak(cue.phrase, TextToSpeech.QUEUE_FLUSH, null, cue.name)
        } else {
            Log.w(tag, "No clip and text-to-speech not ready for $cue")
            audio.abandonAudioFocusRequest(focus)
        }
    }

    fun shutdown() {
        player?.release()
        tts.shutdown()
    }
}
