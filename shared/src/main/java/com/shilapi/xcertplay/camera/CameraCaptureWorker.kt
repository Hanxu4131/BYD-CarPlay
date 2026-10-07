package com.shilapi.xcertplay.camera

/** app_process entry point only. The launcher must impose a hard external process deadline. */
object CameraCaptureWorker {
    internal data class Options(val source: BmmCameraCapture.Source, val policy: BmmCameraCapture.Policy, val timeoutMillis: Long)

    @JvmStatic fun main(args: Array<String>) {
        // Deliberately no host/UI integration and no persistent transport until its frame mapping is verified.
        val output = try {
            val options = parse(args)
            val result = BmmCameraCapture().metadataProbe(options.source, options.policy, options.timeoutMillis, 2)
            encode(result)
        } catch (_: IllegalArgumentException) {
            "{\"version\":1,\"outcome\":\"INVALID_ARGUMENTS\",\"frames\":[]}"
        } catch (failure: Throwable) {
            "{\"version\":1,\"outcome\":\"WORKER_FAILED\",\"errorType\":${quote(failure.javaClass.simpleName.take(80))},\"frames\":[]}"
        }
        println(output)
    }

    internal fun parse(args: Array<String>): Options {
        require(args.size in 6..8)
        val allowed = setOf("mode", "source-id", "callback-index", "layout", "format", "policy", "source-proof", "timeout-ms")
        val values = LinkedHashMap<String, String>()
        args.forEach { argument ->
            require(argument.startsWith("--") && argument.length <= 160)
            val pair = argument.drop(2).split('=', limit = 2)
            require(pair.size == 2 && pair[0] in allowed && pair[1].isNotEmpty() && values.put(pair[0], pair[1]) == null)
        }
        require(values["mode"] == null || values["mode"] == "metadata") { "Stream mode is not implemented" }
        fun required(key: String): String = requireNotNull(values[key])
        val id = required("source-id").toInt()
        val index = required("callback-index").toInt()
        val policy = BmmCameraCapture.Policy.valueOf(required("policy"))
        val proof = BmmCameraCapture.SourceProof.valueOf(required("source-proof"))
        val layoutText = required("layout")
        val formatText = required("format")
        val layout = if (layoutText == "unknown") {
            require(formatText == "unknown")
            null
        } else {
            val dimensions = layoutText.split(',')
            require(dimensions.size == 4)
            val numbers = dimensions.map { it.toInt() }
            BmmCameraCapture.FrameLayout(numbers[0], numbers[1], numbers[2], numbers[3], BmmCameraCapture.PixelFormat.valueOf(formatText))
        }
        val timeout = values["timeout-ms"]?.toLong() ?: 5_000L
        require(timeout in 5_000..8_000)
        return Options(BmmCameraCapture.Source(id, index, layout, proof, "Explicit helper arguments; owner must verify source provenance", false), policy, timeout)
    }

    internal fun encode(result: BmmCameraCapture.Result): String {
        // Fixed record count and field sizes. Neither pixel payload nor source configuration is serialized.
        val frames = result.frames.take(2).joinToString(",") { frame ->
            val layout = frame.declaredLayout
            val dimensions = if (layout == null) "null" else
                "{\"width\":${layout.width},\"height\":${layout.height},\"yStride\":${layout.yStride},\"uvStride\":${layout.uvStride},\"format\":${quote(layout.format.name)}}"
            "{\"byteLength\":${frame.byteLength},\"rawIntegers\":[${frame.rawIntegers.take(5).joinToString(",")}],\"rawTimestamp\":${frame.rawTimestamp},\"declaredLayout\":$dimensions}"
        }
        val cleanup = result.cleanupErrors.take(4).joinToString(",") { quote(it.take(80)) }
        // Detail messages can contain SDK internals; expose outcome and cleanup types rather than arbitrary exception text.
        return "{\"version\":1,\"outcome\":${quote(result.outcome.name)},\"legacyUsed\":${result.legacyUsed},\"frames\":[$frames],\"cleanupErrors\":[$cleanup]}"
    }

    private fun quote(value: String): String = "\"" + buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> if (character.code < 32) append("?") else append(character)
            }
        }
    } + "\""
}
