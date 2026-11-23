package com.vibe.radio.ui

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vibe.radio.audio.AudioFx
import com.vibe.radio.data.AppSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class Dancer(val title: String, val emoji: String) {
    RUNNER("Кот-бегун 3D", "🏃"),
    CAT("Вислоухий кот 3D", "🐱"),
    RAT("Белый крыс 3D", "🐀"),
    DIKAMIK("Дикамик 3D", "👾"),
    ROACH("Таракан 3D", "🪳"),
    GIRL("Девочка 3D", "👧"),
    BARS("3D Эквалайзер", "📊"),
}

/**
 * Полноценная 3D-сцена на OpenGL ES 2.0: все персонажи и визуализатор построены
 * из объёмных 3D-мешей с освещением Blinn-Phong, неоновой подсветкой и Z-буфером.
 * Свайпом пальца можно крутить камеру вокруг персонажа на 360°.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun DancerStage(dancer: Dancer, isPlaying: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val renderer = remember { Dancer3DRenderer() }
    renderer.dancer = dancer
    renderer.isPlaying = isPlaying
    renderer.updatePalette(Neon.Pink, Neon.Violet, Neon.Cyan, Neon.Bg, Neon.Bg2)

    val glView = remember {
        StageGLSurfaceView(context, renderer)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> glView.onResume()
                Lifecycle.Event.ON_PAUSE -> glView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glView.onPause()
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { glView },
            modifier = Modifier.fillMaxSize(),
            update = {
                renderer.dancer = dancer
                renderer.isPlaying = isPlaying
                renderer.updatePalette(Neon.Pink, Neon.Violet, Neon.Cyan, Neon.Bg, Neon.Bg2)
            },
        )
    }
}

@SuppressLint("ViewConstructor")
private class StageGLSurfaceView(
    context: Context,
    private val renderer: Dancer3DRenderer,
) : GLSurfaceView(context) {

    private var prevX = 0f
    private var prevY = 0f
    private var lastTapMs = 0L

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                prevX = event.x
                prevY = event.y
                val now = System.currentTimeMillis()
                if (now - lastTapMs < 280L) {
                    renderer.resetCamera()
                }
                lastTapMs = now
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - prevX
                val dy = event.y - prevY
                prevX = event.x
                prevY = event.y
                renderer.orbitBy(dx * 0.45f, dy * 0.30f)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

// =========================================================================
// Процедурные 3D-меши (вершины: x, y, z, nx, ny, nz)
// =========================================================================
private class Mesh3D(vertices: FloatArray, indices: ShortArray) {
    val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertices); position(0) }
    val indexBuffer: ShortBuffer = ByteBuffer.allocateDirect(indices.size * 2)
        .order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(indices); position(0) }
    val indexCount: Int = indices.size

    companion object {
        fun sphere(stacks: Int = 14, slices: Int = 20): Mesh3D {
            val verts = ArrayList<Float>()
            val idx = ArrayList<Short>()
            for (i in 0..stacks) {
                val phi = PI * i / stacks
                val y = cos(phi).toFloat()
                val r = sin(phi).toFloat()
                for (j in 0..slices) {
                    val theta = 2.0 * PI * j / slices
                    val x = (r * cos(theta)).toFloat()
                    val z = (r * sin(theta)).toFloat()
                    verts.add(x); verts.add(y); verts.add(z)
                    verts.add(x); verts.add(y); verts.add(z)
                }
            }
            val row = slices + 1
            for (i in 0 until stacks) {
                for (j in 0 until slices) {
                    val a = (i * row + j).toShort()
                    val b = ((i + 1) * row + j).toShort()
                    val c = ((i + 1) * row + j + 1).toShort()
                    val d = (i * row + j + 1).toShort()
                    idx.add(a); idx.add(b); idx.add(d)
                    idx.add(b); idx.add(c); idx.add(d)
                }
            }
            return Mesh3D(verts.toFloatArray(), idx.toShortArray())
        }

        /** Цилиндр радиусом 1, высотой 1 (по Y от -0.5 до +0.5), с крышками. */
        fun cylinder(slices: Int = 18): Mesh3D {
            val verts = ArrayList<Float>()
            val idx = ArrayList<Short>()
            // Боковая поверхность
            for (j in 0..slices) {
                val a = 2.0 * PI * j / slices
                val nx = cos(a).toFloat()
                val nz = sin(a).toFloat()
                verts.add(nx); verts.add(0.5f); verts.add(nz); verts.add(nx); verts.add(0f); verts.add(nz)
                verts.add(nx); verts.add(-0.5f); verts.add(nz); verts.add(nx); verts.add(0f); verts.add(nz)
            }
            for (j in 0 until slices) {
                val top0 = (j * 2).toShort()
                val bot0 = (j * 2 + 1).toShort()
                val top1 = (j * 2 + 2).toShort()
                val bot1 = (j * 2 + 3).toShort()
                idx.add(top0); idx.add(bot0); idx.add(top1)
                idx.add(bot0); idx.add(bot1); idx.add(top1)
            }
            // Верхняя крышка
            val topCenter = (verts.size / 6).toShort()
            verts.add(0f); verts.add(0.5f); verts.add(0f); verts.add(0f); verts.add(1f); verts.add(0f)
            val topStart = (verts.size / 6)
            for (j in 0..slices) {
                val a = 2.0 * PI * j / slices
                verts.add(cos(a).toFloat()); verts.add(0.5f); verts.add(sin(a).toFloat())
                verts.add(0f); verts.add(1f); verts.add(0f)
            }
            for (j in 0 until slices) {
                idx.add(topCenter); idx.add((topStart + j + 1).toShort()); idx.add((topStart + j).toShort())
            }
            // Нижняя крышка
            val botCenter = (verts.size / 6).toShort()
            verts.add(0f); verts.add(-0.5f); verts.add(0f); verts.add(0f); verts.add(-1f); verts.add(0f)
            val botStart = (verts.size / 6)
            for (j in 0..slices) {
                val a = 2.0 * PI * j / slices
                verts.add(cos(a).toFloat()); verts.add(-0.5f); verts.add(sin(a).toFloat())
                verts.add(0f); verts.add(-1f); verts.add(0f)
            }
            for (j in 0 until slices) {
                idx.add(botCenter); idx.add((botStart + j).toShort()); idx.add((botStart + j + 1).toShort())
            }
            return Mesh3D(verts.toFloatArray(), idx.toShortArray())
        }

        /** Конус: вершина в (0, +0.5, 0), основание радиуса 1 в y = -0.5. */
        fun cone(slices: Int = 18): Mesh3D {
            val verts = ArrayList<Float>()
            val idx = ArrayList<Short>()
            val ny = 0.7071f
            val nr = 0.7071f
            for (j in 0..slices) {
                val a = 2.0 * PI * j / slices
                val cx = cos(a).toFloat()
                val cz = sin(a).toFloat()
                verts.add(0f); verts.add(0.5f); verts.add(0f); verts.add(cx * nr); verts.add(ny); verts.add(cz * nr)
                verts.add(cx); verts.add(-0.5f); verts.add(cz); verts.add(cx * nr); verts.add(ny); verts.add(cz * nr)
            }
            for (j in 0 until slices) {
                val tip = (j * 2).toShort()
                val b0 = (j * 2 + 1).toShort()
                val b1 = (j * 2 + 3).toShort()
                idx.add(tip); idx.add(b0); idx.add(b1)
            }
            // Дно
            val botCenter = (verts.size / 6).toShort()
            verts.add(0f); verts.add(-0.5f); verts.add(0f); verts.add(0f); verts.add(-1f); verts.add(0f)
            val botStart = (verts.size / 6)
            for (j in 0..slices) {
                val a = 2.0 * PI * j / slices
                verts.add(cos(a).toFloat()); verts.add(-0.5f); verts.add(sin(a).toFloat())
                verts.add(0f); verts.add(-1f); verts.add(0f)
            }
            for (j in 0 until slices) {
                idx.add(botCenter); idx.add((botStart + j).toShort()); idx.add((botStart + j + 1).toShort())
            }
            return Mesh3D(verts.toFloatArray(), idx.toShortArray())
        }

        /** Куб от -0.5 до +0.5 по всем осям с точными нормалями граней. */
        fun box(): Mesh3D {
            val faces = arrayOf(
                floatArrayOf(0f, 0f, 1f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f),
                floatArrayOf(0f, 0f, -1f, 0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f),
                floatArrayOf(1f, 0f, 0f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f),
                floatArrayOf(-1f, 0f, 0f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, -0.5f),
                floatArrayOf(0f, 1f, 0f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f),
                floatArrayOf(0f, -1f, 0f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f),
            )
            val verts = ArrayList<Float>()
            val idx = ArrayList<Short>()
            faces.forEachIndexed { fi, f ->
                val nx = f[0]; val ny = f[1]; val nz = f[2]
                for (k in 0 until 4) {
                    verts.add(f[3 + k * 3]); verts.add(f[4 + k * 3]); verts.add(f[5 + k * 3])
                    verts.add(nx); verts.add(ny); verts.add(nz)
                }
                val base = (fi * 4).toShort()
                idx.add(base); idx.add((base + 1).toShort()); idx.add((base + 2).toShort())
                idx.add(base); idx.add((base + 2).toShort()); idx.add((base + 3).toShort())
            }
            return Mesh3D(verts.toFloatArray(), idx.toShortArray())
        }

        /** Тор в плоскости XZ, большой радиус 1, радиус трубки tubeR. */
        fun torus(majorSegs: Int = 28, minorSegs: Int = 12, tubeR: Float = 0.08f): Mesh3D {
            val verts = ArrayList<Float>()
            val idx = ArrayList<Short>()
            for (i in 0..majorSegs) {
                val u = 2.0 * PI * i / majorSegs
                val cu = cos(u).toFloat()
                val su = sin(u).toFloat()
                for (j in 0..minorSegs) {
                    val v = 2.0 * PI * j / minorSegs
                    val cv = cos(v).toFloat()
                    val sv = sin(v).toFloat()
                    val r = 1f + tubeR * cv
                    verts.add(r * cu); verts.add(tubeR * sv); verts.add(r * su)
                    verts.add(cv * cu); verts.add(sv); verts.add(cv * su)
                }
            }
            val row = minorSegs + 1
            for (i in 0 until majorSegs) {
                for (j in 0 until minorSegs) {
                    val a = (i * row + j).toShort()
                    val b = ((i + 1) * row + j).toShort()
                    val c = ((i + 1) * row + j + 1).toShort()
                    val d = (i * row + j + 1).toShort()
                    idx.add(a); idx.add(b); idx.add(d)
                    idx.add(b); idx.add(c); idx.add(d)
                }
            }
            return Mesh3D(verts.toFloatArray(), idx.toShortArray())
        }
    }
}

