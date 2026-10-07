package com.shilapi.xcertplay.camera

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns one OEM capture object. Invoke only in a dedicated helper process with an external watchdog,
 * never automatically in the CarPlay host process: Java cancellation cannot interrupt blocked JNI.
 * Does not depend on L1 or its license/native code.
 */
class BmmCameraCapture internal constructor(private val backend: Backend) {
    constructor() : this(ReflectionBackend())

    enum class Policy { PUBLIC_ONLY, LEGACY_CONSTRUCTOR }
    enum class SourceProof { USER_CONFIGURATION, SDK_TAG }
    enum class PixelFormat { NV12, NV21 }

    /** Explicit verified layout only; raw callback integers are not sufficient to infer stride or chroma. */
    data class FrameLayout(val width: Int, val height: Int, val yStride: Int, val uvStride: Int, val format: PixelFormat) {
        init {
            require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
            require(yStride >= width && uvStride >= width)
            require(minimumBytes <= Int.MAX_VALUE) { "Frame exceeds byte-array capacity" }
        }
        /** Complete explicit frame size including row padding; trailing buffer capacity is not a frame. */
        val minimumBytes: Long get() = yStride.toLong() * height + uvStride.toLong() * (height / 2)
    }

    data class Source(
        val id: Int,
        val callbackIndex: Int,
        val layoutExplicit: FrameLayout?,
        val proof: SourceProof,
        val evidence: String,
        val userVerifiedSource: Boolean,
        val callbackContract: VerifiedCallbackContract? = null,
    ) {
        init { require(id >= 0 && callbackIndex >= 0 && evidence.isNotBlank()) }
    }

    /** Exact owner-verified vendor callback shape, including a known non-image buffer tail. */
    data class VerifiedCallbackContract(val width: Int, val height: Int, val rawFormat: Int,
        val payloadBytes: Int, val callbackIndex: Int, val tailBytes: Int) {
        init { require(width > 0 && height > 0 && payloadBytes > 0 && callbackIndex >= 0 && tailBytes in 0..64) }
        fun matches(arrayBytes: Int, raw: List<Int>, layout: FrameLayout): Boolean =
            layout.width == width && layout.height == height && layout.minimumBytes == payloadBytes.toLong() &&
                arrayBytes.toLong() == payloadBytes.toLong() + tailBytes &&
                raw == listOf(width, height, rawFormat, payloadBytes, callbackIndex)
    }

    data class Metadata(val byteLength: Int, val rawIntegers: List<Int>, val rawTimestamp: Long,
        val declaredLayout: FrameLayout?, val payloadBytes: Int = byteLength)
    fun interface FrameConsumer {
        /** Vendor-owned bytes are valid only during this synchronous callback. Copy here if needed later.
         * Metadata.payloadBytes is the explicit image prefix; only a verified callback contract permits a tail. close() prevents new delivery; an in-flight callback may finish. */
        fun onFrame(bytes: ByteArray, metadata: Metadata)
    }
    enum class Outcome { FRAMES_RECEIVED, NO_FRAMES, PUBLIC_OPEN_REJECTED, NATIVE_OPEN_REJECTED, FAILED, DEADLINE }
    data class Result(val outcome: Outcome, val frames: List<Metadata>, val legacyUsed: Boolean, val detail: String?, val cleanupErrors: List<String>)

    internal interface Backend {
        fun publicOpen(id: Int): Any?
        fun mappingMissing(): Boolean
        fun constructLegacy(id: Int): Any
        fun legacyOpen(camera: Any): Boolean
        fun callback(camera: Any, consumer: (ByteArray, List<Int>, Long) -> Unit)
        fun clearCallback(camera: Any)
        fun enable(camera: Any, index: Int): Boolean
        fun start(camera: Any): Boolean
        fun disable(camera: Any, index: Int)
        fun stop(camera: Any)
        fun close(camera: Any)
    }

    private val ownerLock = Any()
    private var owner: Session? = null

    /** Call off the UI thread. Observer deadline does not interrupt a blocked native call: helper needs a process watchdog. */
    fun metadataProbe(source: Source, policy: Policy = Policy.PUBLIC_ONLY, timeoutMillis: Long = 5_000, maxFrames: Int = 2): Result {
        require(timeoutMillis in 5_000..8_000 && maxFrames in 1..2)
        val session = launch(source, policy, maxFrames, null, timeoutMillis)
        return try {
            session.result.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            session.close()
            Result(Outcome.DEADLINE, session.snapshot(), session.legacyUsed, "Native cleanup may be pending; isolated helper watchdog required", emptyList())
        } catch (interrupted: InterruptedException) {
            session.close()
            Thread.currentThread().interrupt()
            Result(Outcome.DEADLINE, session.snapshot(), session.legacyUsed, "Observer interrupted; cleanup requested", emptyList())
        }
    }

