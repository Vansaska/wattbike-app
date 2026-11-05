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
    private fun raw(name: String): Int = context.resources.getIdentifier(name, "raw", context.packageName)

    private fun key(cue: Cue) = cue.name.lowercase(Locale.ROOT)

    /** Plays the cue's chime and/or its spoken phrase (chime first). */
    fun play(cue: Cue, chime: Boolean, voice: Boolean) {
        if (!chime && !voice) return
        audio.requestAudioFocus(focus)
        if (chime && playClip(raw("chime_" + key(cue))) { if (voice) speak(cue) else audio.abandonAudioFocusRequest(focus) }) return
        if (voice) speak(cue) else audio.abandonAudioFocusRequest(focus)
    }

    private fun speak(cue: Cue) {
        // A recorded voice clip (res/raw/cue_<name>) wins over the phone's text-to-speech.
        if (playClip(raw("cue_" + key(cue))) { audio.abandonAudioFocusRequest(focus) }) return
        if (ttsReady) {
            tts.speak(cue.phrase, TextToSpeech.QUEUE_FLUSH, null, cue.name)
        } else {
            Log.w(tag, "Text-to-speech not ready for $cue")
            audio.abandonAudioFocusRequest(focus)
        }
    }

    private fun playClip(id: Int, then: () -> Unit): Boolean {
        if (id == 0) return false
        player?.release()
        val p = MediaPlayer.create(context, id, attrs, audio.generateAudioSessionId()) ?: return false
        player = p
        p.setOnCompletionListener {
            it.release()
            if (player === it) player = null
            then()
        }
        p.start()
        return true
    }

    fun shutdown() {
        player?.release()
        tts.shutdown()
    }
}