// =========================================================================
// OpenGL ES 2.0 3D-рендерер всех 7 персонажей
// =========================================================================
private class Dancer3DRenderer : GLSurfaceView.Renderer {

    @Volatile var dancer: Dancer = Dancer.CAT
    @Volatile var isPlaying: Boolean = false

    // Палитра (обновляется из UI-потока)
    @Volatile private var pink = floatArrayOf(1f, 0.31f, 0.85f)
    @Volatile private var violet = floatArrayOf(0.48f, 0.18f, 0.97f)
    @Volatile private var cyan = floatArrayOf(0.31f, 0.89f, 1f)
    @Volatile private var bg = floatArrayOf(0.043f, 0.043f, 0.102f)

    // Интерактивная орбита камеры
    @Volatile private var userYaw = 0f
    @Volatile private var userPitch = 14f

    fun updatePalette(p: Color, v: Color, c: Color, b: Color, b2: Color) {
        pink = floatArrayOf(p.red, p.green, p.blue)
        violet = floatArrayOf(v.red, v.green, v.blue)
        cyan = floatArrayOf(c.red, c.green, c.blue)
        bg = floatArrayOf(
            (b.red * 0.65f + b2.red * 0.35f),
            (b.green * 0.65f + b2.green * 0.35f),
            (b.blue * 0.65f + b2.blue * 0.35f),
        )
    }

    fun orbitBy(dYaw: Float, dPitch: Float) {
        userYaw = (userYaw + dYaw) % 360f
        userPitch = (userPitch + dPitch).coerceIn(-10f, 48f)
    }

    fun resetCamera() {
        userYaw = 0f
        userPitch = 14f
    }

    // GL-ресурсы
    private var program = 0
    private var aPos = 0
    private var aNormal = 0
    private var uMVP = 0
    private var uModel = 0
    private var uNormalMat = 0
    private var uColor = 0
    private var uEmissive = 0
    private var uShininess = 0
    private var uEyePos = 0
    private var uPinkLight = 0
    private var uCyanLight = 0
    private var uBeat = 0

    private lateinit var sphere: Mesh3D
    private lateinit var cylinder: Mesh3D
    private lateinit var cone: Mesh3D
    private lateinit var box: Mesh3D
    private lateinit var torus: Mesh3D

    // Матричный стек
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val stack = Array(16) { FloatArray(16) }
    private var sp = 0
    private val mvp = FloatArray(16)
    private val inv = FloatArray(16)
    private val norm4 = FloatArray(16)
    private val norm3 = FloatArray(9)
    private val eyePos = FloatArray(3)

