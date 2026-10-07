package com.shilapi.xcertplay.camera

import android.content.Context
import java.net.ServerSocket
import java.net.Socket
import android.os.SystemClock
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One isolated OEM helper and one bounded binary reader. No capture JNI runs in the host process. */
class CameraCaptureClient(context: Context,
    private val onFrame: (CameraView,ByteArray,CameraFrameLayout) -> Unit,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    enum class Phase { STOPPED, STARTING, STREAMING, FAILED, CLOSED }
    data class Status(val phase: Phase, val message: String)
    private val app = context.applicationContext
    private val reader = Executors.newSingleThreadExecutor { r -> Thread(r,"camera-capture-reader").apply { isDaemon=true } }
    private val cleaner = Executors.newSingleThreadExecutor { r -> Thread(r,"camera-capture-cleanup").apply { isDaemon=true } }
    private val controlWriter = Executors.newSingleThreadExecutor { r -> Thread(r,"camera-capture-control").apply { isDaemon=true } }
    private val timers = Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"camera-capture-deadline").apply { isDaemon=true } }
    private val generation = AtomicLong()
    private val closed = AtomicBoolean(false)
    private val transport = AtomicReference<Transport?>()
    @Volatile private var state = Status(Phase.STOPPED,"摄像头采集已停止")
    @Volatile private var currentPlan: CameraVerifiedCapturePlan? = null
    @Volatile private var stoppedAt = 0L
    @Volatile private var stoppedL1ForActivation = false

    fun status(): Status = state

    @Synchronized fun start(plan: CameraVerifiedCapturePlan) {
        check(!closed.get()); plan.validate()
        val safe = plan.copy(regions=plan.regions.toMap())
        if (safe == currentPlan && state.phase in setOf(Phase.STARTING,Phase.STREAMING)) return
        cancelTransport()
        currentPlan=safe
        val ticket=generation.incrementAndGet()
        state=Status(Phase.STARTING,"摄像头采集启动中")
        reader.execute { readOwned(ticket,safe) }
    }

    @Synchronized fun stop() {
        if (closed.get()) return
        generation.incrementAndGet(); currentPlan=null; stoppedL1ForActivation=false
        state=Status(Phase.STOPPED,"摄像头采集已停止")
        cancelTransport()
    }

    private fun cancelTransport() {
        transport.getAndSet(null)?.let { owned ->
            stoppedAt=SystemClock.elapsedRealtime()
            owned.closeSocket() // Never queue socket cancellation behind ADB cleanup or a control write.
            cleaner.execute { owned.close() }
        }
    }

    private fun readOwned(ticket: Long, plan: CameraVerifiedCapturePlan) {
        var owned: Transport? = null
        var watchdog: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            // Previous helper has a 1.5s hard cleanup deadline. Do not overlap OEM owners on a plan change.
            val remaining=1500-(SystemClock.elapsedRealtime()-stoppedAt)
            if (remaining>0 && stoppedAt>0) Thread.sleep(remaining)
            if (!active(ticket)) return
            val nonce=ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            owned=Transport(CameraCaptureTcp.listen(),nonce)
            if (!transport.compareAndSet(null,owned) || !active(ticket)) return
            val local=owned
            val started=SystemClock.elapsedRealtime()
            val lastFrame=AtomicLong(0)
            val opened=AtomicBoolean(false)
            watchdog=timers.scheduleAtFixedRate({
                if (!active(ticket)) { local.closeSocket(); return@scheduleAtFixedRate }
                val now=SystemClock.elapsedRealtime()
                val timedOut=if (!opened.get()) now-started>=8000 else now-lastFrame.get()>=2500
                if (timedOut) {
                    local.closeSocket() // Deadline cancellation never waits for the control writer's lock.
                    fail(ticket,if (opened.get()) "摄像头帧已中断；已关闭当前采集" else "摄像头启动超时，关闭后重新开启")
                } else ping(local)
            },500,1000,TimeUnit.MILLISECONDS)
            val key=AdbKeys.load(app)
            val adb=LocalAdb(key); local.adb=adb
            val access = adb.connect(mayAsk=false)
            android.util.Log.i("DiPlay-Camera", "capture local ADB access=$access")
            check(access == LocalAdb.Access.READY) { "capture-adb-$access" }
            check(active(ticket))
            if (!stoppedL1ForActivation) {
                val response=adb.shell("am force-stop l1tech.com.l1mini")
                if (response==null || response.isNotBlank()) {
                    val installed=adb.shell("pm path l1tech.com.l1mini")
                    if (installed==null || installed.isNotBlank()) {
                        android.util.Log.w("DiPlay-Camera","L1Mini app stop command failed")
                        fail(ticket,"旧L1应用界面未能停止；内置摄像头未启动")
                        return
                    }
                    android.util.Log.i("DiPlay-Camera","L1Mini app absent; capture may proceed")
                } else android.util.Log.i("DiPlay-Camera","L1Mini app stop command accepted")
                check(active(ticket))
                check(CameraLegacyOwnerStop.stop(adb)) { "legacy-camera-owner-stop" }
                android.util.Log.i("DiPlay-Camera", "L1 owned backend stop submitted")
                stoppedL1ForActivation=true
            }
            check(active(ticket))
            adb.close()
            // Shell cleanup packets must not share the helper's fresh interactive stream.
            val helperAdb=LocalAdb(key); local.adb=helperAdb
            val helperAccess=helperAdb.connect(mayAsk=false)
            android.util.Log.i("DiPlay-Camera", "capture helper ADB access=$helperAccess")
            check(helperAccess==LocalAdb.Access.READY) { "capture-helper-adb-$helperAccess" }
            val source=plan.source; val layout=plan.layout
            val apk="'"+app.applicationInfo.sourceDir.replace("'","'\\''")+"'"
            val dimensions="${layout.width},${layout.height},${layout.yRowStride},${layout.uvRowStride}"
            val tail=source.callbackContract?.tailBytes ?: 0
            check(active(ticket))
            val command="CLASSPATH=$apk app_process /system/bin ${CameraStreamWorker::class.java.name} " +
                "--port=${local.server.localPort} --nonce=$nonce --source-id=${source.id} --callback-index=${source.callbackIndex} " +
                "--layout=$dimensions --format=${layout.format.name} --policy=${plan.policy.name} --proof=${source.proof.name} --tail=$tail >/dev/null 2>&1"
            local.shell=helperAdb.openShell(command) ?: error("capture-helper-launch")
            check(active(ticket))
            val socket=local.server.accept(); local.socket=socket
            check(active(ticket))
            check(!local.closed.get()) { "capture-transport-closed" }
            require(socket.inetAddress == CameraCaptureTcp.address) { "capture-peer-not-loopback" }
            socket.tcpNoDelay=true
            socket.soTimeout=8000
            val input=DataInputStream(BufferedInputStream(socket.inputStream,65536))
            local.control=DataOutputStream(socket.outputStream)
            CameraCaptureProtocol.readHello(input,nonce,layout)
            ping(local)
            require(input.readInt()==CameraCaptureProtocol.READY) { "capture-helper-not-ready" }
            lastFrame.set(SystemClock.elapsedRealtime()); opened.set(true)
            synchronized(this) {
                check(active(ticket))
                state=Status(Phase.STREAMING,"摄像头实时采集中")
            }
            android.util.Log.i("DiPlay-Camera","capture ready source=${source.id} layout=${layout.width}x${layout.height}")
            socket.soTimeout=2000
            var receivedFrames=0L
            var lastDiagnosticFrames=0L
            var lastDiagnosticMs=SystemClock.elapsedRealtime()
            val payload=ByteArray(requireNotNull(layout.payloadBytes()))
            val mapper=CameraFrameMapper(layout,plan.regions)
            while(active(ticket)) {
                require(input.readInt()==CameraCaptureProtocol.FRAME) { "capture-record-invalid" }
                CameraCaptureProtocol.readFrame(input,payload)
                if (!active(ticket)) break
                val now=SystemClock.elapsedRealtime()
                lastFrame.set(now)
                receivedFrames++
                if (now-lastDiagnosticMs>=10000) {
                    val fps=(receivedFrames-lastDiagnosticFrames)*1000f/(now-lastDiagnosticMs)
                    android.util.Log.i("DiPlay-Camera","capture received=$receivedFrames fps=${String.format(java.util.Locale.US,"%.1f",fps)}")
                    lastDiagnosticMs=now;lastDiagnosticFrames=receivedFrames
                }
                mapper.dispatch(payload) { view,bytes,cropped -> if (active(ticket)) onFrame(view,bytes,cropped) }
            }
        } catch (failure: Exception) {
            if (active(ticket)) {
                android.util.Log.w("DiPlay-Camera", "capture failed type=${failure.javaClass.simpleName} reason=${failure.message}")
                fail(ticket,"摄像头采集不可用（${failure.javaClass.simpleName}）")
            }
        } finally {
            watchdog?.cancel(false)
            owned?.let { transport.compareAndSet(it,null); it.close() }
        }
    }
    private fun ping(local: Transport) {
        if (local.closed.get() || local.control == null || !local.controlQueued.compareAndSet(false,true)) return
        try {
            controlWriter.execute {
                try {
                    if (!local.closed.get()) local.control?.let { synchronized(it) {
                        if (!local.closed.get()) { it.writeInt(CameraCaptureProtocol.PING); it.flush() }
                    } }
                } catch (_: Exception) { local.closeSocket() }
                finally { local.controlQueued.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { local.controlQueued.set(false) }
    }
    private fun active(ticket: Long): Boolean = !closed.get() && generation.get()==ticket && state.phase!=Phase.FAILED
    private fun fail(ticket: Long,message: String) {
        val accepted=synchronized(this) {
            if (closed.get() || generation.get()!=ticket || state.phase==Phase.FAILED) false
            else { state=Status(Phase.FAILED,message); true }
        }
        if (accepted) onFailure(message)
    }
    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        generation.incrementAndGet(); currentPlan=null
        state=Status(Phase.CLOSED,"摄像头采集已关闭")
        cancelTransport()
        reader.shutdown(); cleaner.shutdown(); controlWriter.shutdownNow(); timers.shutdownNow()
    }

    private class Transport(val server: ServerSocket,val nonce: String) : AutoCloseable {
        val closed=AtomicBoolean(false)
        val controlQueued=AtomicBoolean(false)
        @Volatile var socket: Socket?=null
        @Volatile var control: DataOutputStream?=null
        @Volatile var adb: LocalAdb?=null
        @Volatile var shell: LocalAdb.InteractiveShell?=null
        fun closeSocket() {
            closed.set(true)
            // Neither cancellation nor listener close takes the control stream's write lock.
            runCatching { server.close() }
            socket?.let(CameraCaptureTcp::close)
        }
        override fun close() {
            // EOF is the helper's stop signal. Close first to interrupt a stuck control write/read.
            closeSocket()
            runCatching { shell?.close() }; runCatching { adb?.close() }
        }
    }
}
