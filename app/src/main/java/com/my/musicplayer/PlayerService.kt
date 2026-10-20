package com.my.musicplayer

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlayerService : MediaSessionService() {

    private var session: MediaSession? = null
    private var player: ExoPlayer? = null
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null

    override fun onCreate() {
        super.onCreate()
        val p = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        p.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = setupFx(audioSessionId)
        })
        player = p
        instance = this
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, p).setSessionActivity(open).build()
        setupFx(p.audioSessionId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        sleepRunnable?.let { main.removeCallbacks(it) }
        releaseFx()
        session?.release(); session = null
        player?.release(); player = null
        instance = null
        super.onDestroy()
    }

    private fun setupFx(id: Int) {
        releaseFx()
        if (id == 0) return
        try { eq = Equalizer(0, id) } catch (e: Exception) { }
        try { bass = BassBoost(0, id) } catch (e: Exception) { }
        try { virt = Virtualizer(0, id) } catch (e: Exception) { }
        applyFxInternal()
    }

    private fun releaseFx() {
        eq?.release(); bass?.release(); virt?.release()
        eq = null; bass = null; virt = null
    }

    private fun applyFxInternal() {
        val sp = getSharedPreferences("fx", MODE_PRIVATE)
        try {
            eq?.let { e ->
                val r = e.bandLevelRange
                for (i in 0 until e.numberOfBands) {
                    val lvl = sp.getInt("eq$i", 0).coerceIn(r[0].toInt(), r[1].toInt())
                    e.setBandLevel(i.toShort(), lvl.toShort())
                }
                e.setEnabled(true)
            }
        } catch (e: Exception) { }
        try {
            bass?.let {
                if (it.strengthSupported) it.setStrength(sp.getInt("bass", 0).toShort())
                it.setEnabled(true)
            }
        } catch (e: Exception) { }
        try {
            virt?.let {
                if (it.strengthSupported) it.setStrength(sp.getInt("virt", 0).toShort())
                it.setEnabled(true)
            }
        } catch (e: Exception) { }
    }

    companion object {
        var instance: PlayerService? = null
        private val main = Handler(Looper.getMainLooper())
        private var sleepRunnable: Runnable? = null

        fun applyFx() { instance?.applyFxInternal() }
        fun bandCount(): Int = instance?.eq?.numberOfBands?.toInt() ?: 0
        fun bandMin(): Int = instance?.eq?.bandLevelRange?.get(0)?.toInt() ?: -1500
        fun bandMax(): Int = instance?.eq?.bandLevelRange?.get(1)?.toInt() ?: 1500
        fun bandLabel(i: Int): String {
            val hz = (instance?.eq?.getCenterFreq(i.toShort()) ?: 0) / 1000
            return if (hz >= 1000) "%.1f kHz".format(hz / 1000f) else "$hz Hz"
        }

        fun setSleep(minutes: Int) {
            sleepRunnable?.let { main.removeCallbacks(it) }
            sleepRunnable = null
            if (minutes <= 0) return
            val r = Runnable { fadeOut() }
            sleepRunnable = r
            main.postDelayed(r, minutes * 60_000L)
        }

        private fun fadeOut() {
            val p = instance?.player ?: return
            var k = 10
            val step = object : Runnable {
                override fun run() {
                    k--
                    p.volume = k / 10f
                    if (k > 0) main.postDelayed(this, 200)
                    else { p.pause(); p.volume = 1f }
                }
            }
            main.post(step)
        }
    }
}
