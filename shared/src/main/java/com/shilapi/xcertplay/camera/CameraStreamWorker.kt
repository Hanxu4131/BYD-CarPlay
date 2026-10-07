package com.shilapi.xcertplay.camera

import java.net.Socket
import java.net.InetSocketAddress
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Dedicated app_process owner. Only this helper exits on a deadline; no vendor process is stopped. */
object CameraStreamWorker {
    internal data class Options(val port: Int, val nonce: String, val source: BmmCameraCapture.Source,
        val policy: BmmCameraCapture.Policy)

    @Volatile private var stage = "parse"

    private fun diagnose(at: String, failure: Throwable) {
        var cause: Throwable? = failure
        var errno: Int? = null
        repeat(4) { if (cause is android.system.ErrnoException) errno=(cause as android.system.ErrnoException).errno; cause=cause?.cause }
        // Never print an exception message/stack: socket addresses and SDK internals may contain private data.
        runCatching { android.util.Log.w("DiPlay-Camera", "helper stage=$at type=${failure.javaClass.simpleName} errno=${errno ?: "none"}") }
    }

    @JvmStatic fun main(args: Array<String>) {
        var exitCode = 1
        try { stage="parse"; run(parse(args)); exitCode = 0 } catch (failure: Throwable) { diagnose(stage,failure) }
        // OEM callback threads may survive normal Java cleanup; this process owns no other app resources.
        Runtime.getRuntime().halt(exitCode)
    }

    internal fun parse(args: Array<String>): Options {
        val allowed = setOf("port", "nonce", "source-id", "callback-index", "layout", "format", "policy", "proof", "tail")
        require(args.size in 8..9)
        val fields = mutableMapOf<String, String>()
        args.forEach {
            require(it.startsWith("--") && it.length <= 160)
            val p = it.drop(2).split('=', limit = 2)
            require(p.size == 2 && p[0] in allowed && p[1].isNotEmpty() && fields.put(p[0], p[1]) == null)
        }
        fun field(key: String) = requireNotNull(fields[key])
        val port = CameraCaptureTcp.validPort(field("port").toInt())
        val nonce = field("nonce"); require(nonce.matches(Regex("[0-9a-f]{32}")))
        val numbers = field("layout").split(',').map(String::toInt); require(numbers.size == 4)
        val layout = BmmCameraCapture.FrameLayout(numbers[0], numbers[1], numbers[2], numbers[3], BmmCameraCapture.PixelFormat.valueOf(field("format")))
        require(layout.minimumBytes in 1..CameraCaptureProtocol.MAX_FRAME_BYTES.toLong())
        val id = field("source-id").toInt(); val index = field("callback-index").toInt()
        val tail = fields["tail"]?.toInt() ?: 0
        val contract = if (tail == 0) null else {
            // The only tailed callback verified on this owner device. Do not normalize arbitrary capacity.
            require(id == 2 && index == 5 && tail == 7 && layout.width == 2560 && layout.height == 1920 &&
                layout.yStride == 2560 && layout.uvStride == 2560)
            BmmCameraCapture.VerifiedCallbackContract(2560,1920,21,7372800,5,7)
        }
        val proof = BmmCameraCapture.SourceProof.valueOf(field("proof"))
        return Options(port, nonce, BmmCameraCapture.Source(id, index, layout, proof,
            "Explicit app owner-verified capture contract", true, contract), BmmCameraCapture.Policy.valueOf(field("policy")))
    }

