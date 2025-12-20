package com.viniarskii.jbtest

enum class TokenType {
    T_VAR, T_OUT, T_PRINT, T_MAP, T_REDUCE,
    T_ARROW, T_ASSIGN,
    PLUS, MINUS, MULTIPLY, DIVIDE, POWER,
    LPAREN, RPAREN, LBRACE, RBRACE, COMMA,
    T_NUMBER, T_IDENTIFIER, T_STRING_LITERAL,
    WHITESPACE, ERROR
}

data class Token(val type: TokenType, val value: String)

class Lexer(private val input: String) {
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

        // Literals and Identifiers
        // FLOAT and INT combined into T_NUMBER
        Regex("""^(\d+\.\d*|\d*\.\d+)""") to TokenType.T_NUMBER,
        Regex("""^\d+""") to TokenType.T_NUMBER,
        Regex("""^[a-zA-Z_][a-zA-Z0-9_]*""") to TokenType.T_IDENTIFIER,
        Regex("""^"([^\\"]|\\.)*"""") to TokenType.T_STRING_LITERAL
    )

    fun tokenize(): List<Token> {
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
