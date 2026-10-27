package com.example.depth25d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class DepthEstimator(context: Context) {
    private val interpreter: Interpreter
    private val inW: Int
    private val inH: Int

    init {
        val fd = context.assets.openFd("depth_model.tflite")
        val buf = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = Interpreter(buf, Interpreter.Options().apply { setNumThreads(4) })
        val s = interpreter.getInputTensor(0).shape()
        require(s.size == 4 && s[3] == 3) { "想定外の入力形状: ${s.joinToString()}" }
        inH = s[1]
        inW = s[2]
    }

    fun estimate(src: Bitmap): Bitmap {
        val scaled = Bitmap.createScaledBitmap(src, inW, inH, true)
        val input = ByteBuffer.allocateDirect(4 * inW * inH * 3).order(ByteOrder.nativeOrder())
        val px = IntArray(inW * inH)
        scaled.getPixels(px, 0, inW, 0, 0, inW, inH)
        for (p in px) {
            input.putFloat(Color.red(p) / 127.5f - 1f)
            input.putFloat(Color.green(p) / 127.5f - 1f)
            input.putFloat(Color.blue(p) / 127.5f - 1f)
        }
        input.rewind()

        val os = interpreter.getOutputTensor(0).shape()
        val oH = os[1]
        val oW = os[2]
        val count = os.fold(1) { a, b -> a * b }
        val ch = maxOf(1, count / (oH * oW))
        val out = ByteBuffer.allocateDirect(4 * count).order(ByteOrder.nativeOrder())
        interpreter.run(input, out)
        out.rewind()
        val v = FloatArray(count)
        out.asFloatBuffer().get(v)

        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (i in 0 until oH * oW) {
            val f = v[i * ch]
            if (f < mn) mn = f
            if (f > mx) mx = f
        }
        val range = if (mx - mn < 1e-6f) 1f else mx - mn
        val pixels = IntArray(oW * oH)
        for (i in pixels.indices) {
            val g = (((v[i * ch] - mn) / range) * 255f).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(g, g, g)
        }
        return Bitmap.createBitmap(pixels, oW, oH, Bitmap.Config.ARGB_8888)
    }
}