    // Анимация
    private var lastNs = 0L
    private var t = 0f
    private var beat = 0f
    private var level = 0f
    private var peak = 0.05f
    private var prevNorm = 0f
    private var jump = 0f
    private var jumpVel = 0f
    private var jumpCd = 0f
    private var runDist = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        program = buildProgram(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        uMVP = GLES20.glGetUniformLocation(program, "uMVP")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uNormalMat = GLES20.glGetUniformLocation(program, "uNormalMat")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uEmissive = GLES20.glGetUniformLocation(program, "uEmissive")
        uShininess = GLES20.glGetUniformLocation(program, "uShininess")
        uEyePos = GLES20.glGetUniformLocation(program, "uEyePos")
        uPinkLight = GLES20.glGetUniformLocation(program, "uPinkLight")
        uCyanLight = GLES20.glGetUniformLocation(program, "uCyanLight")
        uBeat = GLES20.glGetUniformLocation(program, "uBeat")

        sphere = Mesh3D.sphere(14, 20)
        cylinder = Mesh3D.cylinder(16)
        cone = Mesh3D.cone(16)
        box = Mesh3D.box()
        torus = Mesh3D.torus(28, 12, 0.075f)
        lastNs = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / max(height, 1).toFloat()
        Matrix.perspectiveM(proj, 0, 42f, aspect, 0.1f, 60f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        var dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastNs = now
        if (!AppSettings.animations) dt = 0f

        val playing = isPlaying
        val rawBass = if (playing) AudioFx.bass else 0f
        peak = maxOf(peak * 0.996f, rawBass, 0.04f)
        val norm = (rawBass / peak).coerceIn(0f, 1f)
        beat += (norm - beat) * (if (norm > beat) 0.55f else 0.12f)
        val lv = if (playing) (AudioFx.level * 3.5f).coerceIn(0f, 1f) else 0f
        level += (lv - level) * 0.22f
        t += dt * (if (playing) 1f + beat * 0.85f else 0.35f)

        // Физика прыжка кота-бегуна на сильную долю
        jumpCd -= dt
        if (playing && dt > 0f && norm > 0.76f && prevNorm <= 0.76f && jumpCd <= 0f && jump <= 0.001f) {
            jumpVel = 3.2f + 1.4f * norm
            jumpCd = 0.34f
        }
        prevNorm = norm
        if (jump > 0f || jumpVel > 0f) {
            jumpVel -= 10.5f * dt
            jump = (jump + jumpVel * dt).coerceAtLeast(0f)
            if (jump == 0f) jumpVel = 0f
        }
        runDist += dt * (if (playing) 1.3f + level * 1.9f + beat * 0.7f else 0.28f)

        val bgCol = bg
        GLES20.glClearColor(bgCol[0], bgCol[1], bgCol[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        GLES20.glUseProgram(program)

        // Камера
        val mode = dancer
        val autoYaw = when (mode) {
            Dancer.RUNNER -> 26f + sin(t * 0.45f) * 8f
            Dancer.BARS -> t * 18f
            else -> sin(t * 0.55f) * 22f
        }
        val yawRad = Math.toRadians((autoYaw + userYaw).toDouble())
        val pitchRad = Math.toRadians(userPitch.toDouble())
        val camDist = when (mode) {
            Dancer.RUNNER -> 6.8f
            Dancer.BARS -> 6.2f
            else -> 5.4f
        }
        val lookY = if (mode == Dancer.RUNNER) 0.55f else 0.25f
        val cx = (camDist * cos(pitchRad) * sin(yawRad)).toFloat()
        val cy = (lookY + camDist * sin(pitchRad)).toFloat()
        val cz = (camDist * cos(pitchRad) * cos(yawRad)).toFloat()
        eyePos[0] = cx; eyePos[1] = cy; eyePos[2] = cz
        Matrix.setLookAtM(view, 0, cx, cy, cz, 0f, lookY, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        GLES20.glUniform3fv(uEyePos, 1, eyePos, 0)
        GLES20.glUniform3fv(uPinkLight, 1, pink, 0)
        GLES20.glUniform3fv(uCyanLight, 1, cyan, 0)
        GLES20.glUniform1f(uBeat, beat)

        sp = 0
        Matrix.setIdentityM(stack[0], 0)

        when (mode) {
            Dancer.RUNNER -> drawRunner3D()
            Dancer.BARS -> drawSpectrumArena3D()
            else -> {
                drawDiscoStage3D()
                when (mode) {
                    Dancer.CAT -> drawCat3D()
                    Dancer.RAT -> drawRat3D()
                    Dancer.DIKAMIK -> drawDikamik3D()
                    Dancer.ROACH -> drawRoach3D()
                    Dancer.GIRL -> drawGirl3D()
                    else -> Unit
                }
            }
        }
    }

    // ---------------- Матричные хелперы ----------------
    private inline fun push(block: () -> Unit) {
        if (sp + 1 < stack.size) {
            System.arraycopy(stack[sp], 0, stack[sp + 1], 0, 16)
            sp++
            block()
            sp--
        }
    }

    private fun tr(x: Float, y: Float, z: Float) = Matrix.translateM(stack[sp], 0, x, y, z)
    private fun rot(deg: Float, x: Float, y: Float, z: Float) = Matrix.rotateM(stack[sp], 0, deg, x, y, z)
    private fun sc(x: Float, y: Float, z: Float) =
        Matrix.scaleM(stack[sp], 0, max(x, 0.002f), max(y, 0.002f), max(z, 0.002f))
    private fun sc(s: Float) = sc(s, s, s)

    private fun draw(
        mesh: Mesh3D,
        r: Float, g: Float, b: Float,
        a: Float = 1f,
        emissive: Float = 0f,
        shininess: Float = 36f,
    ) {
        val model = stack[sp]
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)
        if (Matrix.invertM(inv, 0, model, 0)) {
            Matrix.transposeM(norm4, 0, inv, 0)
            norm3[0] = norm4[0]; norm3[1] = norm4[1]; norm3[2] = norm4[2]
            norm3[3] = norm4[4]; norm3[4] = norm4[5]; norm3[5] = norm4[6]
            norm3[6] = norm4[8]; norm3[7] = norm4[9]; norm3[8] = norm4[10]
        } else {
            norm3[0] = 1f; norm3[1] = 0f; norm3[2] = 0f
            norm3[3] = 0f; norm3[4] = 1f; norm3[5] = 0f
            norm3[6] = 0f; norm3[7] = 0f; norm3[8] = 1f
        }

        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniformMatrix3fv(uNormalMat, 1, false, norm3, 0)
        GLES20.glUniform4f(uColor, r, g, b, a)
        GLES20.glUniform1f(uEmissive, emissive)
        GLES20.glUniform1f(uShininess, shininess)

        val vb = mesh.vertexBuffer
        vb.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, vb)
        vb.position(3)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 24, vb)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, mesh.indexBuffer)
    }

    private fun draw(mesh: Mesh3D, rgb: FloatArray, a: Float = 1f, emissive: Float = 0f, shininess: Float = 36f) =
        draw(mesh, rgb[0], rgb[1], rgb[2], a, emissive, shininess)

