package com.example.voicechanger

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

class MainActivity : Activity() {

    @Volatile private var pitchSemi = 0f
    @Volatile private var rough = 0f
    @Volatile private var gain = 1f
    @Volatile private var running = false

    private var worker: Thread? = null
    private lateinit var btn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        fun title(t: String) = TextView(this).apply {
            text = t; setTextColor(Color.WHITE); textSize = 16f
            setPadding(0, 40, 0, 8)
        }

        val tPitch = title("Pitch: 0 st")
        val sPitch = SeekBar(this).apply {
            max = 48; progress = 24
            setOnSeekBarChangeListener(listener { p ->
                pitchSemi = (p - 24).toFloat(); tPitch.text = "Pitch: ${p - 24} st"
            })
        }
        val tRough = title("Rouquidao: 0%")
        val sRough = SeekBar(this).apply {
            max = 100; progress = 0
            setOnSeekBarChangeListener(listener { p ->
                rough = p / 100f; tRough.text = "Rouquidao: $p%"
            })
        }
        val tGain = title("Ganho: 1.0x")
        val sGain = SeekBar(this).apply {
            max = 400; progress = 100
            setOnSeekBarChangeListener(listener { p ->
                gain = p / 100f; tGain.text = "Ganho: ${"%.1f".format(p / 100f)}x"
            })
        }

        btn = Button(this).apply {
            text = "INICIAR"
            setOnClickListener { toggle() }
        }

        val note = TextView(this).apply {
            text = "Dica: se houver microfonia, abaixe o ganho e o volume do celular."
            setTextColor(Color.GRAY); textSize = 12f; gravity = Gravity.CENTER
            setPadding(0, 48, 0, 0)
        }

        root.addView(tPitch); root.addView(sPitch)
        root.addView(tRough); root.addView(sRough)
        root.addView(tGain); root.addView(sGain)
        root.addView(btn); root.addView(note)
        setContentView(root)
    }

    private fun listener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = onChange(p)
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun toggle() {
        if (running) { stopEngine(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        startEngine()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 1 && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) startEngine()
    }

    private fun startEngine() {
        running = true
        btn.text = "PARAR"
        worker = Thread { engine() }.also { it.start() }
    }

    private fun stopEngine() {
        running = false
        worker?.join(500)
        btn.text = "INICIAR"
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun engine() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = true

        val sr = 48000
        val frame = 240

        val minRec = AudioRecord.getMinBufferSize(
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, sr,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            max(minRec, frame * 4)
        )

        if (AcousticEchoCanceler.isAvailable())
            AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
        if (NoiseSuppressor.isAvailable())
            NoiseSuppressor.create(rec.audioSessionId)?.enabled = true

        val minTrk = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val trk = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(max(minTrk, frame * 4))
            .build()

        val size = 8192
        val mask = size - 1
        val buf = FloatArray(size)
        var w = 0
        var phase = 0f
        val win = 1200f

        var hpY = 0f; var hpX = 0f
        val hpA = exp(-2.0 * Math.PI * 120.0 / sr).toFloat()
        var env = 0f
        var gate = 0f
        var seed = 22222222

        val inS = ShortArray(frame)
        val outS = ShortArray(frame)

        fun readTap(d: Float): Float {
            val di = floor(d).toInt()
            val fr = d - di
            val a = buf[(w - 1 - di) and mask]
            val b = buf[(w - 2 - di) and mask]
            return a + (b - a) * fr
        }

        rec.startRecording()
        trk.play()

        while (running) {
            val n = rec.read(inS, 0, frame)
            if (n <= 0) continue

            val semi = pitchSemi
            val ratio = 2.0.pow(semi / 12.0).toFloat()
            val h = rough
            val g = gain
            val bypass = abs(semi) < 0.05f

            for (i in 0 until n) {
                var x = inS[i] / 32768f

                val y = hpA * (hpY + x - hpX)
                hpX = x; hpY = y; x = y

                val ax = abs(x)
                env += (ax - env) * (if (ax > env) 0.05f else 0.002f)
                val target = if (env > 0.006f) 1f else 0f
                gate += (target - gate) * 0.01f
                x *= gate

                buf[w] = x
                w = (w + 1) and mask
                var s = x
                if (!bypass) {
                    phase += (1f - ratio) / win
                    if (phase >= 1f) phase -= 1f
                    if (phase < 0f) phase += 1f
                    val p2 = if (phase + 0.5f >= 1f) phase - 0.5f else phase + 0.5f
                    val w1 = sin(Math.PI * phase).toFloat().let { it * it }
                    val w2 = sin(Math.PI * p2).toFloat().let { it * it }
                    s = readTap(phase * win) * w1 + readTap(p2 * win) * w2
                }

                if (h > 0f) {
                    seed = seed xor (seed shl 13)
                    seed = seed xor (seed ushr 17)
                    seed = seed xor (seed shl 5)
                    val noise = (seed / 2147483648f)
                    s = s * (1f - 0.3f * h) + noise * env * 2.5f * h
                }

                s = tanh(s * g)

                outS[i] = (s * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            trk.write(outS, 0, n)
        }

        rec.stop(); rec.release()
        trk.stop(); trk.release()
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = false
        am.mode = AudioManager.MODE_NORMAL
    }
}
