package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.MainViewArea
import kotlin.math.abs

/** Remember source window sizes, so a fullscreen reconnect still advertises known split areas. */
internal object AdaptiveViewAreaHistory {
    data class Size(val width: Int, val height: Int)
    const val MAX_SPLIT_AREAS = 3
    const val STABLE_MILLIS = 1000L

    fun split(size: Size, canvas: Size): Boolean =
        canvas.width > 0 && canvas.height > 0 && size.width > 0 && size.height > 0 &&
            size.width < canvas.width - 2 && size.height <= canvas.height &&
            size.width.toLong() * 4 >= canvas.width && size.height.toLong() * 2 >= canvas.height

    private fun same(a: Size, b: Size): Boolean = abs(a.width - b.width) <= 2 && abs(a.height - b.height) <= 2

    fun remember(history: List<Size>, size: Size, canvas: Size): List<Size> {
        val valid = history.filter { split(it, canvas) }.fold(emptyList<Size>()) { result, item ->
            if (result.any { same(it, item) }) result else result + item
        }
        if (!split(size, canvas)) return valid.take(MAX_SPLIT_AREAS)
        // Keep the original dimensions when layout jitters by a pixel or two.
        val existing = valid.firstOrNull { same(it, size) } ?: size
        return (listOf(existing) + valid.filterNot { same(it, size) }).take(MAX_SPLIT_AREAS)
    }

    fun areas(canvas: Size, encoded: Size, current: Size, history: List<Size>): List<MainViewArea> {
        fun scale(size: Size) = MainViewArea(
            (size.width.toLong() * encoded.width / canvas.width).toInt(),
            (size.height.toLong() * encoded.height / canvas.height).toInt())
        return (listOf(MainViewArea(encoded.width, encoded.height), scale(current)) +
            history.filter { split(it, canvas) }.map(::scale))
            .filter { it.valid(encoded.width, encoded.height) }.distinct().take(4)
    }
}
