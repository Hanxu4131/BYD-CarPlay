package com.shilapi.xcertplay.camera

/** Candidate source identifiers only; importing a file never verifies hardware or lens calibration. */
data class CameraSourceReference(
    val cameraId: Int? = null,
    val cameraMode: String? = null,
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null,
    val channelOrder: String? = null,
    val verified: Boolean = false,
)

internal data class CameraReferenceAngles(val yawDegrees: Float, val pitchDegrees: Float, val rollDegrees: Float)
internal data class ParsedCameraReference(
    val source: CameraSourceReference,
    val angles: Map<String, CameraReferenceAngles>,
    // Unknown corner semantics: retained only in the preview session, never used to infer a lens.
    val corners: Map<String, List<Double>>,
)

internal object CameraReferenceParser {
    const val MAX_BYTES = 64 * 1024
    val angleKeys = setOf("BSD_FE_LB", "BSD_FE_RB", "BSD_FE_LF", "BSD_FE_RF")
    private val integerKeys = setOf("CameraID", "SrcW", "SrcH")

    fun parse(bytes: ByteArray): ParsedCameraReference {
        require(bytes.size <= MAX_BYTES) { "参考配置超过 64 KiB" }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val root = Reader(text).root(integerKeys + angleKeys + setOf("ChannelOrder", "CameraMode"))
        val numbers = integerKeys.mapNotNull { key ->
            if (!root.containsKey(key)) null else {
                val raw = root[key]
                val n = if (raw is String) numericString(raw) else raw as? Double ?: error("$key 必须是整数")
                require(n.isFinite() && n % 1.0 == 0.0 && n >= Int.MIN_VALUE && n <= Int.MAX_VALUE) { "$key 必须是有效整数" }
                key to n.toInt()
            }
        }.toMap()
        listOf("SrcW", "SrcH").forEach { key -> numbers[key]?.let { require(it > 0) { "$key 必须大于 0" } } }
        val angles = mutableMapOf<String, CameraReferenceAngles>()
        val corners = mutableMapOf<String, List<Double>>()
        angleKeys.forEach { key ->
            if (root.containsKey(key)) {
                val raw = root[key]
                val decoded = if (raw is String) {
                    if (raw.trimStart().startsWith("[")) Reader(raw).arrayDocument()
                    else raw.split(',').map { numericString(it) }
                } else raw
                val list = decoded as? List<*>
                    ?: error("$key 必须是 11 项数字数组")
                require(list.size == 11 && list.all { it is Double && it.isFinite() }) { "$key 必须是 11 项有限数字" }
                val n = list.map { it as Double }
                fun degrees(index: Int): Float {
                    val result = Math.toDegrees(n[index]).toFloat()
                    require(result.isFinite()) { "$key 角度超出范围" }
                    return result
                }
                angles[key] = CameraReferenceAngles(degrees(0), degrees(10), degrees(1))
                corners[key] = n.subList(2, 10).toList()
            }
        }
        val mode = if (root.containsKey("CameraMode")) cameraMode(root["CameraMode"]) else null
        val order = if (root.containsKey("ChannelOrder")) channelOrder(root["ChannelOrder"]) else null
        require(numbers.isNotEmpty() || angles.isNotEmpty() || order != null || mode != null) { "未找到支持的摄像头字段" }
        return ParsedCameraReference(CameraSourceReference(numbers["CameraID"], mode,
            numbers["SrcW"], numbers["SrcH"], order), angles.toMap(), corners.toMap())
    }

    internal fun cameraMode(raw: Any?): String {
        if (raw is String && raw.trim() == "AVM") return "AVM"
        val text = when (raw) { is String -> raw.trim(); is Double -> raw.toString(); is Int -> raw.toString(); else -> error("CameraMode 候选格式无效") }
        val n = numericString(text)
        require(n % 1.0 == 0.0 && n >= Int.MIN_VALUE && n <= Int.MAX_VALUE)
        return n.toInt().toString()
    }

