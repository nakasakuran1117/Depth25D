package com.example.depth25d

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface

class MainActivity : Activity() {
    private lateinit var glView: ParallaxView
    private lateinit var depthView: ImageView
    private lateinit var status: TextView
    private var estimator: DepthEstimator? = null
    private val reqPick = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "画像を選んでください" }
        glView = ParallaxView(this)
        depthView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }

        val button = Button(this).apply {
            text = "画像を選ぶ"
            setOnClickListener {
                val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
                startActivityForResult(i, reqPick)
            }
        }
        val tilt = CheckBox(this).apply {
            text = "傾き"
            setOnCheckedChangeListener { _, on ->
                if (on && !glView.tiltAvailable) {
                    isChecked = false
                    status.text = "この端末は傾きセンサー非対応です"
                } else {
                    glView.setTilt(on)
                }
            }
        }
        val auto = CheckBox(this).apply {
            text = "自動"
            setOnCheckedChangeListener { _, on -> glView.setAuto(on) }
        }
        val checks = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tilt)
            addView(auto)
        }
        val label = TextView(this).apply { text = "奥行きの強さ" }
        val seek = SeekBar(this).apply {
            max = 100
            progress = 42
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    glView.strength = p / 100f * 0.6f
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 96, 24, 24)
            addView(button)
            addView(status)
            addView(checks)
            addView(label)
            addView(seek)
            addView(glView, LinearLayout.LayoutParams(-1, 0, 2f))
            addView(depthView, LinearLayout.LayoutParams(-1, 0, 0.6f))
        }
        setContentView(root)
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqPick || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val bmp = loadBitmap(uri, 1536) ?: return
        depthView.setImageBitmap(null)
        status.text = "深度推定中…"
        Thread {
            try {
                val est = estimator ?: DepthEstimator(this).also { estimator = it }
                val t0 = System.currentTimeMillis()
                val depth = est.estimate(bmp)
                val ms = System.currentTimeMillis() - t0
                runOnUiThread {
                    depthView.setImageBitmap(depth)
                    glView.setScene(bmp, depth)
                    status.text = "完了: ${ms}ms（指でドラッグ）"
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "エラー: ${e.message}" }
            }
        }.start()
    }

    private fun loadBitmap(uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val orientation = contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (orientation) {
            6 -> m.postRotate(90f)
            3 -> m.postRotate(180f)
            8 -> m.postRotate(270f)
        }
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale < 1f) m.postScale(scale, scale)
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}
