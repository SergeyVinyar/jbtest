package com.viniarskii.jbtest.interpreter

sealed interface Expression {

    data class Number(
        val value: Double
    ) : Expression

    data class Identifier(
        val name: String,
        val unaryMinus: Boolean,
    ) : Expression

    data class BinaryOp(
        val left: Expression,
        val op: TokenType,
        val right: Expression
    ) : Expression

    data class Sequence(
        val start: Expression,
        val end: Expression
    ) : Expression

    data class Map(
        val sequence: Expression,
        val param: String,
        val lambda: Expression
    ) : Expression

    data class Reduce(
        val sequence: Expression,
        val neutral: Expression,
        val identifier1: String,
        val identifier2: String,
        val lambda: Expression
    ) : Expression
}

sealed interface Statement {

    data class Var(
        val identifier: String,
        val expression: Expression
    ) : Statement

    data class Out(
        val expression: Expression
    ) : Statement

    data class Print(
        val text: String
    ) : Statement
}

interface Parser {
    fun parse(tokens: List<Token>): List<Statement>
}

/**
 * Converts a list of tokens into an Abstract Syntax Tree (AST).
 * Here we check for syntax correctness.
 *
 * Not thread-safe.
 */
class ParserImpl : Parser {
    private var current = 0
    private var tokens: List<Token> = listOf()

    private fun peek() = tokens.getOrNull(current)

    private fun consume() = tokens[current++]

    private fun expect(type: TokenType): Token {
        val t = peek() ?: error("Expected ${type.readableName}, but reached end of input")
        if (t.type != type) error("Expected ${type.readableName} but found ${t.type.readableName}")
        return consume()
    }

    override fun parse(tokens: List<Token>): List<Statement> {
        this.current = 0
        this.tokens = tokens
        return mutableListOf<Statement>().apply {
            while (current < tokens.size) {
                add(parseStatement())
            }
        }
    }

    private fun parseStatement(): Statement {
        val token = peek() ?: error("Unexpected end of input")
        return when (token.type) {
            TokenType.T_VAR -> {
                expect(TokenType.T_VAR)
                val identifier = expect(TokenType.T_IDENTIFIER).value
                expect(TokenType.T_ASSIGN)
                Statement.Var(identifier, parseAdditions())
            }

            TokenType.T_OUT -> {
                expect(TokenType.T_OUT)
                Statement.Out(parseAdditions())
            }

            TokenType.T_PRINT -> {
                expect(TokenType.T_PRINT)
                val text = consume().value
                Statement.Print(text.substring(1, text.length - 1))
            }

            else -> {
                // I'm always typing "val" instead of "var" automatically and struggling to understand
                // why it's not working
                if (token.type == TokenType.T_IDENTIFIER && token.value == "val") {
                    error("Expected statement, found ${token.type.readableName} \"${token.value}\". Did you mean \"var\"?")
                } else {
                    error("Expected statement, found ${token.type.readableName} \"${token.value}\"")
                }
            }
        }
    }

    // We go down the "tree" to the very bottom (additions -> multiplications -> powers)
    // and then start making nodes moving upward. So that we keep the required operations
    // precedence
    private fun parseAdditions(): Expression {
        var node = parseMultiplication()
        while (peek()?.type in listOf(TokenType.PLUS, TokenType.MINUS)) {
            val op = consume().type
            node = Expression.BinaryOp(node, op, parseMultiplication())
        }
        return node
    }

    private fun parseMultiplication(): Expression {
        var node = parsePower()
        while (peek()?.type in listOf(TokenType.MULTIPLY, TokenType.DIVIDE)) {
            val op = consume().type
            node = Expression.BinaryOp(node, op, parsePower())
        }
        return node
    }

    private fun parsePower(): Expression {
        var node = parsePrimary()
        if (peek()?.type == TokenType.POWER) {
            val op = consume().type
            node = Expression.BinaryOp(node, op, parsePower())
        }
        return node
    }

    private fun parsePrimary(): Expression {
        val token = peek() ?: error("Unexpected end of expression")
        return when (token.type) {
            TokenType.MINUS -> {
                consume()
                if (peek()?.type == TokenType.T_IDENTIFIER) {
                    Expression.Identifier(name = consume().value, unaryMinus = true)
                } else {
                    error("Unexpected token ${TokenType.MINUS.readableName} in expression")
                }
            }
            TokenType.T_NUMBER -> Expression.Number(consume().value.toDouble())
            TokenType.T_IDENTIFIER -> Expression.Identifier(name = consume().value, unaryMinus = false)
            TokenType.LPAREN -> {
                consume()
                val expression = parseAdditions()
                expect(TokenType.RPAREN)
                expression
            }
            TokenType.LBRACE -> parseSequence()
            TokenType.T_MAP -> parseMap()
            TokenType.T_REDUCE -> parseReduce()
            else -> error("Unexpected token ${token.type.readableName} in expression")
        }
    }

    private fun parseSequence(): Expression.Sequence {
        expect(TokenType.LBRACE)
        val start = parseAdditions()
        expect(TokenType.COMMA)
        val end = parseAdditions()
        expect(TokenType.RBRACE)
        return Expression.Sequence(start, end)
    }

    private fun parseMap(): Expression.Map {
        expect(TokenType.T_MAP)
        expect(TokenType.LPAREN)
        val sequence = parseAdditions()
        expect(TokenType.COMMA)
        val param = expect(TokenType.T_IDENTIFIER).value
        expect(TokenType.T_ARROW)
        val body = parseAdditions()
        expect(TokenType.RPAREN)
        return Expression.Map(sequence, param, body)
    }

    private fun parseReduce(): Expression.Reduce {
        expect(TokenType.T_REDUCE)
        expect(TokenType.LPAREN)
        val sequence = parseAdditions()
        expect(TokenType.COMMA)
        val neutral = parseAdditions()
        expect(TokenType.COMMA)
        val identifier1 = expect(TokenType.T_IDENTIFIER).value
        val identifier2 = expect(TokenType.T_IDENTIFIER).value
        expect(TokenType.T_ARROW)
        val body = parseAdditions()
        expect(TokenType.RPAREN)
        return Expression.Reduce(sequence, neutral, identifier1, identifier2, body)
    }
}
