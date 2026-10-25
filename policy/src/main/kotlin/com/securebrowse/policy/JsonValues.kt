package com.securebrowse.policy

/** Minimal JSON reader and writer for the allowlist document. No extra libraries. */
internal object JsonValues {
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWs()
        if (!parser.eof()) throw PolicyFormatException("Unexpected trailing data in allowlist file.")
        return value
    }

    fun stringify(value: Any?): String = buildString { write(value) }

    private fun StringBuilder.write(value: Any?) {
        when (value) {
            null -> append("null")
            is Boolean -> append(if (value) "true" else "false")
            is Int -> append(value)
            is Long -> append(value)
            is String -> {
                append('"')
                value.forEach { ch ->
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
                    }
                }
                append('"')
            }
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    write(entry.key as String)
                    append(':')
                    write(entry.value)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    write(item)
                }
                append(']')
            }
            else -> throw PolicyFormatException("Cannot encode ${value::class.simpleName}.")
        }
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun eof(): Boolean = i >= s.length

        fun skipWs() {
            while (!eof() && s[i].isWhitespace()) i++
        }

        fun parseValue(): Any? {
            skipWs()
            if (eof()) throw PolicyFormatException("Allowlist file ended early.")
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = linkedMapOf<String, Any?>()
            skipWs()
            if (peek('}')) {
                i++
                return map
            }
            while (true) {
                skipWs()
                if (eof() || s[i] != '"') throw PolicyFormatException("Expected a name in the allowlist file.")
                val key = parseString()
                if (map.containsKey(key)) throw PolicyFormatException("Duplicate field “$key”.")
                skipWs()
                expect(':')
                map[key] = parseValue()
                skipWs()
                when {
                    peek(',') -> i++
                    peek('}') -> {
                        i++
                        return map
                    }
                    else -> throw PolicyFormatException("Expected a comma or end of object.")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skipWs()
            if (peek(']')) {
                i++
                return list
            }
            while (true) {
                list += parseValue()
                skipWs()
                when {
                    peek(',') -> i++
                    peek(']') -> {
                        i++
                        return list
                    }
                    else -> throw PolicyFormatException("Expected a comma or end of list.")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (!eof()) {
                val ch = s[i++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (eof()) throw PolicyFormatException("Broken text escape.")
                        when (val esc = s[i++]) {
                            '"', '\\', '/' -> out.append(esc)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw PolicyFormatException("Broken text escape.")
                                val hex = s.substring(i, i + 4)
                                i += 4
                                val code = hex.toIntOrNull(16) ?: throw PolicyFormatException("Broken text escape.")
                                out.append(code.toChar())
                            }
                            else -> throw PolicyFormatException("Broken text escape.")
                        }
                    }
                    else -> {
                        if (ch.code < 0x20) throw PolicyFormatException("Unescaped control character.")
                        out.append(ch)
                    }
                }
            }
            throw PolicyFormatException("Unclosed text.")
        }

        private fun parseNumber(): Any {
            val start = i
            if (peek('-')) i++
            if (eof() || !s[i].isDigit()) throw PolicyFormatException("Expected a number.")
            if (s[i] == '0') {
                i++
                if (!eof() && s[i].isDigit()) throw PolicyFormatException("Invalid number.")
            } else {
                while (!eof() && s[i].isDigit()) i++
            }
            if (!eof() && (s[i] == '.' || s[i] == 'e' || s[i] == 'E')) {
                throw PolicyFormatException("Only whole numbers are accepted.")
            }
            val text = s.substring(start, i)
            val asLong = text.toLongOrNull() ?: throw PolicyFormatException("Number is out of range.")
            return if (asLong in Int.MIN_VALUE..Int.MAX_VALUE) asLong.toInt() else asLong
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) throw PolicyFormatException("Invalid value.")
            i += word.length
            return value
        }

        private fun expect(ch: Char) {
            if (eof() || s[i] != ch) throw PolicyFormatException("Expected ‘$ch’.")
            i++
        }

        private fun peek(ch: Char): Boolean = !eof() && s[i] == ch
    }
}
