package com.shilapi.xcertplay.airplay

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Finite regions negotiated once; the HID canvas never changes during the session. */
data class MainViewArea(val width: Int, val height: Int, val x: Int = 0, val y: Int = 0) {
    fun valid(canvasWidth: Int, canvasHeight: Int): Boolean = width > 0 && height > 0 &&
        x >= 0 && y >= 0 && x.toLong() + width <= canvasWidth && y.toLong() + height <= canvasHeight
}

data class MainAreaViewport(val codedWidth: Int, val codedHeight: Int,
    val x: Int, val y: Int, val width: Int, val height: Int) {
    fun valid(): Boolean = codedWidth in 1..8192 && codedHeight in 1..8192 && MainViewArea(width, height, x, y).valid(codedWidth, codedHeight)
    fun matches(area: MainViewArea): Boolean = x == area.x && y == area.y && width == area.width && height == area.height
    fun map(contact: AirPlayContact, masterWidth: Int, masterHeight: Int): AirPlayContact = contact.copy(
        x = (x + contact.x.coerceIn(0.0, 1.0) * width) / masterWidth,
        y = (y + contact.y.coerceIn(0.0, 1.0) * height) / masterHeight)
    companion object {
        fun parse(header: ByteArray): MainAreaViewport? {
            if (header.size < 48) return null
            val bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            fun pixel(offset: Int): Int? {
                val value = bytes.getFloat(offset)
                if (!value.isFinite() || value < 0 || value > 8192 || abs(value - value.toInt()) > 0.01f) return null
                return value.toInt()
            }
            val cw = pixel(16) ?: return null; val ch = pixel(20) ?: return null
            val x = pixel(32) ?: return null; val y = pixel(36) ?: return null
            val w = pixel(40) ?: return null; val h = pixel(44) ?: return null
            if (!MainViewArea(w, h, x, y).valid(cw, ch)) return null
            return MainAreaViewport(cw, ch, x, y, w, h)
        }
    }
}

/** Command writes only select a desired area; a matching returned header confirms it. */
class MainAreaSelection(val canvasWidth: Int, val canvasHeight: Int, val areas: List<MainViewArea>, initial: Int, val sourceWidth: Int = canvasWidth, val sourceHeight: Int = canvasHeight) {
    init { require(sourceWidth > 0 && sourceHeight > 0 && canvasWidth in 1..8192 && canvasHeight in 1..8192); require(areas.isNotEmpty() && areas.size <= 4 && areas.all { it.valid(canvasWidth, canvasHeight) }); require(initial in areas.indices) }
    private var desired = initial
    private var epoch = 0L
    private var requestedEpoch = -1L
    private var requestAttempts = 0
    private var requestOwner: Any? = null
    @Volatile var h264 = false; private set
    @Volatile var confirmed: MainAreaViewport? = null; private set
    @Synchronized fun select(width: Int, height: Int): Pair<Int, Long>? {
        val index = areas.indexOfFirst { it.width == width && it.height == height }
        if (index < 0) return null
        if (desired != index) { desired = index; confirmed = null; epoch++; requestOwner = null }
        return index to epoch
    }
    @Synchronized fun beginRequestSeries(index: Int, token: Long, owner: Any): Boolean {
        if (!current(index, token) || confirmed != null || requestOwner === owner ||
            (requestedEpoch == token && requestAttempts >= 3)) return false
        requestOwner = owner
        return true
    }
    @Synchronized fun ownsRequestSeries(index: Int, token: Long, owner: Any): Boolean =
        current(index, token) && requestOwner === owner
    @Synchronized fun markRequested(index: Int, token: Long, maximumAttempts: Int = 1): Boolean {
        if (!current(index, token)) return false
        if (requestedEpoch != token) { requestedEpoch = token; requestAttempts = 0 }
        if (requestAttempts >= maximumAttempts.coerceIn(1, 3)) return false
        requestAttempts++; return true
    }
    @Synchronized fun current(index: Int, token: Long): Boolean = h264 && desired == index && token == epoch
    @Synchronized fun receive(codec: VideoCodec, geometry: MainAreaViewport?): Boolean {
        h264 = codec == VideoCodec.H264
        if (!h264) { confirmed = null; return false }
        val matching = geometry?.takeIf { it.valid() && areas.getOrNull(desired)?.let(it::matches) == true && it.codedWidth <= canvasWidth && it.codedHeight <= canvasHeight }
        if (matching != null) confirmed = matching
        return matching != null
    }
    @Synchronized fun reset() { epoch++; h264 = false; confirmed = null; requestOwner = null }
}