    internal fun channelOrder(raw: Any?): String {
        val text = when (raw) { is String -> raw.trim(); is Double -> raw.toString(); is Int -> raw.toString(); else -> error("ChannelOrder 候选格式无效") }
        require(text.length <= 32)
        val letters = text.split(Regex("\\s+"))
        if (letters.size == 4 && letters.toSet() == setOf("B", "L", "R", "F")) return letters.joinToString(" ")
        val n = numericString(text)
        require(n % 1.0 == 0.0 && n >= Int.MIN_VALUE && n <= Int.MAX_VALUE) { "ChannelOrder 候选格式无效" }
        return n.toInt().toString()
    }

    private fun numericString(raw: String): Double {
        val text = raw.trim()
        require(text.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))) { "摄像头数字字段格式无效" }
        return text.toDouble().also { require(it.isFinite()) { "摄像头数字字段不是有限值" } }
    }

    /** Small strict JSON reader. Unknown values are consumed without retaining their data. */
    private class Reader(private val input: String) {
        private var pos = 0
        private fun whitespace() { while (pos < input.length && input[pos] in " \n\r\t") pos++ }
        private fun take(c: Char): Boolean { whitespace(); return if (pos < input.length && input[pos] == c) { pos++; true } else false }
        private fun need(c: Char) { require(take(c)) { "参考配置 JSON 格式错误" } }
        private fun done() { whitespace(); require(pos == input.length) { "参考配置有多余内容" } }
        fun root(allowed: Set<String>): Map<String, Any?> {
            val result = mutableMapOf<String, Any?>(); val seen = mutableSetOf<String>()
            need('{')
            if (!take('}')) { do {
                val key = string(); need(':')
                if (key in allowed) { require(seen.add(key)) { "摄像头字段重复" }; result[key] = value(0, true) }
                else value(0, false)
            } while (take(',')); need('}') }
            done(); return result
        }
        fun arrayDocument(): Any? { val result = value(0, true); done(); return result }
        private fun value(depth: Int, keep: Boolean): Any? {
            require(depth <= 24) { "参考配置嵌套过深" }; whitespace(); require(pos < input.length)
            return when (input[pos]) {
                '"' -> string().takeIf { keep }
                '[' -> {
                    pos++; val list = if (keep) mutableListOf<Any?>() else null
                    if (!take(']')) { do { val item = value(depth + 1, keep); list?.add(item) } while (take(',')); need(']') }
                    list
                }
                '{' -> { pos++; if (!take('}')) { do { string(); need(':'); value(depth + 1, false) } while (take(',')); need('}') }; null }
                't', 'f', 'n' -> { val literal = when (input[pos]) { 't' -> "true"; 'f' -> "false"; else -> "null" }; require(input.startsWith(literal, pos)); pos += literal.length; null }
                else -> {
                    val start = pos
                    if (pos < input.length && input[pos] == '-') pos++
                    require(pos < input.length && input[pos] in '0'..'9')
                    if (input[pos] == '0') pos++ else while (pos < input.length && input[pos] in '0'..'9') pos++
                    if (pos < input.length && input[pos] == '.') { pos++; val p = pos; while (pos < input.length && input[pos] in '0'..'9') pos++; require(pos > p) }
                    if (pos < input.length && input[pos] in "eE") { pos++; if (pos < input.length && input[pos] in "+-") pos++; val p = pos; while (pos < input.length && input[pos] in '0'..'9') pos++; require(pos > p) }
                    val n = input.substring(start, pos).toDouble(); require(n.isFinite()); n.takeIf { keep }
                }
            }
        }
        private fun string(): String {
            need('"'); val result = StringBuilder()
            while (pos < input.length) {
                val c = input[pos++]
                if (c == '"') return result.toString()
                require(c >= ' ') { "JSON 字符串无效" }
                if (c != '\\') result.append(c) else {
                    require(pos < input.length)
                    when (val escaped = input[pos++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b'); 'f' -> result.append('\u000c'); 'n' -> result.append('\n'); 'r' -> result.append('\r'); 't' -> result.append('\t')
                        'u' -> { require(pos + 4 <= input.length); result.append(input.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                        else -> error("JSON 转义无效")
                    }
                }
            }
            error("JSON 字符串未结束")
        }
    }
}
