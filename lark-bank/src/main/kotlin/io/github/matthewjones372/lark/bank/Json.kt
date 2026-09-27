package io.github.matthewjones372.lark.bank

/** JSON by hand, for the few flat shapes the bank sends and reads (spec 0094): no library for a dozen fields. */
internal object Json {
    /** An object whose values are strings, numbers, booleans, maps like [fields], or lists of those. */
    fun write(fields: Map<String, Any>): String =
        fields.entries.joinToString(",", "{", "}") { (name, value) -> "${string(name)}:${value(value)}" }

    private fun value(value: Any?): String = when (value) {
        is String -> string(value)
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> "${string(k.toString())}:${value(v)}" }
        is List<*> -> value.joinToString(",", "[", "]", transform = ::value)
        else -> error("no JSON is written for $value")
    }

    private fun string(text: String): String = text.fold(StringBuilder("\"")) { out, c ->
        when {
            c == '"' || c == '\\' -> out.append('\\').append(c)
            c < ' ' -> out.append("\\u").append(c.code.toString(radix = 16).padStart(HEX, '0'))
            else -> out.append(c)
        }
    }.append('"').toString()

    /** A flat object's fields, a string's unescaped and any other value as written; null when [text] is not one. */
    fun read(text: String): Map<String, String>? = Reading(text.trim()).obj()

    private class Reading(private val text: String) {
        private var at = 0

        fun obj(): Map<String, String>? {
            if (!take('{')) return null
            val fields = LinkedHashMap<String, String>()
            if (take('}')) return fields.takeIf { at == text.length }
            do {
                val name = string() ?: return null
                if (!take(':')) return null
                fields[name] = (if (peek() == '"') string() else literal()) ?: return null
            } while (take(','))
            return fields.takeIf { take('}') && at == text.length }
        }

        private fun string(): String? {
            if (!take('"')) return null
            val out = StringBuilder()
            while (at < text.length && text[at] != '"') {
                val c = text[at++]
                if (c != '\\') {
                    out.append(c)
                } else {
                    out.append(escaped() ?: return null)
                }
            }
            return out.toString().takeIf { take('"') }
        }

        private fun escaped(): Char? = when (val c = text.getOrNull(at++)) {
            'n' -> '\n'

            't' -> '\t'

            'r' -> '\r'

            'u' -> text.substring(at, minOf(at + HEX, text.length)).toIntOrNull(radix = 16)?.toChar()
                ?.also { at += HEX }

            '"', '\\', '/' -> c

            else -> null
        }

        private fun literal(): String? {
            val start = at
            while (at < text.length && text[at] !in ",}" && !text[at].isWhitespace()) at++
            return text.substring(start, at).takeIf {
                it.isNotEmpty() &&
                    it.all { c -> c.isLetterOrDigit() || c in "-+." }
            }
        }

        private fun peek(): Char? {
            while (at < text.length && text[at].isWhitespace()) at++
            return text.getOrNull(at)
        }

        private fun take(c: Char): Boolean = (peek() == c).also { if (it) at++ }
    }

    private const val HEX = 4
}
