package com.my.musicplayer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.concurrent.Executor

class MainActivity : Activity() {

    data class Song(val id: Long, val title: String, val artist: String)

    private val all = ArrayList<Song>()
    private val shown = ArrayList<Song>()
    private var ctrl: MediaController? = null
    private val h = Handler(Looper.getMainLooper())
    private var dragging = false

    private lateinit var adapter: ArrayAdapter<Song>
    private lateinit var countTv: TextView
    private lateinit var titleTv: TextView
    private lateinit var artistTv: TextView
    private lateinit var seek: SeekBar
    private lateinit var curTv: TextView
    private lateinit var durTv: TextView
    private lateinit var playBtn: TextView
    private lateinit var shufBtn: TextView
    private lateinit var repBtn: TextView

    private val BG = Color.parseColor("#0E1220")
    private val PANEL = Color.parseColor("#171D33")
    private val ACC = Color.parseColor("#B79CFF")
    private val MUTE = Color.parseColor("#8D97B8")

    private val presets = linkedMapOf(
        "Flat" to intArrayOf(0, 0, 0, 0, 0),
        "Bass" to intArrayOf(8, 5, 0, -1, 0),
        "Pop" to intArrayOf(-1, 2, 4, 2, -1),
        "Rock" to intArrayOf(5, 3, -2, 3, 5),
        "Hip-Hop" to intArrayOf(6, 4, 0, 2, 3),
        "Classical" to intArrayOf(4, 2, -1, 2, 4),
        "Dance" to intArrayOf(6, 3, 0, 2, 4),
        "Acoustic" to intArrayOf(4, 3, 2, 3, 2)
    )

    private val audioPerm: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, sp: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = sp; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun btn(t: String, sp: Float = 24f, f: () -> Unit) = tv(t, sp).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener { f() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        buildUi()
        if (checkSelfPermission(audioPerm) == PackageManager.PERMISSION_GRANTED) loadSongs()
        else {
            val need = if (Build.VERSION.SDK_INT >= 33)
                arrayOf(audioPerm, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(audioPerm)
            requestPermissions(need, 1)
        }
        connect()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (checkSelfPermission(audioPerm) == PackageManager.PERMISSION_GRANTED) loadSongs()
        else countTv.text = "Allow music access in app settings to see your songs"
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        ctrl?.release()
        super.onDestroy()
    }

    // ---------- UI ----------
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(tv("Music Player", 22f, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btn("Equalizer", 14f) { showEq() })
        bar.addView(btn("Timer", 14f) { showTimer() })
        root.addView(bar)

        val search = EditText(this).apply {
            hint = "Search songs"; setHintTextColor(MUTE); setTextColor(Color.WHITE)
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = filter(s?.toString() ?: "")
            })
        }
        root.addView(search)
        countTv = tv("", 12f, MUTE).apply { setPadding(dp(4), dp(4), 0, dp(4)) }
        root.addView(countTv)