    /** No unverified source may start a persistent stream. Caller explicitly owns and closes the returned session. */
    fun stream(source: Source, consumer: FrameConsumer, policy: Policy = Policy.PUBLIC_ONLY): Session {
        require(source.userVerifiedSource && source.layoutExplicit != null) { "Persistent capture requires a user-verified source and explicit pixel layout" }
        return launch(source, policy, null, consumer, null)
    }

    private fun launch(source: Source, policy: Policy, maximum: Int?, consumer: FrameConsumer?, timeout: Long?): Session = synchronized(ownerLock) {
        check(owner == null) { "Previous capture owner has not released" }
        val session = Session(source, policy, maximum, consumer, timeout)
        owner = session
        session.launch()
        session
    }

    inner class Session internal constructor(
        private val source: Source,
        private val policy: Policy,
        private val maximum: Int?,
        consumer: FrameConsumer?,
        private val timeout: Long?,
    ) : AutoCloseable {
        val result = CompletableFuture<Result>()
        val ready = CompletableFuture<Boolean>()
        private val cancelled = AtomicBoolean(false)
        private val done = CountDownLatch(1)
        private val count = AtomicLong()
        private val deliveryLock = Any()
        private val consumerFailure = AtomicReference<String?>()
        @Volatile private var frameConsumer = consumer
        private val frames = ArrayList<Metadata>()
        @Volatile internal var legacyUsed = false
        private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "BmmCapture-owner").apply { isDaemon = true } }
        internal fun launch() { worker.execute { runOwned() } }
        internal fun snapshot(): List<Metadata> = synchronized(frames) { frames.toList() }
        override fun close() { cancelled.set(true); frameConsumer = null; done.countDown() }
        private fun checkOwner() { check(!cancelled.get()) { "Capture cancelled" } }
        private fun runOwned() {
            var camera: Any? = null
            var enableAttempted = false
            var startAttempted = false
            var outcome = Outcome.FAILED
            var detail: String? = null
            val cleanup = ArrayList<String>()
            val started = System.nanoTime()
            try {
                checkOwner()
                camera = backend.publicOpen(source.id)
                if (camera == null) {
                    val allowLegacy = policy == Policy.LEGACY_CONSTRUCTOR && source.proof == SourceProof.USER_CONFIGURATION && backend.mappingMissing()
                    if (!allowLegacy) {
                        outcome = Outcome.PUBLIC_OPEN_REJECTED
                        return
                    }
                    checkOwner()
                    camera = backend.constructLegacy(source.id)
                    legacyUsed = true
                    checkOwner()
                    if (!backend.legacyOpen(camera)) {
                        outcome = Outcome.NATIVE_OPEN_REJECTED
                        return
                    }
                }
                checkOwner()
                backend.callback(camera) { bytes, raw, timestamp ->
                    synchronized(deliveryLock) {
                        val limit = maximum
                        if (!cancelled.get() && (limit == null || count.get() < limit)) {
                            val layout = source.layoutExplicit
                            val valid = raw.size == 5 && if (layout == null) true else {
                                val contract = source.callbackContract
                                if (contract != null) contract.matches(bytes.size, raw, layout)
                                else bytes.size.toLong() == layout.minimumBytes
                            }
                            if (valid) {
                                val ordinal = count.incrementAndGet()
                                val metadata = Metadata(bytes.size, raw.toList(), timestamp, layout, layout?.minimumBytes?.toInt() ?: bytes.size)
                                synchronized(frames) { if (frames.size < 2) frames.add(metadata) }
                                try { frameConsumer?.onFrame(bytes, metadata) }
                                catch (failure: Throwable) {
                                    consumerFailure.compareAndSet(null, "Frame consumer failed: " + failure.javaClass.simpleName)
                                    close()
                                }
                                if (limit != null && ordinal >= limit) done.countDown()
                            }
                        }
                    }
                }
                checkOwner()
                enableAttempted = true
                check(backend.enable(camera, source.callbackIndex)) { "OEM rejected callback index" }
                checkOwner()
                startAttempted = true
                check(backend.start(camera)) { "OEM rejected preview start" }
                ready.complete(!cancelled.get())
                if (timeout == null) done.await()
                else {
                    val remaining = timeout - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    if (remaining > 0) done.await(remaining, TimeUnit.MILLISECONDS)
                }
                detail = consumerFailure.get()
                outcome = if (detail != null) Outcome.FAILED else if (count.get() > 0) Outcome.FRAMES_RECEIVED else Outcome.NO_FRAMES
            } catch (failure: Exception) {
                detail = failure.javaClass.simpleName + ": " + (failure.message ?: "")
            } catch (failure: LinkageError) {
                detail = failure.javaClass.simpleName + ": " + (failure.message ?: "")
            } finally {
                cancelled.set(true)
                frameConsumer = null
                val own = camera
                if (own != null) {
                    fun release(name: String, action: () -> Unit) {
                        try { action() } catch (failure: Exception) { cleanup.add("$name:${failure.javaClass.simpleName}") }
                        catch (failure: LinkageError) { cleanup.add("$name:${failure.javaClass.simpleName}") }
                    }
                    release("callback") { backend.clearCallback(own) }
                    if (enableAttempted) release("disable") { backend.disable(own, source.callbackIndex) }
                    if (startAttempted) release("stop") { backend.stop(own) }
                    release("close") { backend.close(own) }
                }
                ready.complete(false)
                synchronized(ownerLock) { if (owner === this) owner = null }
                result.complete(Result(outcome, snapshot(), legacyUsed, detail, cleanup))
                worker.shutdown()
            }
        }
    }

    private class ReflectionBackend : Backend {
        private val loader by lazy {
            Class.forName("dalvik.system.PathClassLoader").getConstructor(String::class.java, String::class.java, ClassLoader::class.java)
                .newInstance("/system/framework/bmmcamera.jar", "/system/lib64", ClassLoader.getSystemClassLoader()) as ClassLoader
        }
        private val api by lazy { Class.forName("android.hardware.AVMCamera", false, loader) }
        private val callbackApi by lazy { Class.forName("android.hardware.AVMCamera\$IPreviewCallback", false, loader) }
        private var callbackProxy: Any? = null
        private fun call(name: String, target: Any?, types: Array<Class<*>>, vararg values: Any?): Any? = unwrap { api.getMethod(name, *types).invoke(target, *values) }
        override fun publicOpen(id: Int): Any? = call("open", null, arrayOf(Int::class.javaPrimitiveType!!), id)
        override fun mappingMissing(): Boolean {
            val info = Class.forName("android.hardware.BmmCameraInfo", true, loader)
            return unwrap { info.getMethod("getCameraNumbers").invoke(null) } == 0
        }
        override fun constructLegacy(id: Int): Any = unwrap {
            api.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(id)
        }!!
        override fun legacyOpen(camera: Any): Boolean = unwrap {
            api.getDeclaredMethod("open").apply { isAccessible = true }.invoke(camera)
        } as Boolean
        override fun callback(camera: Any, consumer: (ByteArray, List<Int>, Long) -> Unit) {
            callbackProxy = Proxy.newProxyInstance(callbackApi.classLoader, arrayOf(callbackApi)) { proxy, method, args ->
                when {
                    method.name == "hashCode" -> System.identityHashCode(proxy)
                    method.name == "equals" -> proxy === args?.get(0)
                    method.name == "toString" -> "BmmCaptureCallback"
                    method.name == "onPreview" && args != null && args.size == 8 -> {
                        val bytes = args[1] as? ByteArray
                        if (bytes != null) consumer(bytes, (2..6).map { args[it] as Int }, args[7] as Long)
                        null
                    }
                    else -> null
                }
            }
            call("setPreviewCallback", camera, arrayOf(callbackApi), callbackProxy)
        }
        override fun clearCallback(camera: Any) { try { call("setPreviewCallback", camera, arrayOf(callbackApi), null) } finally { callbackProxy = null } }
        override fun enable(camera: Any, index: Int): Boolean = call("enablePreviewCallback", camera, arrayOf(Int::class.javaPrimitiveType!!), index) as Boolean
        override fun start(camera: Any): Boolean = call("startPreview", camera, emptyArray()) as Boolean
        override fun disable(camera: Any, index: Int) { check(call("disablePreviewCallback", camera, arrayOf(Int::class.javaPrimitiveType!!), index) == true) { "Disable not confirmed" } }
        override fun stop(camera: Any) { check(call("stopPreview", camera, emptyArray()) == true) { "Stop not confirmed" } }
        override fun close(camera: Any) { call("close", camera, emptyArray()) }
        private fun <T> unwrap(block: () -> T): T = try { block() } catch (wrapped: InvocationTargetException) { throw wrapped.targetException }
    }
}