    /** 3D-конечность (цилиндр + сферические суставы) от точки A до B. */
    private fun drawLimb(
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        radius: Float,
        r: Float, g: Float, b: Float,
        capStart: Boolean = true,
        capEnd: Boolean = true,
        emissive: Float = 0f,
    ) {
        val dx = x2 - x1
        val dy = y2 - y1
        val dz = z2 - z1
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 0.001f) return
        push {
            tr((x1 + x2) * 0.5f, (y1 + y2) * 0.5f, (z1 + z2) * 0.5f)
            val ny = (dy / len).coerceIn(-1f, 1f)
            if (ny < -0.9995f) {
                rot(180f, 1f, 0f, 0f)
            } else if (ny < 0.9995f) {
                val ax = dz / len
                val az = -dx / len
                val angle = Math.toDegrees(acos(ny.toDouble())).toFloat()
                rot(angle, ax, 0f, az)
            }
            sc(radius, len, radius)
            draw(cylinder, r, g, b, emissive = emissive)
        }
        if (capStart) push { tr(x1, y1, z1); sc(radius); draw(sphere, r, g, b, emissive = emissive) }
        if (capEnd) push { tr(x2, y2, z2); sc(radius); draw(sphere, r, g, b, emissive = emissive) }
    }

    /** Объёмный 3D-глаз с морганием, радужкой, зрачком и бликом. */
    private fun drawEye3D(x: Float, y: Float, z: Float, rad: Float, ir: Float, ig: Float, ib: Float, lookX: Float) {
        val blink = (t % 3.8f) < 0.12f
        push {
            tr(x, y, z)
            if (blink) {
                sc(rad * 1.15f, rad * 0.22f, rad * 0.45f)
                draw(box, 0.14f, 0.14f, 0.16f)
            } else {
                // Белок
                push { sc(rad, rad, rad * 0.72f); draw(sphere, 0.98f, 0.98f, 1f, shininess = 80f) }
                // Радужка
                push {
                    tr(lookX * rad * 0.18f, 0f, rad * 0.45f)
                    sc(rad * 0.68f, rad * 0.68f, rad * 0.45f)
                    draw(sphere, ir, ig, ib, emissive = 0.15f, shininess = 90f)
                }
                // Зрачок
                push {
                    tr(lookX * rad * 0.22f, 0f, rad * 0.68f)
                    sc(rad * 0.36f, rad * 0.36f, rad * 0.28f)
                    draw(sphere, 0.05f, 0.05f, 0.07f, shininess = 100f)
                }
                // Блик
                push {
                    tr(-rad * 0.22f, rad * 0.24f, rad * 0.78f)
                    sc(rad * 0.17f)
                    draw(sphere, 1f, 1f, 1f, emissive = 0.9f)
                }
            }
        }
    }

    /** 3D-наушники (ободок + светящиеся неоновые чашки). */
    private fun drawHeadphones3D(headR: Float, offsetY: Float = 0f) {
        // Дуга
        val steps = 10
        for (i in 0 until steps) {
            val a0 = -PI * 0.08 + PI * 1.16 * i / steps
            val a1 = -PI * 0.08 + PI * 1.16 * (i + 1) / steps
            val rBand = headR * 1.12f
            drawLimb(
                (cos(a0) * rBand).toFloat(), offsetY + (sin(a0) * rBand).toFloat(), 0f,
                (cos(a1) * rBand).toFloat(), offsetY + (sin(a1) * rBand).toFloat(), 0f,
                headR * 0.09f, pink[0], pink[1], pink[2], capStart = i == 0, capEnd = true, emissive = 0.25f,
            )
        }
        // Чашки слева и справа
        for (s in floatArrayOf(-1f, 1f)) {
            push {
                tr(s * headR * 1.06f, offsetY - headR * 0.05f, 0f)
                rot(90f, 0f, 0f, 1f)
                push { sc(headR * 0.38f, headR * 0.22f, headR * 0.38f); draw(cylinder, 0.16f, 0.16f, 0.22f) }
                push {
                    tr(0f, -s * headR * 0.10f, 0f)
                    sc(headR * (0.34f + 0.05f * beat))
                    draw(torus, pink, emissive = 0.65f + 0.3f * beat)
                }
                push {
                    tr(0f, -s * headR * 0.12f, 0f)
                    sc(headR * 0.20f, headR * 0.08f, headR * 0.20f)
                    draw(cylinder, cyan, emissive = 0.6f + 0.4f * beat)
                }
            }
        }
    }

    // =====================================================================
    // Общая 3D-диско-сцена (подиум, неоновые кольца, 3D-спектр, частицы)
    // =====================================================================
    private fun drawDiscoStage3D() {
        val floorY = -1.25f
        // Главный круглый подиум
        push {
            tr(0f, floorY - 0.12f, 0f)
            sc(1.65f, 0.18f, 1.65f)
            draw(cylinder, 0.11f, 0.10f, 0.20f, shininess = 80f)
        }
        // Внешнее неоновое кольцо (пульсирует под бас)
        push {
            tr(0f, floorY - 0.02f, 0f)
            rot(t * 25f, 0f, 1f, 0f)
            sc(1.68f + 0.08f * beat, 0.8f, 1.68f + 0.08f * beat)
            draw(torus, pink, emissive = 0.65f + 0.35f * beat)
        }
        // Второе вращающееся кольцо циан
        push {
            tr(0f, floorY - 0.12f, 0f)
            rot(-t * 18f, 0f, 1f, 0f)
            sc(2.15f + 0.12f * level, 0.6f, 2.15f + 0.12f * level)
            draw(torus, cyan, emissive = 0.45f + 0.35f * beat)
        }
        // Полукруг 3D-столбиков живого спектра позади персонажа
        val spec = AudioFx.spectrum32
        val pillars = 16
        for (i in 0 until pillars) {
            val ang = Math.toRadians((-155.0 + i * 130.0 / (pillars - 1)))
            val rad = 2.45f
            val px = (sin(ang) * rad).toFloat()
            val pz = (cos(ang) * rad).toFloat()
            val v = if (isPlaying) spec[(i * 2).coerceIn(0, 31)] else (0.08f + 0.06f * sin(t * 3f + i))
            val h = 0.20f + v * 1.9f + beat * 0.25f
            val col = if (i % 2 == 0) cyan else pink
            push {
                tr(px, floorY + h * 0.5f - 0.02f, pz)
                sc(0.14f, h, 0.14f)
                draw(box, col, emissive = 0.45f + 0.45f * v)
            }
        }
        // Парящие 3D-частицы
        for (i in 0 until 14) {
            val a = i * 2.39996f + t * 0.55f
            val rr = 1.6f + (i % 3) * 0.45f
            val py = floorY + ((t * (0.45f + (i % 4) * 0.12f) + i * 0.57f) % 3.2f)
            val col = when (i % 3) {
                0 -> cyan
                1 -> pink
                else -> violet
            }
            push {
                tr(cos(a) * rr, py, sin(a) * rr)
                sc(0.055f + 0.03f * beat)
                draw(sphere, col, emissive = 0.85f)
            }
        }
    }

    private fun poseSwing() = sin(t * 6.0f)
    private fun poseBounce() = abs(sin(t * 6.0f))

    // =====================================================================
    // 1. ВИСЛОУХИЙ КОТ 3D
    // =====================================================================
    private fun drawCat3D() {
        val swing = poseSwing()
        val bounce = poseBounce()
        val lift = bounce * (0.12f + 0.26f * beat)
        val tilt = swing * (7f + 8f * beat)
        val squash = 1f + 0.05f * beat * (1f - bounce)

        val furR = 0.60f; val furG = 0.65f; val furB = 0.73f
        val darkR = 0.45f; val darkG = 0.50f; val darkB = 0.58f
        val bellyR = 0.93f; val bellyG = 0.91f; val bellyB = 0.88f

        push {
            tr(0f, -0.35f + lift, 0f)
            rot(tilt * 0.5f, 0f, 0f, 1f)
            rot(swing * 12f, 0f, 1f, 0f)
            sc(1f / squash, squash, 1f / squash)

            // Тело + пузико
            push { sc(0.68f, 0.78f, 0.58f); draw(sphere, furR, furG, furB) }
            push { tr(0f, -0.06f, 0.28f); sc(0.42f, 0.55f, 0.36f); draw(sphere, bellyR, bellyG, bellyB) }

            // Ноги
            for (s in floatArrayOf(-1f, 1f)) {
                val stepY = if (s < 0) -0.08f * swing else 0.08f * swing
                drawLimb(s * 0.28f, -0.58f, 0.02f, s * 0.30f, -0.82f + stepY, 0.14f, 0.18f, darkR, darkG, darkB)
                push {
                    tr(s * 0.30f, -0.86f + stepY, 0.22f)
                    sc(0.20f, 0.12f, 0.26f)
                    draw(sphere, darkR, darkG, darkB)
                }
            }

            // Танцующие 3D-лапки (руки)
            val armL = 0.35f + 0.45f * swing
            val armR = 0.35f - 0.45f * swing
            drawLimb(-0.55f, 0.25f, 0.06f, -0.95f, 0.25f + armL * 0.5f, 0.22f, 0.15f, furR, furG, furB)
            drawLimb(-0.95f, 0.25f + armL * 0.5f, 0.22f, -1.12f, 0.62f + armL * 0.35f, 0.34f, 0.13f, furR, furG, furB)
            drawLimb(0.55f, 0.25f, 0.06f, 0.95f, 0.25f + armR * 0.5f, 0.22f, 0.15f, furR, furG, furB)
            drawLimb(0.95f, 0.25f + armR * 0.5f, 0.22f, 1.12f, 0.62f + armR * 0.35f, 0.34f, 0.13f, furR, furG, furB)

            // Изогнутый пушистый 3D-хвост
            var tx = 0f; var ty = -0.45f; var tz = -0.48f
            for (k in 1..6) {
                val u = k / 6f
                val nx = sin(t * 7f + u * 2.4f) * 0.35f * u
                val ny = -0.45f + u * 0.95f
                val nz = -0.48f - u * 0.55f - sin(u * PI).toFloat() * 0.22f
                drawLimb(tx, ty, tz, nx, ny, nz, 0.14f - u * 0.03f, darkR, darkG, darkB, capStart = k == 1)
                tx = nx; ty = ny; tz = nz
            }

            // Голова
            push {
                tr(0f, 0.98f, 0.05f)
                rot(tilt * 0.85f, 0f, 0f, 1f)
                rot(-swing * 10f, 0f, 1f, 0f)
                push { sc(0.76f, 0.66f, 0.68f); draw(sphere, furR, furG, furB) }

                // Сложенные шотландские висячие 3D-ушки
                for (s in floatArrayOf(-1f, 1f)) {
                    push {
                        tr(s * 0.44f, 0.48f, 0.12f)
                        rot(s * -48f, 0f, 0f, 1f)
                        rot(32f, 1f, 0f, 0f)
                        sc(0.24f, 0.20f, 0.22f)
                        draw(cone, darkR, darkG, darkB)
                    }
                }
                // Полоски на лбу
                for (i in -1..1) {
                    push {
                        tr(i * 0.12f, 0.42f, 0.56f)
                        rot(-25f, 1f, 0f, 0f)
                        sc(0.045f, 0.18f, 0.045f)
                        draw(box, darkR, darkG, darkB)
                    }
                }
                // 3D-глаза (янтарные)
                drawEye3D(-0.27f, 0.08f, 0.58f, 0.17f, 0.95f, 0.68f, 0.20f, swing)
                drawEye3D(0.27f, 0.08f, 0.58f, 0.17f, 0.95f, 0.68f, 0.20f, swing)

                // Мордочка + розовый нос + щёчки
                push { tr(-0.12f, -0.14f, 0.60f); sc(0.16f, 0.13f, 0.14f); draw(sphere, bellyR, bellyG, bellyB) }
                push { tr(0.12f, -0.14f, 0.60f); sc(0.16f, 0.13f, 0.14f); draw(sphere, bellyR, bellyG, bellyB) }
                push { tr(0f, -0.05f, 0.69f); sc(0.09f, 0.07f, 0.08f); draw(sphere, 1f, 0.50f, 0.63f) }
                for (s in floatArrayOf(-1f, 1f)) {
                    push {
                        tr(s * 0.44f, -0.10f, 0.48f)
                        sc(0.12f + 0.03f * beat, 0.09f, 0.08f)
                        draw(sphere, 1f, 0.55f, 0.68f, emissive = 0.2f * beat)
                    }
                    // Усы
                    for (k in -1..1) {
                        drawLimb(
                            s * 0.24f, -0.12f, 0.62f,
                            s * 0.78f, -0.12f + k * 0.10f, 0.52f,
                            0.015f, 0.95f, 0.95f, 0.98f, capStart = false, capEnd = false,
                        )
                    }
                }
                // 3D-наушники
                drawHeadphones3D(0.72f, 0.04f)
            }
        }
    }

    // =====================================================================
    // 2. БЕЛЫЙ КРЫС 3D
    // =====================================================================
    private fun drawRat3D() {
        val swing = poseSwing()
        val bounce = poseBounce()
        val lift = bounce * (0.14f + 0.28f * beat)
        val tilt = swing * (9f + 9f * beat)
        val squash = 1f + 0.06f * beat * (1f - bounce)

        val wR = 0.96f; val wG = 0.96f; val wB = 0.98f
        val pR = 1.0f; val pG = 0.70f; val pB = 0.78f

        push {
            tr(0f, -0.38f + lift, 0f)
            rot(tilt * 0.6f, 0f, 0f, 1f)
            rot(swing * 16f, 0f, 1f, 0f)
            sc(1f / squash, squash, 1f / squash)

            // Тело-груша
            push { sc(0.62f, 0.74f, 0.56f); draw(sphere, wR, wG, wB) }

            // Розовые лапки-ступни
            for (s in floatArrayOf(-1f, 1f)) {
                val dy = if (s < 0) -0.09f * swing else 0.09f * swing
                push {
                    tr(s * 0.28f, -0.78f + dy, 0.20f)
                    sc(0.18f, 0.10f, 0.30f)
                    draw(sphere, pR, pG, pB)
                }
            }

            // Танцующие белые ручки с розовыми кистями
            for (s in floatArrayOf(-1f, 1f)) {
                val wave = if (s < 0) swing else -swing
                val ex = s * 0.85f; val ey = 0.22f + wave * 0.28f; val ez = 0.22f
                val hx = s * 1.02f; val hy = 0.55f + wave * 0.25f; val hz = 0.35f
                drawLimb(s * 0.45f, 0.22f, 0.10f, ex, ey, ez, 0.10f, wR, wG, wB)
                drawLimb(ex, ey, ez, hx, hy, hz, 0.085f, wR, wG, wB, capEnd = false)
                push { tr(hx, hy, hz); sc(0.11f); draw(sphere, pR, pG, pB) }
            }

            // Длинный волнистый розовый 3D-хвост
            var tx = 0f; var ty = -0.45f; var tz = -0.46f
            for (k in 1..10) {
                val u = k / 10f
                val nx = sin(t * 7.5f + u * 4.5f) * 0.55f * u
                val ny = -0.45f + u * 0.72f + cos(t * 6f + u * 3f) * 0.14f
                val nz = -0.46f - u * 0.95f
                drawLimb(tx, ty, tz, nx, ny, nz, 0.085f * (1f - 0.55f * u), pR, pG, pB, capStart = k == 1)
                tx = nx; ty = ny; tz = nz
            }

            // Голова с вытянутой 3D-мордочкой и большими ушами
            push {
                tr(0f, 0.88f, 0.08f)
                rot(tilt * 0.8f, 0f, 0f, 1f)
                push { sc(0.56f, 0.52f, 0.54f); draw(sphere, wR, wG, wB) }
                // Вытянутый конусный нос
                push {
                    tr(0f, -0.06f, 0.52f)
                    rot(92f, 1f, 0f, 0f)
                    sc(0.32f, 0.55f, 0.30f)
                    draw(cone, wR, wG, wB)
                }
                // Розовый кончик носа
                push { tr(0f, -0.08f, 0.82f); sc(0.11f); draw(sphere, 1f, 0.42f, 0.56f, emissive = 0.15f) }
                // Зубки
                push { tr(-0.05f, -0.24f, 0.56f); sc(0.06f, 0.12f, 0.04f); draw(box, 1f, 1f, 1f) }
                push { tr(0.05f, -0.24f, 0.56f); sc(0.06f, 0.12f, 0.04f); draw(box, 1f, 1f, 1f) }
                // Большие круглые 3D-уши
                for (s in floatArrayOf(-1f, 1f)) {
                    push {
                        tr(s * 0.50f, 0.44f, -0.04f)
                        rot(s * -24f, 0f, 0f, 1f)
                        rot(s * 18f, 0f, 1f, 0f)
                        push { sc(0.36f, 0.36f, 0.10f); draw(sphere, wR, wG, wB) }
                        push { tr(0f, 0f, 0.05f); sc(0.24f, 0.24f, 0.08f); draw(sphere, pR, pG, pB) }
                    }
                    // Усики
                    for (k in -1..1) {
                        drawLimb(
                            s * 0.12f, -0.08f, 0.68f,
                            s * 0.65f, -0.06f + k * 0.09f, 0.62f,
                            0.012f, 0.80f, 0.80f, 0.85f, capStart = false, capEnd = false,
                        )
                    }
                }
                // Рубиновые 3D-глаза
                drawEye3D(-0.22f, 0.12f, 0.46f, 0.13f, 0.90f, 0.14f, 0.36f, swing)
                drawEye3D(0.22f, 0.12f, 0.46f, 0.13f, 0.90f, 0.14f, 0.36f, swing)
            }
        }
    }

    // =====================================================================
    // 3. ДИКАМИК 3D (пушистый фиолетовый персонаж с антенной и гребешком)
    // =====================================================================
    private fun drawDikamik3D() {
        val swing = poseSwing()
        val bounce = poseBounce()
        val lift = bounce * (0.20f + 0.34f * beat)
        val tilt = swing * (10f + 10f * beat)
        val squash = 1f + 0.07f * beat * (1f - bounce)

        val bR = 0.56f; val bG = 0.36f; val bB = 1.0f
        val dR = 0.35f; val dG = 0.18f; val dB = 0.73f

        push {
            tr(0f, -0.15f + lift, 0f)
            rot(tilt * 0.7f, 0f, 0f, 1f)
            rot(swing * 18f, 0f, 1f, 0f)
            sc(1f / squash, squash * (1f + 0.04f * beat), 1f / squash)

            // Основное пушистое 3D-тело
            push { sc(0.84f, 0.88f, 0.78f); draw(sphere, bR, bG, bB) }

            // Объёмная 3D-пушистость по всему периметру
            for (i in 0 until 16) {
                val a = (i * 2.0 * PI / 16.0).toFloat()
                val ringY = sin(i * 1.9f) * 0.48f
                val rad = 0.78f * sqrt((1f - ringY * ringY).coerceAtLeast(0.25f)) + 0.04f * sin(t * 9f + i)
                push {
                    tr(cos(a) * rad, ringY, sin(a) * rad)
                    sc(0.24f)
                    draw(sphere, bR, bG, bB)
                }
            }

            // Ботиночки
            for (s in floatArrayOf(-1f, 1f)) {
                val dy = if (s < 0) -0.10f * swing else 0.10f * swing
                push { tr(s * 0.34f, -0.94f + dy, 0.14f); sc(0.24f, 0.15f, 0.32f); draw(sphere, dR, dG, dB) }
            }

            // Машущие ручки
            for (s in floatArrayOf(-1f, 1f)) {
                val wave = if (s < 0) swing else -swing
                drawLimb(
                    s * 0.78f, 0.05f, 0.08f,
                    s * 1.25f, 0.25f + wave * 0.45f, 0.25f,
                    0.14f, bR, bG, bB,
                )
            }

            // Неоново-розовый гребешок из 5 3D-конусов
            for (i in -2..2) {
                push {
                    tr(i * 0.22f, 0.88f - abs(i) * 0.07f, -0.05f)
                    rot(i * -16f, 0f, 0f, 1f)
                    sc(0.11f, 0.34f + 0.12f * beat, 0.11f)
                    draw(cone, pink, emissive = 0.35f + 0.35f * beat)
                }
            }

            // Пружинящая 3D-антенна со светящимся циановым шаром
            val tipX = sin(t * 5.5f) * 0.34f
            val tipZ = cos(t * 4.2f) * 0.18f
            val tipY = 1.56f + 0.12f * beat
            drawLimb(0f, 0.82f, 0f, tipX * 0.5f, 1.22f, tipZ * 0.5f, 0.05f, dR, dG, dB)
            drawLimb(tipX * 0.5f, 1.22f, tipZ * 0.5f, tipX, tipY, tipZ, 0.045f, dR, dG, dB, capEnd = false)
            push {
                tr(tipX, tipY, tipZ)
                sc(0.18f + 0.08f * beat)
                draw(sphere, cyan, emissive = 0.9f)
            }

            // Большие изумрудные 3D-глаза
            drawEye3D(-0.30f, 0.22f, 0.68f, 0.24f, 0.22f, 0.88f, 0.54f, swing)
            drawEye3D(0.30f, 0.22f, 0.68f, 0.24f, 0.22f, 0.88f, 0.54f, swing)

            // Рот, открывающийся в такт басу + 2 зубика
            val mouthH = 0.12f + 0.24f * beat
            push { tr(0f, -0.20f, 0.72f); sc(0.28f, mouthH, 0.14f); draw(sphere, 0.16f, 0.04f, 0.30f) }
            push { tr(-0.10f, -0.14f + mouthH * 0.35f, 0.80f); sc(0.08f, 0.09f, 0.05f); draw(box, 1f, 1f, 1f) }
            push { tr(0.10f, -0.14f + mouthH * 0.35f, 0.80f); sc(0.08f, 0.09f, 0.05f); draw(box, 1f, 1f, 1f) }
        }
    }

    // =====================================================================
    // 4. ТАРАКАН 3D (глянцевый панцирь, раскрывающиеся крылья, 6 лап, усы)
    // =====================================================================
    private fun drawRoach3D() {
        val swing = poseSwing()
        val bounce = poseBounce()
        val lift = bounce * (0.14f + 0.24f * beat)
        val tilt = swing * (10f + 9f * beat)

        val brR = 0.44f; val brG = 0.23f; val brB = 0.11f
        val ltR = 0.62f; val ltG = 0.36f; val ltB = 0.17f
        val lgR = 0.28f; val lgG = 0.13f; val lgB = 0.06f

        push {
            tr(0f, -0.30f + lift, 0f)
            rot(tilt * 0.7f, 0f, 0f, 1f)
            rot(swing * 15f, 0f, 1f, 0f)

            // Сегментированное глянцевое брюшко
            push { sc(0.50f, 0.76f, 0.42f); draw(sphere, brR, brG, brB, shininess = 96f) }
            for (i in -2..2) {
                push {
                    tr(0f, i * 0.20f, 0f)
                    val rr = 0.48f - abs(i) * 0.05f
                    sc(rr, 0.06f, rr * 0.86f)
                    draw(torus, lgR, lgG, lgB)
                }
            }

            // Раскрывающиеся в такт басу 3D-надкрылья
            val wingOpen = 12f + 26f * beat
            for (s in floatArrayOf(-1f, 1f)) {
                push {
                    tr(s * 0.08f, 0.45f, -0.18f)
                    rot(s * -wingOpen, 0f, 0f, 1f)
                    rot(-18f - 14f * beat, 1f, 0f, 0f)
                    tr(s * 0.22f, -0.45f, -0.08f)
                    sc(0.28f, 0.68f, 0.12f)
                    draw(sphere, ltR, ltG, ltB, shininess = 110f)
                }
            }

            // 6 сочленённых 3D-лапок (2 опорные + 4 танцующие)
            for (s in floatArrayOf(-1f, 1f)) {
                val dy = if (s < 0) -0.08f * swing else 0.08f * swing
                // Нижние опорные
                drawLimb(s * 0.26f, -0.52f, 0.06f, s * 0.48f, -0.78f, 0.16f, 0.055f, lgR, lgG, lgB)
                drawLimb(s * 0.48f, -0.78f, 0.16f, s * 0.34f, -0.96f + dy, 0.24f, 0.045f, lgR, lgG, lgB)
                // Средние машут
                val mWave = if (s < 0) swing else -swing
                drawLimb(s * 0.44f, 0.05f, 0.10f, s * 0.96f, 0.15f + mWave * 0.32f, 0.26f, 0.05f, lgR, lgG, lgB)
                drawLimb(s * 0.96f, 0.15f + mWave * 0.32f, 0.26f, s * 1.18f, 0.45f + mWave * 0.28f, 0.36f, 0.04f, lgR, lgG, lgB)
                // Верхние машут в противофазе
                val uWave = -mWave
                drawLimb(s * 0.36f, 0.48f, 0.12f, s * 0.88f, 0.68f + uWave * 0.30f, 0.28f, 0.05f, lgR, lgG, lgB)
                drawLimb(s * 0.88f, 0.68f + uWave * 0.30f, 0.28f, s * 1.10f, 0.98f + uWave * 0.25f, 0.38f, 0.04f, lgR, lgG, lgB)
            }

            // Грудь + голова
            push { tr(0f, 0.68f, 0.05f); sc(0.42f, 0.26f, 0.36f); draw(sphere, brR, brG, brB, shininess = 90f) }
            push {
                tr(0f, 1.02f, 0.12f)
                rot(tilt * 0.9f, 0f, 0f, 1f)
                push { sc(0.36f, 0.32f, 0.34f); draw(sphere, ltR, ltG, ltB, shininess = 90f) }
                drawEye3D(-0.14f, 0.05f, 0.28f, 0.10f, 0.35f, 0.20f, 0.08f, swing)
                drawEye3D(0.14f, 0.05f, 0.28f, 0.10f, 0.35f, 0.20f, 0.08f, swing)
                // Длинные изогнутые 3D-усики
                for (s in floatArrayOf(-1f, 1f)) {
                    var ax = s * 0.10f; var ay = 0.26f; var az = 0.12f
                    for (k in 1..6) {
                        val u = k / 6f
                        val nx = s * (0.10f + u * 0.95f + sin(t * 6f + u * 2f) * 0.15f)
                        val ny = 0.26f + u * 0.72f + cos(t * 5f + s) * 0.12f * u
                        val nz = 0.12f + u * 0.35f
                        drawLimb(ax, ay, az, nx, ny, nz, 0.028f * (1f - 0.5f * u), lgR, lgG, lgB, capStart = k == 1)
                        ax = nx; ay = ny; az = nz
                    }
                }
            }
        }
    }

    // =====================================================================
    // 5. ДЕВОЧКА 3D (русые волосы, развивающиеся пряди, неоновое платье)
    // =====================================================================
    private fun drawGirl3D() {
        val swing = poseSwing()
        val bounce = poseBounce()
        val lift = bounce * (0.10f + 0.20f * beat)
        val tilt = swing * (6f + 7f * beat)

        val skR = 1.0f; val skG = 0.86f; val skB = 0.77f
        val hrR = 0.79f; val hrG = 0.63f; val hrB = 0.49f
        val hdR = 0.65f; val hdG = 0.49f; val hdB = 0.35f
        val shR = 0.24f; val shG = 0.16f; val shB = 0.45f

        push {
            tr(0f, -0.25f + lift, 0f)
            rot(tilt * 0.45f, 0f, 0f, 1f)
            rot(swing * 14f, 0f, 1f, 0f)

            // Ноги и туфельки
            for (s in floatArrayOf(-1f, 1f)) {
                val dy = if (s < 0) -0.07f * swing else 0.07f * swing
                drawLimb(s * 0.18f, -0.40f, 0f, s * 0.20f, -0.92f + dy, 0.04f, 0.09f, skR, skG, skB)
                push { tr(s * 0.20f, -0.96f + dy, 0.12f); sc(0.13f, 0.08f, 0.20f); draw(sphere, shR, shG, shB) }
            }

            // Пышное 3D-платье (конус с верхним лифом)
            push {
                tr(0f, -0.05f, 0f)
                rot(swing * 8f, 0f, 0f, 1f)
                push { sc(0.58f + 0.05f * beat, 0.96f, 0.54f + 0.05f * beat); draw(cone, pink, emissive = 0.12f) }
                push { tr(0f, -0.45f, 0f); sc(0.57f, 0.5f, 0.53f); draw(torus, violet, emissive = 0.25f) }
            }

            // Танцующие ручки
            for (s in floatArrayOf(-1f, 1f)) {
                val wave = if (s < 0) swing else -swing
                val ex = s * 0.56f; val ey = 0.32f + wave * 0.25f; val ez = 0.14f
                val hx = s * 0.82f; val hy = 0.65f + wave * 0.30f; val hz = 0.26f
                drawLimb(s * 0.24f, 0.32f, 0.04f, ex, ey, ez, 0.075f, skR, skG, skB)
                drawLimb(ex, ey, ez, hx, hy, hz, 0.068f, skR, skG, skB)
            }

            // Шея и голова
            drawLimb(0f, 0.42f, 0f, 0f, 0.64f, 0f, 0.08f, skR, skG, skB, capStart = false, capEnd = false)
            push {
                tr(0f, 0.92f, 0.02f)
                rot(tilt * 0.75f, 0f, 0f, 1f)
                push { sc(0.45f, 0.48f, 0.44f); draw(sphere, skR, skG, skB) }

                // Объёмная причёска (верхняя шапочка + чёлка)
                push { tr(0f, 0.14f, -0.06f); sc(0.49f, 0.42f, 0.48f); draw(sphere, hrR, hrG, hrB) }
                push { tr(-0.18f, 0.26f, 0.30f); rot(18f, 0f, 0f, 1f); sc(0.24f, 0.16f, 0.18f); draw(sphere, hrR, hrG, hrB) }
                push { tr(0.18f, 0.26f, 0.30f); rot(-18f, 0f, 0f, 1f); sc(0.24f, 0.16f, 0.18f); draw(sphere, hrR, hrG, hrB) }

                // Светящаяся циановая заколка
                push { tr(0.32f, 0.28f, 0.34f); sc(0.085f); draw(sphere, cyan, emissive = 0.8f) }

                // Развевающиеся 3D-пряди длинных волос сзади
                val hairSway = sin(t * 6f - 0.7f) * (0.14f + 0.18f * beat)
                for (i in -2..2) {
                    val bx = i * 0.17f
                    val bz = -0.34f - (2 - abs(i)) * 0.05f
                    drawLimb(
                        bx, 0.12f, bz,
                        bx * 1.15f + hairSway, -0.55f, bz - 0.14f,
                        0.12f, hdR, hdG, hdB,
                    )
                    drawLimb(
                        bx * 1.15f + hairSway, -0.55f, bz - 0.14f,
                        bx * 1.25f + hairSway * 1.6f, -1.05f, bz - 0.20f,
                        0.10f, hrR, hrG, hrB,
                    )
                }

                // Зелёные 3D-глаза и щёчки
                drawEye3D(-0.17f, 0.02f, 0.38f, 0.11f, 0.38f, 0.62f, 0.34f, swing)
                drawEye3D(0.17f, 0.02f, 0.38f, 0.11f, 0.38f, 0.62f, 0.34f, swing)
                for (s in floatArrayOf(-1f, 1f)) {
                    push { tr(s * 0.26f, -0.10f, 0.34f); sc(0.08f, 0.05f, 0.05f); draw(sphere, 1f, 0.56f, 0.69f) }
                }
                // Улыбка
                push { tr(0f, -0.17f, 0.40f); sc(0.10f, 0.045f + 0.04f * beat, 0.04f); draw(sphere, 0.78f, 0.12f, 0.36f) }
            }
        }
    }

    // =====================================================================
    // 6. КОТ-БЕГУН 3D (бежит по ночному 3D-городу с неоновой трассой)
    // =====================================================================
    private fun drawRunner3D() {
        val groundY = -1.10f
        val spec = AudioFx.spectrum32

        // 3D-луна + неоновый ореол вдалеке
        push {
            tr(2.4f, 2.5f, -7.5f)
            push { sc(0.85f + 0.08f * beat); draw(sphere, 1f, 0.96f, 0.84f, emissive = 0.95f) }
            push { rot(75f, 1f, 0.3f, 0f); sc(1.30f + 0.15f * beat); draw(torus, pink, emissive = 0.85f) }
        }

        // Неоновая 3D-дорога (вдоль оси Z: кот бежит к -Z, город летит к +Z)
        push {
            tr(0f, groundY - 0.08f, -1.5f)
            sc(2.8f, 0.12f, 22f)
            draw(box, 0.09f, 0.07f, 0.16f, shininess = 70f)
        }
        // Неоновые бордюры дороги
        for (s in floatArrayOf(-1f, 1f)) {
            push {
                tr(s * 1.42f, groundY, -1.5f)
                sc(0.08f, 0.06f, 22f)
                draw(box, pink, emissive = 0.85f + 0.15f * beat)
            }
        }
        // Бегущая разметка
        val dashSpacing = 1.6f
        val dashOff = (runDist * 3.8f) % dashSpacing
        for (k in -7..5) {
            val z = k * dashSpacing + dashOff
            push {
                tr(0f, groundY + 0.01f, z)
                sc(0.10f, 0.02f, 0.65f)
                draw(box, cyan, emissive = 0.75f)
            }
        }

        // 3D-небоскрёбы-эквалайзеры слева и справа
        val bSpacing = 1.8f
        val bOff = (runDist * 2.2f) % bSpacing
        val bBaseIdx = (runDist * 2.2f / bSpacing).toInt()
        for (side in floatArrayOf(-1f, 1f)) {
            for (k in -7..4) {
                val z = k * bSpacing + bOff
                val idx = abs(k - bBaseIdx + if (side > 0) 13 else 0)
                val spVal = if (isPlaying) spec[idx % 32] else (0.12f + 0.08f * sin(t * 2f + idx))
                val bh = 1.0f + (idx % 4) * 0.45f + spVal * 2.2f + beat * 0.35f
                val bx = side * (2.65f + (idx % 2) * 0.55f)
                push {
                    tr(bx, groundY + bh * 0.5f, z)
                    sc(0.95f, bh, 0.95f)
                    draw(box, violet[0] * 0.55f, violet[1] * 0.55f, violet[2] * 0.65f, emissive = 0.18f + 0.15f * spVal)
                }
                // Светящаяся крыша-полоска
                push {
                    tr(bx, groundY + bh + 0.04f, z)
                    sc(0.98f, 0.08f, 0.98f)
                    draw(box, if (idx % 2 == 0) cyan else pink, emissive = 0.85f)
                }
            }
        }

        // Летящие 3D-ноты
        for (n in 0 until 6) {
            val nz = -9f + ((runDist * (3.0f + n * 0.4f) + n * 2.5f) % 14f)
            val nx = (if (n % 2 == 0) -1f else 1f) * (0.85f + (n % 3) * 0.35f)
            val ny = groundY + 1.5f + (n % 3) * 0.55f + sin(t * 3.5f + n) * 0.25f
            val col = if (n % 2 == 0) cyan else pink
            push {
                tr(nx, ny, nz)
                rot(t * 80f + n * 40f, 0f, 1f, 0f)
                push { sc(0.14f, 0.11f, 0.11f); draw(sphere, col, emissive = 0.85f) }
                push { tr(0.11f, 0.22f, 0f); sc(0.03f, 0.42f, 0.03f); draw(cylinder, col, emissive = 0.85f) }
                push { tr(0.20f, 0.38f, 0f); rot(-28f, 0f, 0f, 1f); sc(0.18f, 0.06f, 0.04f); draw(box, col, emissive = 0.85f) }
            }
        }

        // Тень кота на дороге
        val jumpY = jump * 0.95f
        val shScale = (1f - jump * 0.35f).coerceIn(0.4f, 1f)
        push {
            tr(0f, groundY + 0.005f, 0.4f)
            sc(0.55f * shScale, 0.02f, 0.95f * shScale)
            draw(cylinder, 0.02f, 0.02f, 0.04f, a = 0.55f)
        }

        // Бегущий 3D-кот (смотрит вперёд по -Z)
        val phase = runDist * 11.5f
        val inAir = jump > 0.04f
        val bob = if (inAir) 0f else abs(sin(phase)) * 0.10f
        val furR = 0.60f; val furG = 0.65f; val furB = 0.73f
        val darkR = 0.45f; val darkG = 0.50f; val darkB = 0.58f
        val bellyR = 0.93f; val bellyG = 0.91f; val bellyB = 0.88f

        push {
            tr(0f, groundY + 0.66f + jumpY + bob, 0.4f)
            val pitchBody = if (inAir) 16f else sin(phase) * 6f
            rot(pitchBody, 1f, 0f, 0f)

            // Горизонтальное тело кота (вытянуто вдоль Z)
            push { sc(0.46f, 0.42f, 0.78f); draw(sphere, furR, furG, furB) }
            push { tr(0f, -0.14f, 0f); sc(0.32f, 0.28f, 0.55f); draw(sphere, bellyR, bellyG, bellyB) }

            // 4 бегущие 3D-лапы (галоп / прыжок)
            val sw1 = sin(phase)
            val sw2 = sin(phase + 1.1f)
            val legLen = 0.52f
            for (side in floatArrayOf(-1f, 1f)) {
                val fAng = if (inAir) -48f else (if (side < 0) sw1 else -sw1) * 44f
                val bAng = if (inAir) 48f else (if (side < 0) -sw2 else sw2) * 44f
                // Передняя лапа
                val fr = Math.toRadians(fAng.toDouble())
                drawLimb(
                    side * 0.26f, -0.16f, -0.44f,
                    side * 0.26f, -0.16f - cos(fr).toFloat() * legLen, -0.44f + sin(fr).toFloat() * legLen,
                    0.10f, if (side < 0) furR else darkR, if (side < 0) furG else darkG, if (side < 0) furB else darkB,
                )
                // Задняя лапа
                val br = Math.toRadians(bAng.toDouble())
                drawLimb(
                    side * 0.26f, -0.16f, 0.44f,
                    side * 0.26f, -0.16f - cos(br).toFloat() * legLen, 0.44f + sin(br).toFloat() * legLen,
                    0.105f, if (side < 0) darkR else furR, if (side < 0) darkG else furG, if (side < 0) darkB else furB,
                )
            }

            // Волнистый 3D-хвост позади (+Z)
            var tx = 0f; var ty = 0.08f; var tz = 0.68f
            for (k in 1..6) {
                val u = k / 6f
                val nx = sin(phase * 0.5f + u * 2.5f) * 0.25f * u
                val ny = 0.08f + u * 0.65f + 0.10f * beat
                val nz = 0.68f + u * 0.68f
                drawLimb(tx, ty, tz, nx, ny, nz, 0.10f - u * 0.02f, darkR, darkG, darkB, capStart = k == 1)
                tx = nx; ty = ny; tz = nz
            }

            // Голова кота спереди (-Z)
            push {
                tr(0f, 0.38f, -0.74f)
                rot(180f, 0f, 1f, 0f) // чтобы локальная +Z смотрела вперёд по движению (-Z)
                push { sc(0.45f, 0.40f, 0.42f); draw(sphere, furR, furG, furB) }
                // Висячие ушки
                for (s in floatArrayOf(-1f, 1f)) {
                    push {
                        tr(s * 0.26f, 0.28f, 0.08f)
                        rot(s * -46f, 0f, 0f, 1f)
                        rot(28f, 1f, 0f, 0f)
                        sc(0.15f, 0.13f, 0.14f)
                        draw(cone, darkR, darkG, darkB)
                    }
                }
                drawEye3D(-0.16f, 0.05f, 0.36f, 0.10f, 0.95f, 0.68f, 0.20f, 0f)
                drawEye3D(0.16f, 0.05f, 0.36f, 0.10f, 0.95f, 0.68f, 0.20f, 0f)
                push { tr(0f, -0.05f, 0.42f); sc(0.06f, 0.05f, 0.05f); draw(sphere, 1f, 0.50f, 0.63f) }
                drawHeadphones3D(0.43f, 0.02f)
            }
        }
    }

    // =====================================================================
    // 7. 3D-ЭКВАЛАЙЗЕР (32 живые 3D-колонны по кругу + гироскоп-ядро)
    // =====================================================================
    private fun drawSpectrumArena3D() {
        val spec = AudioFx.spectrum32
        val baseY = -0.75f

        // Центральная светящаяся 3D-сфера ядра
        push {
            tr(0f, 0.35f, 0f)
            push { sc(0.62f + 0.18f * beat); draw(sphere, violet, emissive = 0.55f + 0.4f * beat) }
            // Два вращающихся 3D-гироскопа
            push {
                rot(t * 55f, 1f, 0.6f, 0f)
                sc(1.05f + 0.12f * beat)
                draw(torus, cyan, emissive = 0.85f)
            }
            push {
                rot(-t * 42f, 0.3f, 1f, 0.7f)
                sc(1.30f + 0.15f * level)
                draw(torus, pink, emissive = 0.85f)
            }
        }

        // 32 3D-колонны по кругу на реальном FFT-спектре
        val n = 32
        val ringR = 2.05f
        for (i in 0 until n) {
            val a = (i * 2.0 * PI / n).toFloat()
            val v = if (isPlaying) spec[i] else (0.10f + 0.08f * sin(t * 4f + i * 0.4f))
            val h = 0.25f + v * 2.45f + beat * 0.20f
            val k = i / n.toFloat()
            val cr = cyan[0] * (1f - k) + pink[0] * k
            val cg = cyan[1] * (1f - k) + pink[1] * k
            val cb = cyan[2] * (1f - k) + pink[2] * k
            push {
                tr(cos(a) * ringR, baseY + h * 0.5f, sin(a) * ringR)
                rot(-Math.toDegrees(a.toDouble()).toFloat(), 0f, 1f, 0f)
                sc(0.16f, h, 0.22f)
                draw(box, cr, cg, cb, emissive = 0.45f + 0.5f * v)
            }
            // Парящий пик над колонной
            push {
                tr(cos(a) * ringR, baseY + h + 0.14f, sin(a) * ringR)
                sc(0.14f, 0.07f, 0.18f)
                draw(box, 1f, 1f, 1f, emissive = 0.9f)
            }
        }

        // Основание арены
        push {
            tr(0f, baseY - 0.08f, 0f)
            sc(2.35f, 0.12f, 2.35f)
            draw(cylinder, 0.10f, 0.09f, 0.19f)
        }
    }

    // ---------------- Шейдеры OpenGL ES 2.0 ----------------
    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    companion object {
        private const val VS = """
            attribute vec3 aPos;
            attribute vec3 aNormal;
            uniform mat4 uMVP;
            uniform mat4 uModel;
            uniform mat3 uNormalMat;
            varying vec3 vWorldPos;
            varying vec3 vNormal;
            void main() {
                vec4 wp = uModel * vec4(aPos, 1.0);
                vWorldPos = wp.xyz;
                vNormal = normalize(uNormalMat * aNormal);
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """

        private const val FS = """
            precision mediump float;
            varying vec3 vWorldPos;
            varying vec3 vNormal;
            uniform vec4 uColor;
            uniform float uEmissive;
            uniform float uShininess;
            uniform vec3 uEyePos;
            uniform vec3 uPinkLight;
            uniform vec3 uCyanLight;
            uniform float uBeat;

            void main() {
                vec3 N = normalize(vNormal);
                vec3 V = normalize(uEyePos - vWorldPos);

                // Основной студийный свет сверху-спереди
                vec3 L1 = normalize(vec3(2.8, 4.5, 4.2));
                float d1 = max(dot(N, L1), 0.0);
                vec3 H1 = normalize(L1 + V);
                float sp1 = pow(max(dot(N, H1), 0.0), uShininess) * 0.42;

                // Неоновый розовый заполняющий свет слева (пульсирует под бас)
                vec3 L2 = normalize(vec3(-4.0, 1.5, 2.0));
                float d2 = max(dot(N, L2), 0.0) * (0.32 + 0.35 * uBeat);

                // Неоновый циановый контровой свет справа-сзади
                vec3 L3 = normalize(vec3(3.5, 2.2, -3.5));
                float d3 = max(dot(N, L3), 0.0) * (0.30 + 0.35 * uBeat);

                // Полусферический ambient
                float hemi = 0.5 + 0.5 * N.y;
                vec3 amb = mix(vec3(0.16, 0.14, 0.26), vec3(0.38, 0.38, 0.46), hemi);

                // Fresnel-ободок для объёмного 3D-силуэта
                float rim = pow(1.0 - max(dot(N, V), 0.0), 2.8) * (0.28 + 0.35 * uBeat);
                vec3 rimCol = mix(uCyanLight, uPinkLight, 0.5) * rim;

                vec3 lit = uColor.rgb * (amb + vec3(0.95) * d1 + uPinkLight * d2 + uCyanLight * d3)
                         + vec3(1.0) * sp1
                         + rimCol;
                vec3 finalCol = mix(lit, uColor.rgb * 1.15, clamp(uEmissive, 0.0, 1.0));
                gl_FragColor = vec4(finalCol, uColor.a);
            }
        """
    }
}