        adapter = object : ArrayAdapter<Song>(
            this@MainActivity, android.R.layout.simple_list_item_2, android.R.id.text1, shown
        ) {
            override fun getView(p: Int, v: View?, g: ViewGroup): View {
                val r = super.getView(p, v, g)
                val s = getItem(p)!!
                r.findViewById<TextView>(android.R.id.text1).apply {
                    text = s.title; setTextColor(Color.WHITE); isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }
                r.findViewById<TextView>(android.R.id.text2).apply {
                    text = s.artist; setTextColor(MUTE); isSingleLine = true
                }
                return r
            }
        }
        val list = ListView(this).apply {
            divider = null
            this.adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> playAt(pos) }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PANEL)
            setPadding(dp(16), dp(12), dp(16), dp(10))
        }
        titleTv = tv("Nothing playing", 17f, bold = true).apply {
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        }
        artistTv = tv("", 13f, MUTE)
        panel.addView(titleTv); panel.addView(artistTv)

        seek = SeekBar(this)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) curTv.text = fmt(p.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                ctrl?.seekTo(sb.progress.toLong()); dragging = false
            }
        })
        curTv = tv("0:00", 12f, MUTE); durTv = tv("0:00", 12f, MUTE)
        val times = LinearLayout(this)
        times.addView(curTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(durTv)
        panel.addView(seek); panel.addView(times)

        shufBtn = btn("⇄", 22f) { ctrl?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
        val prevBtn = btn("⏮", 24f) { ctrl?.seekToPreviousMediaItem() }
        playBtn = btn("▶", 34f) { togglePlay() }
        val nextBtn = btn("⏭", 24f) { ctrl?.seekToNextMediaItem() }
        repBtn = btn("↻", 22f) {
            ctrl?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        for (b in listOf(shufBtn, prevBtn, playBtn, nextBtn, repBtn))
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(row)
        root.addView(panel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(-dp(16), 0, -dp(16), 0)
        })
        setContentView(root)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    // ---------- songs ----------
    private fun loadSongs() {
        all.clear()
        val a = MediaStore.Audio.Media.ARTIST
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, a),
            "${MediaStore.Audio.Media.IS_MUSIC}!=0 AND ${MediaStore.Audio.Media.DURATION}>30000",
            null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE"
        )?.use { c ->
            while (c.moveToNext()) {
                val art = c.getString(2)
                all.add(Song(c.getLong(0), c.getString(1) ?: "Unknown",
                    if (art == null || art == "<unknown>") "Unknown artist" else art))
            }
        }
        filter("")
    }

    private fun filter(q: String) {
        val s = q.trim().lowercase()
        shown.clear()
        shown.addAll(if (s.isEmpty()) all else all.filter { (it.title + " " + it.artist).lowercase().contains(s) })
        adapter.notifyDataSetChanged()
        countTv.text = if (all.isEmpty()) "No songs found" else "${shown.size} songs"
    }

    // ---------- playback ----------
    private fun connect() {
        val token = SessionToken(this, ComponentName(this, PlayerService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        f.addListener({
            try {
                ctrl = f.get()
                ctrl?.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = refresh()
                })
                refresh()
                h.post(ticker)
            } catch (e: Exception) { }
        }, Executor { h.post(it) })
    }

    private val ticker = object : Runnable {
        override fun run() {
            ctrl?.let {
                if (!dragging && it.duration > 0) {
                    seek.max = it.duration.toInt()
                    seek.progress = it.currentPosition.toInt()
                    curTv.text = fmt(it.currentPosition)
                    durTv.text = fmt(it.duration)
                }
            }
            h.postDelayed(this, 500)
        }
    }

    private fun refresh() {
        val c = ctrl ?: return
        val m = c.mediaMetadata
        titleTv.text = m.title ?: "Nothing playing"
        artistTv.text = m.artist ?: ""
        playBtn.text = if (c.isPlaying) "❚❚" else "▶"
        shufBtn.setTextColor(if (c.shuffleModeEnabled) ACC else Color.WHITE)
        repBtn.text = if (c.repeatMode == Player.REPEAT_MODE_ONE) "↻1" else "↻"
        repBtn.setTextColor(if (c.repeatMode == Player.REPEAT_MODE_OFF) Color.WHITE else ACC)
    }

    private fun playAt(pos: Int) {
        val c = ctrl ?: return
        val items = shown.map {
            MediaItem.Builder()
                .setMediaId(it.id.toString())
                .setUri(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.id))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist).build())
                .build()
        }
        c.setMediaItems(items, pos, 0)
        c.prepare()
        c.play()
    }

    private fun togglePlay() {
        val c = ctrl ?: return
        if (c.mediaItemCount == 0) { if (shown.isNotEmpty()) playAt(0); return }
        if (c.isPlaying) c.pause() else c.play()
    }

    // ---------- timer ----------
    private fun showTimer() {
        val mins = intArrayOf(15, 30, 45, 60, 0)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Sleep timer")
            .setItems(arrayOf("15 minutes", "30 minutes", "45 minutes", "60 minutes", "Off")) { _, i ->
                PlayerService.setSleep(mins[i])
                toast(if (mins[i] == 0) "Timer off" else "Music will stop in ${mins[i]} minutes")
            }.show()
    }

    // ---------- equalizer ----------
    private fun showEq() {
        val n = PlayerService.bandCount()
        if (n == 0) { toast("Start a song first, then open the equalizer"); return }
        val sp = getSharedPreferences("fx", MODE_PRIVATE)
        val lo = PlayerService.bandMin()
        val hi = PlayerService.bandMax()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        fun bar(label: String, max: Int, prog: Int, onChange: (Int) -> Unit) {
            box.addView(tv(label, 13f, MUTE))
            val s = SeekBar(this)
            s.max = max
            s.progress = prog.coerceIn(0, max)
            s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { if (u) onChange(p) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
            box.addView(s)
        }
        for (i in 0 until n) {
            bar(PlayerService.bandLabel(i), hi - lo, sp.getInt("eq$i", 0) - lo) { p ->
                sp.edit().putInt("eq$i", p + lo).apply(); PlayerService.applyFx()
            }
        }
        bar("Bass boost", 1000, sp.getInt("bass", 0)) { p ->
            sp.edit().putInt("bass", p).apply(); PlayerService.applyFx()
        }
        bar("3D sound (best with headphones)", 1000, sp.getInt("virt", 0)) { p ->
            sp.edit().putInt("virt", p).apply(); PlayerService.applyFx()
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Equalizer")
            .setView(ScrollView(this).apply { addView(box) })
            .setNeutralButton("Presets") { _, _ -> showPresets() }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showPresets() {
        val names = presets.keys.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Presets")
            .setItems(names) { _, i ->
                val v = presets[names[i]]!!
                val n = PlayerService.bandCount()
                val lo = PlayerService.bandMin()
                val hi = PlayerService.bandMax()
                val e = getSharedPreferences("fx", MODE_PRIVATE).edit()
                for (b in 0 until n) {
                    val k = if (n == 1) 0 else Math.round(b * 4f / (n - 1))
                    e.putInt("eq$b", (v[k] * 100).coerceIn(lo, hi))
                }
                e.apply()
                PlayerService.applyFx()
                showEq()
            }.show()
    }
}
