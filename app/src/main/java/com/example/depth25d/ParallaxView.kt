package com.example.depth25d

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sin

// 傾きの向きが逆に感じたら、どちらかを -1f にする
private const val SIGN_X = 1f
private const val SIGN_Y = 1f

class ParallaxView(context: Context) : GLSurfaceView(context), SensorEventListener {
    private val r = Renderer()
    private var dragX = 0f
    private var dragY = 0f
    private var lastX = 0f
    private var lastY = 0f

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var tiltOn = false
    private var autoOn = false
    private var base: FloatArray? = null
    private var sx = 0f
    private var sy = 0f
    private val rot = FloatArray(9)

    val tiltAvailable: Boolean get() = rotSensor != null

    var strength: Float
        get() = r.strength
        set(v) {
            r.strength = v
            requestRender()
        }

    init {
        setEGLContextClientVersion(3)
        setRenderer(r)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setScene(image: Bitmap, depth: Bitmap) {
        queueEvent { r.load(image, depth) }
        requestRender()
    }

    fun setTilt(on: Boolean) {
        tiltOn = on
        base = null
        sx = 0f
        sy = 0f
        r.tiltX = 0f
        r.tiltY = 0f
        if (on) registerSensor() else sm.unregisterListener(this)
        updateMode()
    }

    fun setAuto(on: Boolean) {
        autoOn = on
        r.auto = on
        updateMode()
    }

    private fun updateMode() {
        renderMode = if (tiltOn || autoOn) RENDERMODE_CONTINUOUSLY else RENDERMODE_WHEN_DIRTY
        requestRender()
    }

    private fun registerSensor() {
        rotSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onResume() {
        super.onResume()
        if (tiltOn) {
            base = null
            registerSensor()
        }
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        val ux = rot[6]
        val uz = rot[8]
        val b = base
        if (b == null) {
            base = floatArrayOf(ux, uz)
            return
        }
        val tx = (ux - b[0]) * 3f * SIGN_X
        val ty = (uz - b[1]) * 3f * SIGN_Y
        sx += (tx - sx) * 0.15f
        sy += (ty - sy) * 0.15f
        r.tiltX = sx
        r.tiltY = sy
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                dragX = (dragX + (e.x - lastX) / width * 2f).coerceIn(-1f, 1f)
                dragY = (dragY - (e.y - lastY) / height * 2f).coerceIn(-1f, 1f)
                lastX = e.x
                lastY = e.y
                r.dragX = dragX
                r.dragY = dragY
                requestRender()
            }
        }
        return true
    }

    private class Renderer : GLSurfaceView.Renderer {
        @Volatile var dragX = 0f
        @Volatile var dragY = 0f
        @Volatile var tiltX = 0f
        @Volatile var tiltY = 0f
        @Volatile var auto = false
        @Volatile var strength = 0.25f

        private var prog = 0
        private var texImage = 0
        private var texDepth = 0
        private var imgAspect = 1f
        private var vw = 1
        private var vh = 1
        private var loaded = false
        private var lastImage: Bitmap? = null
        private var lastDepth: Bitmap? = null
        private val n = 64
        private val vbuf: FloatBuffer
        private val ibuf: ShortBuffer
        private val idxCount: Int

        init {
            val verts = FloatArray((n + 1) * (n + 1) * 4)
            var k = 0
            for (j in 0..n) for (i in 0..n) {
                val x = i / n.toFloat()
                val y = j / n.toFloat()
                verts[k++] = -1f + 2f * x
                verts[k++] = 1f - 2f * y
                verts[k++] = x
                verts[k++] = y
            }
            val idx = ShortArray(n * n * 6)
            var m = 0
            for (j in 0 until n) for (i in 0 until n) {
                val a = j * (n + 1) + i
                val b = a + 1
                val c = a + (n + 1)
                val d = c + 1
                idx[m++] = a.toShort(); idx[m++] = c.toShort(); idx[m++] = b.toShort()
                idx[m++] = b.toShort(); idx[m++] = c.toShort(); idx[m++] = d.toShort()
            }
            idxCount = idx.size
            vbuf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(verts); position(0) }
            ibuf = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder())
                .asShortBuffer().apply { put(idx); position(0) }
        }

        private val vsrc = """#version 300 es
layout(location=0) in vec2 aPos;
layout(location=1) in vec2 aUv;
uniform sampler2D uDepth;
uniform vec2 uOffset;
uniform float uStrength;
uniform vec2 uScale;
out vec2 vUv;
void main() {
  float d = textureLod(uDepth, aUv, 0.0).r;
  float edge = min(min(aUv.x, 1.0 - aUv.x), min(aUv.y, 1.0 - aUv.y));
  float fade = smoothstep(0.0, 0.08, edge);
  vec2 p = aPos + uOffset * (d - 0.5) * uStrength * fade;
  gl_Position = vec4(p * uScale, 0.0, 1.0);
  vUv = aUv;
}"""

        private val fsrc = """#version 300 es
precision mediump float;
uniform sampler2D uImage;
in vec2 vUv;
out vec4 oColor;
void main() { oColor = texture(uImage, vUv); }"""

        private fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException(GLES30.glGetShaderInfoLog(s))
            return s
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vsrc))
            GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fsrc))
            GLES30.glLinkProgram(p)
            prog = p
            texImage = 0
            texDepth = 0
            loaded = false
            val i = lastImage
            val d = lastDepth
            if (i != null && d != null) load(i, d)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            vw = width
            vh = height
            GLES30.glViewport(0, 0, width, height)
        }

        fun load(image: Bitmap, depth: Bitmap) {
            lastImage = image
            lastDepth = depth
            imgAspect = image.width.toFloat() / image.height
            if (texImage == 0) {
                val t = IntArray(2)
                GLES30.glGenTextures(2, t, 0)
                texImage = t[0]
                texDepth = t[1]
            }
            upload(texImage, image)
            upload(texDepth, depth)
            loaded = true
        }

        private fun upload(id: Int, bmp: Bitmap) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            if (!loaded) return

            var ox = dragX + tiltX
            var oy = dragY + tiltY
            if (auto) {
                val t = System.nanoTime() / 1e9
                ox += (0.6 * sin(t * 0.7)).toFloat()
                oy += (0.35 * sin(t * 1.1 + 1.0)).toFloat()
            }
            ox = ox.coerceIn(-1.2f, 1.2f)
            oy = oy.coerceIn(-1.2f, 1.2f)

            GLES30.glUseProgram(prog)
            val va = vw.toFloat() / vh
            val sx = if (imgAspect > va) 1f else imgAspect / va
            val sy = if (imgAspect > va) va / imgAspect else 1f
            GLES30.glUniform2f(GLES30.glGetUniformLocation(prog, "uScale"), sx, sy)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(prog, "uOffset"), ox, oy)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uStrength"), strength)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texImage)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uImage"), 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texDepth)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uDepth"), 1)
            vbuf.position(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, vbuf)
            GLES30.glEnableVertexAttribArray(0)
            vbuf.position(2)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, vbuf)
            GLES30.glEnableVertexAttribArray(1)
            ibuf.position(0)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, idxCount, GLES30.GL_UNSIGNED_SHORT, ibuf)
        }
    }
}
