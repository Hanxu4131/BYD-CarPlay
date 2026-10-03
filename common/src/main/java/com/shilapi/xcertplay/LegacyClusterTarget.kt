package com.shilapi.xcertplay

/** Parse logical display IDs only. A physical address or a mode ID cannot authorize a launch. */
internal object LegacyClusterTarget {
    const val LAUNCH_TOKEN_EXTRA = "cluster_launch_token"
    private val packageName = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val launchToken = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** Numeric Intent flags work with the older vehicle am parser; do not wait for focus. */
    fun launchCommand(target: Int, applicationId: String, token: String): String {
        require(target > 0) { "The default display is not a cluster target" }
        require(packageName.matches(applicationId)) { "Invalid application package" }
        require(launchToken.matches(token)) { "Invalid launch token" }
        // NEW_TASK (0x10000000) | MULTIPLE_TASK (0x08000000): never move the centre task.
        return "am start-activity --display $target -f 0x18000000 " +
            "-n $applicationId/com.shilapi.xcertplay.ClusterMapActivity --es $LAUNCH_TOKEN_EXTRA $token"
    }

    fun ids(dump: String): Set<Int> {
        val logical = dump.substringAfter("Logical Displays:", dump)
        val result = linkedSetOf<Int>()
        val patterns = listOf(
            Regex("(?m)^\\s*Display (\\d+):\\s*$"),
            Regex("(?m)^\\s*mDisplayId=(\\d+)\\s*$"),
            Regex("DisplayInfo\\{[^\\n}]*\\bdisplayId[ =]+(\\d+)\\b"),
        )
        patterns.forEach { pattern -> pattern.findAll(logical).forEach { result.add(it.groupValues[1].toInt()) } }
        return result
    }

    /** Only per-display history records count. Resumed/global summaries cannot prove placement. */
    fun activityDisplay(dump: String, applicationId: String, taskId: Int): Int? {
        if (!packageName.matches(applicationId) || taskId < 0) return null
        val component = "$applicationId/com.shilapi.xcertplay.ClusterMapActivity"
        val displayHeader = Regex("^Display #(\\d+) \\(activities from top to bottom\\):\\s*$")
        val history = Regex("^\\s{4,}\\* Hist #\\d+: ActivityRecord\\{([^{}]+)\\}\\s*$")
        val recordComponent = Regex("\\bu\\d+\\s+(\\S+)")
        val recordTask = Regex("(?:^|\\s)t(\\d+)(?=\\s|$)")
        var display: Int? = null
        val matches = mutableListOf<Int>()
        for (line in dump.lineSequence()) {
            val heading = displayHeader.matchEntire(line)
            if (heading != null) {
                display = heading.groupValues[1].toIntOrNull()
                continue
            }
            // An unindented heading ends the display section, including the global footer.
            if ((line.isNotEmpty() && !line.first().isWhitespace()) ||
                line.trimStart().startsWith("ActivityStackSupervisor state:") ||
                line.trimStart().startsWith("ResumedActivity:")) display = null
            val current = display ?: continue
            val record = history.matchEntire(line)?.groupValues?.get(1) ?: continue
            val foundComponent = recordComponent.find(record)?.groupValues?.get(1)?.removeSuffix(",")
            val foundTask = recordTask.find(record)?.groupValues?.get(1)?.toIntOrNull()
            if (foundComponent == component && foundTask == taskId) matches.add(current)
        }
        return matches.singleOrNull()?.takeIf { it > 0 }
    }

    fun allowed(target: Int, dump: String): Boolean = target > 0 && target in ids(dump)
    fun accepts(actualDisplay: Int, expectedDisplay: Int, enabled: Boolean, streamActive: Boolean,
        hasContext: Boolean, tokenMatches: Boolean): Boolean =
        hasContext && enabled && streamActive && tokenMatches && actualDisplay > 0 && actualDisplay == expectedDisplay

    /** Shell acceptance is provisional; only the target Activity can confirm its actual display. */
    fun launched(output: String?): Boolean = output != null &&
        (Regex("(?m)^Status: ok\\s*$").containsMatchIn(output) ||
            Regex("(?m)^Starting: Intent \\{").containsMatchIn(output)) &&
        !Regex("(?i)error|exception|permission\\s*deni(?:al|ed)").containsMatchIn(output)
}
