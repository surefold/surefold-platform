package io.github.matthewjones372.surefold.jobs

private const val HEX = 16
private const val HEX_DIGITS = 4

/**
 * What the enqueuing thread carried, as the `jsonb` object of strings the `carried` column holds: written by hand,
 * since it is never more than a flat map of a trace's ids and a few annotations. Nothing carried is a null.
 */
internal object Carried {

    fun write(carried: Map<String, String>): String? =
        if (carried.isEmpty()) {
            null
        } else {
            carried.entries.joinToString(",", "{", "}") { (key, value) -> quoted(key) + ":" + quoted(value) }
        }

    private fun quoted(text: String): String = buildString(text.length + 2) {
        append('"')
        text.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u").append(c.code.toString(HEX).padStart(HEX_DIGITS, '0'))
                else -> append(c)
            }
        }
        append('"')
    }
}