    internal fun errorType(detail: String?): String = detail?.substringBefore(':')
        ?.takeIf { it.length<=120 && it.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) } ?: "none"

    private fun run(options: Options) {
        val stopping = AtomicBoolean(false)
        val ready = AtomicBoolean(false)
        val controlAt = AtomicLong(SystemClock.elapsedRealtime())
        val started = SystemClock.elapsedRealtime()
        val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"camera-helper-watchdog").apply { isDaemon = true } }
        watchdog.scheduleAtFixedRate({
            val now = SystemClock.elapsedRealtime()
            if (now - controlAt.get() >= 6000 || (!ready.get() && now - started >= 8000)) {
                runCatching { android.util.Log.w("DiPlay-Camera", "helper stage=$stage deadline=true ready=${ready.get()}") }
                Runtime.getRuntime().halt(124)
            }
        }, 500, 500, TimeUnit.MILLISECONDS)
        val socket = Socket()
        var session: BmmCameraCapture.Session? = null
        val layout = requireNotNull(options.source.layoutExplicit).let { CameraFrameLayout(it.width,it.height,it.yStride,it.uvStride,
            if (it.format == BmmCameraCapture.PixelFormat.NV12) CameraPixelFormat.NV12 else CameraPixelFormat.NV21) }
        val queue = CameraCaptureLatest(requireNotNull(layout.payloadBytes()))
        var writer: Thread? = null
        try {
            stage="ipc-connect"
            android.util.Log.i("DiPlay-Camera", "helper stage=ipc-connect")
            socket.connect(InetSocketAddress(CameraCaptureTcp.address, CameraCaptureTcp.validPort(options.port)), 3000)
            socket.tcpNoDelay = true
            socket.soTimeout = 6000
            val input = DataInputStream(BufferedInputStream(socket.inputStream,1024))
            val output = DataOutputStream(BufferedOutputStream(socket.outputStream,65536))
            stage="ipc-hello"
            CameraCaptureProtocol.hello(output,options.nonce,layout)
            val control = Thread({
                try {
                    while (!stopping.get()) {
                        when(input.readInt()) {
                            CameraCaptureProtocol.PING -> controlAt.set(SystemClock.elapsedRealtime())
                            CameraCaptureProtocol.STOP -> { stopping.set(true); session?.close() }
                            else -> error("capture-control-version")
                        }
                    }
                } catch (failure: Throwable) {
                    if (failure !is java.io.EOFException && !stopping.get()) diagnose("control-read",failure)
                    stopping.set(true); session?.close()
                }
            },"camera-helper-control").apply { isDaemon=true; start() }
            stage="capture-open"
            android.util.Log.i("DiPlay-Camera", "helper stage=capture-open")
            session = BmmCameraCapture().stream(options.source, BmmCameraCapture.FrameConsumer { bytes, metadata ->
                if (!stopping.get()) queue.offer(bytes,metadata.payloadBytes,metadata.rawTimestamp)
            },options.policy)
            val owner = requireNotNull(session)
            stage="capture-ready"
            val opened = owner.ready.get(8,TimeUnit.SECONDS)
            if (stopping.get()) return
            if (!opened) {
                val result=runCatching { owner.result.get(100,TimeUnit.MILLISECONDS) }.getOrNull()
                android.util.Log.w("DiPlay-Camera", "helper stage=capture-ready outcome=${result?.outcome?.name ?: "PENDING"} errorType=${errorType(result?.detail)}")
                error("capture-open-rejected")
            }
            output.writeInt(CameraCaptureProtocol.READY); output.flush(); ready.set(true)
            stage="capture-stream"
            android.util.Log.i("DiPlay-Camera", "helper stage=capture-stream")
            writer = Thread({
                try {
                    while (!stopping.get()) {
                        val frame = queue.take() ?: continue
                        try { CameraCaptureProtocol.frame(output,frame.bytes,frame.timestamp); output.flush() }
                        finally { queue.release(frame) }
                    }
                } catch (failure: Throwable) {
                    if (!stopping.get()) diagnose("frame-write",failure)
                    stopping.set(true); owner.close()
                }
            },"camera-helper-writer").apply { isDaemon=true; start() }
            while (!stopping.get() && !owner.result.isDone) Thread.sleep(100)
        } catch (failure: Throwable) {
            diagnose(stage,failure)
            throw failure
        } finally {
            stopping.set(true)
            CameraCaptureTcp.close(socket) // Interrupt IPC before any OEM cleanup.
            queue.close()
            session?.close()
            // Keep watchdog independent of native cleanup, which cannot be interrupted by Future.cancel.
            watchdog.schedule({
                runCatching { android.util.Log.w("DiPlay-Camera", "helper stage=cleanup deadline=true") }
                Runtime.getRuntime().halt(124)
            },1500,TimeUnit.MILLISECONDS)
            runCatching { writer?.join(200) }
            val cleanup=runCatching { session?.result?.get(1200,TimeUnit.MILLISECONDS) }
            cleanup.onSuccess { result ->
                if (result!=null) android.util.Log.i("DiPlay-Camera", "helper stage=cleanup outcome=${result.outcome.name} errors=${result.cleanupErrors.size}")
            }.onFailure { diagnose("cleanup",it) }
            watchdog.shutdownNow()
        }
    }
}
