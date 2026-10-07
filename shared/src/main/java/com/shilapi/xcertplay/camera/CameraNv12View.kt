package com.shilapi.xcertplay.camera

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Original GLES2 renderer for explicitly described NV12/NV21 frames. No bitmap conversion or storage.
 * Call submitFrame on the capture worker: it copies packed plane bytes, not RGB pixels. A replacement
 * drops the previous pending frame; at most one frame is pending and one is being uploaded by GL.
 * This view owns its EGL surface and textures; it never opens or takes ownership of the camera.
 */
class CameraNv12View @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {
    private data class Frame(val layout: CameraFrameLayout, val bytes: ByteBuffer)
    private val buffers = CameraFrameBufferPool<CameraFrameLayout>()
    private val paused = AtomicBoolean(false)
    private val transform = AtomicReference(CameraViewTransform())
    private val projection = AtomicReference<CameraProjection>(CameraProjection.None)
    private val released = AtomicBoolean(false)
    private val maxTextureSize = AtomicInteger(Int.MAX_VALUE)
    private val renderFailed = AtomicBoolean(false)
    private val drawCount = AtomicLong()
    private val cameraRenderer = CameraRenderer()

    init {
        setEGLContextClientVersion(2)
        // Context loss releases GL resources. A recreated surface waits for a fresh capture frame.
        preserveEGLContextOnPause = false
        setRenderer(cameraRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Size, plane strides and chroma byte order must come from the capture API, never a byte heuristic. */
    fun submitFrame(payload: ByteArray, layout: CameraFrameLayout, renderImmediately: Boolean = true): Boolean {
        if (released.get() || renderFailed.get() || !layout.accepts(payload.size) ||
            layout.width > maxTextureSize.get() || layout.height > maxTextureSize.get()) return false
        val size = layout.packedBytes() ?: return false
        // Bound both input-stride work and packed slabs before copying.
        if (payload.size > CameraFrameBufferPool.MAX_TOTAL_BYTES || size > CameraFrameBufferPool.MAX_FRAME_BYTES) return false
        val lease = buffers.acquire(size) ?: return false
        try {
            layout.packInto(payload, lease.bytes)
        } catch (failure: RuntimeException) {
            buffers.recycle(lease)
            return false
        }
        if (released.get() || !buffers.publish(lease, layout)) { buffers.recycle(lease); return false }
        // After publish, the GL thread may already own the slab. Only the pool can discard pending.
        if (!renderImmediately) return true
        return try { requestRender(); true } catch (_: RuntimeException) { buffers.clearPending(); false }
    }

    /** Successful GL draws, including alpha-zero warm draws; exposed only for bounded runtime diagnostics. */
    fun renderedFrameCount(): Long = drawCount.get()

    fun setViewTransform(value: CameraViewTransform) {
        require(value.valid()) { "invalid-camera-transform" }
        if (released.get()) return
        transform.set(value)
        requestRender()
    }

    /** Explicit per-camera calibration only. None keeps the original NV12/NV21 sampling path. */
    fun setProjection(value: CameraProjection) {
        require(value.valid()) { "invalid-camera-projection" }
        if (released.get()) return
        projection.set(value)
        requestRender()
    }

    /** Final release. Call before discarding the view; subsequent frames are rejected. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        buffers.close()
        queueEvent { cameraRenderer.releaseGl() }
        requestRender()
    }

    override fun onPause() {
        paused.set(true)
        buffers.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        paused.set(false)
        if (!released.get()) buffers.resume()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!released.get() && !paused.get()) buffers.resume()
    }

    override fun onDetachedFromWindow() {
        // Overlay hide must not disable acquisition: the next accepted frame is what shows it again.
        // Invalidate old copies, but permit a fresh bounded latest frame while unattached.
        buffers.clearPending()
        // GLSurfaceView exits its GL thread and destroys its EGL context/surface here; texture IDs
        // belonging to that context must not be deleted later from a new or another context.
        super.onDetachedFromWindow()
    }

    private inner class CameraRenderer : Renderer {
        private var program = 0
        private val textures = IntArray(2)
        private var layout: CameraFrameLayout? = null
        private var viewWidth = 0
        private var viewHeight = 0
        private var failed = false
        private var positionLocation = -1
        private var uvLocation = -1
        private var yLocation = -1
        private var chromaLocation = -1
        private var swapLocation = -1
        private var rangeLocation = -1
        private var projectLocation = -1
        private var lensLocation = -1
        private var regionLocation = -1
        private var halfFisheyeLocation = -1
        private var perspectiveLocation = -1
        private var rotationLocation = -1
        private var sourceSizeLocation = -1
        private var sourceTanLocation = -1
        private val vertices: FloatBuffer = floats(8)
        private val coordinates: FloatBuffer = floats(8)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // A fresh frame may have triggered attachment. CPU slabs are independent of EGL;
            // preserve it here. Pause/detach already invalidated the previous generation.
            program = 0
            textures.fill(0)
            layout = null
            failed = false
            renderFailed.set(false)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            val maximum = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maximum, 0)
            maxTextureSize.set(maximum[0].coerceAtLeast(1))
            if (released.get()) return
            try {
                program = createProgram()
                positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
                uvLocation = GLES20.glGetAttribLocation(program, "aUv")
                yLocation = GLES20.glGetUniformLocation(program, "uY")
                chromaLocation = GLES20.glGetUniformLocation(program, "uChroma")
                swapLocation = GLES20.glGetUniformLocation(program, "uSwap")
                rangeLocation = GLES20.glGetUniformLocation(program, "uLimited")
                projectLocation = GLES20.glGetUniformLocation(program, "uProject")
                lensLocation = GLES20.glGetUniformLocation(program, "uLens")
                regionLocation = GLES20.glGetUniformLocation(program, "uRegion")
                halfFisheyeLocation = GLES20.glGetUniformLocation(program, "uHalfFisheye")
                perspectiveLocation = GLES20.glGetUniformLocation(program, "uPerspective")
                rotationLocation = GLES20.glGetUniformLocation(program, "uRotation")
                sourceSizeLocation = GLES20.glGetUniformLocation(program, "uSourceSize")
                sourceTanLocation = GLES20.glGetUniformLocation(program, "uSourceTan")
                GLES20.glGenTextures(2, textures, 0)
                for (texture in textures) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                }
            } catch (error: RuntimeException) {
                failed = true
                renderFailed.set(true)
                releaseGl()
                Log.w("DiPlay-CameraGL", "Renderer initialization failed: ${error.message?.take(180)}")
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewWidth = width
            viewHeight = height
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (released.get() || failed || program == 0) { buffers.clearPending(); return }
            val ready = buffers.takeLatest()
            if (ready != null) {
                try {
                    val next = ready.metadata
                    if (next.width <= maxTextureSize.get() && next.height <= maxTextureSize.get()) upload(Frame(next, ready.lease.bytes))
                } finally { buffers.recycle(ready.lease) }
            }
            if (failed) return
            val current = layout ?: return
            val projection = projection.get()
            val projectedAspect = CameraProjectionMath.outputAspect(projection)
            val sourceTangents = if (projection is CameraProjection.Rectilinear)
                CameraProjectionMath.sourceTangents(projection, current.width, current.height) ?: return else null
            val bounds = CameraRenderMath.coordinates(current.width, current.height, viewWidth, viewHeight, transform.get(), projectedAspect) ?: return
            vertices.clear(); vertices.put(bounds.vertices); vertices.flip()
            coordinates.clear(); coordinates.put(bounds.texture); coordinates.flip()
            GLES20.glUseProgram(program)
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glVertexAttribPointer(uvLocation, 2, GLES20.GL_FLOAT, false, 0, coordinates)
            GLES20.glEnableVertexAttribArray(positionLocation)
            GLES20.glEnableVertexAttribArray(uvLocation)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            GLES20.glUniform1i(yLocation, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1])
            GLES20.glUniform1i(chromaLocation, 1)
            GLES20.glUniform1f(swapLocation, CameraRenderMath.swapUv(current.format))
            GLES20.glUniform1f(rangeLocation, if (current.range == CameraYuvRange.LIMITED) 1f else 0f)
            GLES20.glUniform1f(projectLocation, when (projection) {
                CameraProjection.None -> 0f
                is CameraProjection.EquidistantFisheye -> 1f
                is CameraProjection.Rectilinear -> 2f
            })
            if (projection !== CameraProjection.None) {
                GLES20.glUniform2f(perspectiveLocation, kotlin.math.tan(requireNotNull(CameraProjectionMath.horizontalFov(projection)) * .5f), requireNotNull(projectedAspect))
                GLES20.glUniformMatrix3fv(rotationLocation, 1, false, CameraProjectionMath.rotation(projection), 0)
                GLES20.glUniform2f(sourceSizeLocation, current.width.toFloat(), current.height.toFloat())
                val region = when (projection) {
                    is CameraProjection.EquidistantFisheye -> {
                        val lens = projection.lens
                        GLES20.glUniform4f(lensLocation, lens.centerX, lens.centerY, lens.radiusX, lens.radiusY)
                        GLES20.glUniform1f(halfFisheyeLocation, lens.fisheyeFovRadians * .5f)
                        lens.sourceRegion
                    }
                    is CameraProjection.Rectilinear -> {
                        val tangents = requireNotNull(sourceTangents)
                        GLES20.glUniform2f(sourceTanLocation, tangents[0], tangents[1])
                        projection.sourceRegion
                    }
                    else -> error("projection")
                }
                GLES20.glUniform4f(regionLocation, region.left, region.top, region.right, region.bottom)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            val drawError = GLES20.glGetError()
            if (drawError == GLES20.GL_NO_ERROR) drawCount.incrementAndGet() else {
                failed = true
                renderFailed.set(true)
                Log.w("DiPlay-CameraGL", "Draw failed: glError=$drawError")
            }
            GLES20.glDisableVertexAttribArray(positionLocation)
            GLES20.glDisableVertexAttribArray(uvLocation)
        }

        private fun upload(frame: Frame) {
            val next = frame.layout
            val ySize = next.width * next.height
            val allocate = layout?.width != next.width || layout?.height != next.height
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            frame.bytes.position(0); frame.bytes.limit(ySize)
            plane(0, next.width, next.height, GLES20.GL_LUMINANCE, frame.bytes.slice(), allocate)
            frame.bytes.limit(requireNotNull(next.packedBytes())); frame.bytes.position(ySize)
            plane(1, next.width / 2, next.height / 2, GLES20.GL_LUMINANCE_ALPHA, frame.bytes.slice(), allocate)
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                failed = true
                renderFailed.set(true)
                layout = null
                Log.w("DiPlay-CameraGL", "Texture upload failed: glError=$error")
            } else layout = next
        }

        private fun plane(index: Int, width: Int, height: Int, format: Int, bytes: ByteBuffer, allocate: Boolean) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[index])
            if (allocate) GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, width, height, 0, format, GLES20.GL_UNSIGNED_BYTE, bytes)
            else GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, format, GLES20.GL_UNSIGNED_BYTE, bytes)
        }

        fun releaseGl() {
            if (textures.any { it != 0 }) GLES20.glDeleteTextures(2, textures, 0)
            textures.fill(0)
            if (program != 0) GLES20.glDeleteProgram(program)
            program = 0
            layout = null
        }
    }

    private companion object {
        fun floats(count: Int): FloatBuffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fun shader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            check(shader != 0) { "shader-allocation" }
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val message = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error("shader-compile:$message")
            }
            return shader
        }
        fun createProgram(): Int {
            val vertex = shader(GLES20.GL_VERTEX_SHADER, VERTEX)
            var fragment = 0
            var program = 0
            try {
                fragment = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
                program = GLES20.glCreateProgram()
                check(program != 0) { "program-allocation" }
                GLES20.glAttachShader(program, vertex)
                GLES20.glAttachShader(program, fragment)
                GLES20.glLinkProgram(program)
                val status = IntArray(1)
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
                check(status[0] != 0) { "program-link:${GLES20.glGetProgramInfoLog(program)}" }
                return program
            } catch (error: RuntimeException) {
                if (program != 0) GLES20.glDeleteProgram(program)
                throw error
            } finally {
                GLES20.glDeleteShader(vertex)
                if (fragment != 0) GLES20.glDeleteShader(fragment)
            }
        }
        const val VERTEX = """
            attribute vec2 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() { vUv = aUv; gl_Position = vec4(aPosition, 0.0, 1.0); }
        """
        const val FRAGMENT = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D uY;
            uniform sampler2D uChroma;
            uniform float uSwap;
            uniform float uLimited;
            uniform float uProject;
            uniform vec4 uLens;
            uniform vec4 uRegion;
            uniform float uHalfFisheye;
            uniform vec2 uPerspective;
            uniform mat3 uRotation;
            uniform vec2 uSourceSize;
            uniform vec2 uSourceTan;
            void main() {
                vec2 sampleUv = vUv;
                if (uProject > 0.5) {
                    vec3 rawRay = vec3((2.0 * vUv.x - 1.0) * uPerspective.x,
                        (1.0 - 2.0 * vUv.y) * uPerspective.x / uPerspective.y, 1.0);
                    float maxComponent = max(max(abs(rawRay.x), abs(rawRay.y)), 1.0);
                    vec3 ray = normalize(rawRay / maxComponent);
                    ray = uRotation * ray;
                    if (uProject > 1.5) {
                        if (ray.z <= 0.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
                        vec2 localUv = vec2(0.5) + vec2(0.5, -0.5) * (ray.xy / ray.z) / uSourceTan;
                        sampleUv = uRegion.xy + localUv * (uRegion.zw - uRegion.xy);
                    } else {
                        float radial = length(ray.xy);
                        float theta = atan(radial, ray.z);
                        if (theta > uHalfFisheye || (radial < 0.00001 && ray.z < 0.0)) {
                            gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return;
                        }
                        vec2 direction = radial < 0.00001 ? vec2(0.0) : ray.xy / radial;
                        sampleUv = uLens.xy + vec2(direction.x, -direction.y) * uLens.zw * (theta / uHalfFisheye);
                    }
                    vec2 inset = vec2(1.0) / uSourceSize;
                    #ifndef GL_FRAGMENT_PRECISION_HIGH
                    inset = max(inset, vec2(2.0 / 1024.0));
                    #endif
                    if (sampleUv.x < uRegion.x + inset.x || sampleUv.y < uRegion.y + inset.y ||
                        sampleUv.x > uRegion.z - inset.x || sampleUv.y > uRegion.w - inset.y) {
                        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return;
                    }
                }
                vec2 bytes = texture2D(uChroma, sampleUv).ra;
                vec2 uv = mix(bytes, bytes.yx, uSwap) - vec2(128.0 / 255.0);
                float y = (texture2D(uY, sampleUv).r - uLimited * (16.0 / 255.0)) * mix(1.0, 1.164383, uLimited);
                float r = y + mix(1.402, 1.596027, uLimited) * uv.y;
                float g = y - mix(0.344136, 0.391762, uLimited) * uv.x - mix(0.714136, 0.812968, uLimited) * uv.y;
                float b = y + mix(1.772, 2.017232, uLimited) * uv.x;
                gl_FragColor = vec4(clamp(vec3(r, g, b), 0.0, 1.0), 1.0);
            }
        """
    }
}
