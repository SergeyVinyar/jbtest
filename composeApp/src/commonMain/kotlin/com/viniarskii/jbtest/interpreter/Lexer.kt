package com.viniarskii.jbtest.interpreter

import androidx.compose.ui.text.AnnotatedString

enum class TokenType(val readableName: String) {
    T_VAR("<var>"),
    T_OUT("<out>"),
    T_PRINT("<print>"),
    T_MAP("<map>"),
    T_REDUCE("<reduce>"),
    T_ARROW("\"->\""),
    T_ASSIGN("\"=\""),
    PLUS("\"+\""),
    MINUS("\"-\""),
    MULTIPLY("\"*\""),
    DIVIDE("\"\\\""),
    POWER("\"^\""),
    LPAREN("\"(\""),
    RPAREN("\")\""),
    LBRACE("\"{\""),
    RBRACE("\"}\""),
    COMMA("\",\""),
    T_NUMBER("number"),
    T_IDENTIFIER("identifier"),
    T_STRING_LITERAL("string_literal"),
    WHITESPACE("whitespace"),
    ERROR("ERROR")
}

data class Token(val type: TokenType, val value: String)

interface Lexer {
    fun tokenize(input: AnnotatedString): List<Token>
}

/**
 * Non thread-safe
 */
class LexerImpl : Lexer {
    private var current = 0

    private val rules = listOf(
        // Whitespace (ignored in the final list)
        Regex("""^[ \t\r\n]+""") to TokenType.WHITESPACE,

        // Keywords
        Regex("""^var\b""") to TokenType.T_VAR,
        Regex("""^out\b""") to TokenType.T_OUT,
        Regex("""^print\b""") to TokenType.T_PRINT,
        Regex("""^map\b""") to TokenType.T_MAP,
        Regex("""^reduce\b""") to TokenType.T_REDUCE,

        // Multi-char Operators
        Regex("""^->""") to TokenType.T_ARROW,
        Regex("""^=""") to TokenType.T_ASSIGN,

        // Literals and Identifiers
        // FLOAT and INT combined into T_NUMBER
        Regex("""^([-]?\d+\.\d*|\d*\.\d+)""") to TokenType.T_NUMBER,
        Regex("""^[-]?\d+""") to TokenType.T_NUMBER,
        Regex("""^[a-zA-Z_][a-zA-Z0-9_]*""") to TokenType.T_IDENTIFIER,
        Regex("""^"([^\\"]|\\.)*"""") to TokenType.T_STRING_LITERAL,

        // Single-char Operators
        Regex("""^\+""") to TokenType.PLUS,
        Regex("""^-""") to TokenType.MINUS,
        Regex("""^\*""") to TokenType.MULTIPLY,
        Regex("""^/""") to TokenType.DIVIDE,
        Regex("""^\^""") to TokenType.POWER,
        Regex("""^\(""") to TokenType.LPAREN,
        Regex("""^\)""") to TokenType.RPAREN,
        Regex("""^\{""") to TokenType.LBRACE,
        Regex("""^\}""") to TokenType.RBRACE,
        Regex("""^,""") to TokenType.COMMA,
    )

    override fun tokenize(input: AnnotatedString): List<Token> {
        current = 0
        val tokens = mutableListOf<Token>()

        while (current < input.length) {
            val remainingInput = input.substring(current)
            var matched = false

            for ((regex, type) in rules) {
                val matchResult = regex.find(remainingInput)
                if (matchResult != null && matchResult.range.start == 0) {
                    val matchValue = matchResult.value

                    if (type != TokenType.WHITESPACE) {
                        tokens.add(Token(type, matchValue))
                    }

                    current += matchValue.length
                    matched = true
                    break
                }
            }

            if (!matched) {
                val unexpectedChar = input[current].toString()
                tokens.add(Token(TokenType.ERROR, unexpectedChar))
                current++
            }
        }

        return tokens
    }
}
