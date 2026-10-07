package com.shilapi.xcertplay.camera

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/** Original private IPC. No L1 wire format, image persistence, or guessed source dimensions. */
internal object CameraCaptureProtocol {
    const val MAGIC = 0x44504341
    const val VERSION = 1
    const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    const val HELLO = 1
    const val READY = 2
    const val FRAME = 3
    const val END = 4
    const val ERROR = 5
    const val PING = 11
    const val STOP = 12

    fun hello(output: DataOutputStream, nonce: String, layout: CameraFrameLayout) {
        require(nonce.matches(Regex("[0-9a-f]{32}")))
        require(layout.payloadBytes() in 1..MAX_FRAME_BYTES)
        output.writeInt(MAGIC); output.writeInt(VERSION); output.writeInt(HELLO)
        output.write(nonce.toByteArray(Charsets.US_ASCII))
        listOf(layout.width, layout.height, layout.yRowStride, layout.uvRowStride, layout.format.ordinal, layout.range.ordinal).forEach(output::writeInt)
        output.flush()
    }
    fun readHello(input: DataInputStream, nonce: String, expected: CameraFrameLayout) {
        require(input.readInt() == MAGIC && input.readInt() == VERSION && input.readInt() == HELLO) { "capture-protocol-version" }
        val received = ByteArray(32); input.readFully(received)
        require(java.security.MessageDigest.isEqual(received, nonce.toByteArray(Charsets.US_ASCII))) { "capture-owner-token" }
        val fields = IntArray(6) { input.readInt() }
        require(fields.contentEquals(intArrayOf(expected.width, expected.height, expected.yRowStride, expected.uvRowStride, expected.format.ordinal, expected.range.ordinal))) { "capture-layout-mismatch" }
        require(expected.payloadBytes() in 1..MAX_FRAME_BYTES)
    }
    fun frame(output: DataOutputStream, bytes: ByteArray, timestamp: Long) {
        require(bytes.size in 1..MAX_FRAME_BYTES)
        output.writeInt(FRAME); output.writeInt(bytes.size); output.writeLong(timestamp); output.write(bytes)
    }
    fun readFrame(input: DataInputStream, destination: ByteArray): Long {
        val length = input.readInt()
        require(length == destination.size && length in 1..MAX_FRAME_BYTES) { "capture-payload-length" }
        val timestamp = input.readLong(); input.readFully(destination)
        return timestamp
    }
}

/** Two fixed callback slabs: one writer and one latest. Vendor callback never waits for IPC I/O. */
internal class CameraCaptureLatest(private val frameBytes: Int) : AutoCloseable {
    init { require(frameBytes in 1..CameraCaptureProtocol.MAX_FRAME_BYTES) }
    internal class Frame internal constructor(val bytes: ByteArray, val timestamp: Long, internal val slot: Int)
    private val lock = Object()
    private val slabs = arrayOfNulls<ByteArray>(2)
    private var pending: Frame? = null
    private var writing: Frame? = null
    private var closed = false
    fun offer(source: ByteArray, payloadBytes: Int, timestamp: Long): Boolean = synchronized(lock) {
        if (closed || payloadBytes != frameBytes || source.size < payloadBytes) return@synchronized false
        val slot = if (writing?.slot == 0) 1 else 0
        val slab = slabs[slot] ?: ByteArray(frameBytes).also { slabs[slot] = it }
        System.arraycopy(source, 0, slab, 0, frameBytes)
        pending = Frame(slab, timestamp, slot)
        lock.notifyAll()
        true
    }
    fun take(waitMillis: Long = 500): Frame? = synchronized(lock) {
        check(writing == null) { "Only one writer owns a capture queue" }
        if (!closed && pending == null) lock.wait(waitMillis)
        if (closed) return@synchronized null
        pending?.also { pending = null; writing = it }
    }
    fun release(frame: Frame) = synchronized(lock) { if (writing === frame) writing = null }
    override fun close() = synchronized(lock) { closed = true; pending = null; lock.notifyAll() }
}

/** Loopback-only transport. The random owner token authenticates the helper, not a peer UID. */
internal object CameraCaptureTcp {
    val address: java.net.InetAddress = java.net.InetAddress.getByAddress(byteArrayOf(127,0,0,1))
    fun validPort(port: Int): Int { require(port in 1..65535) { "capture-port-invalid" }; return port }
    fun listen(): java.net.ServerSocket = java.net.ServerSocket().apply {
        try {
            reuseAddress=false
            bind(java.net.InetSocketAddress(address,0),1)
            validPort(localPort)
            soTimeout=8000
        } catch (failure: Throwable) { runCatching { close() }; throw failure }
    }
    fun close(socket: java.net.Socket) {
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }
}
