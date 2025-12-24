package com.viniarskii.jbtest.interpreter

import androidx.compose.ui.text.AnnotatedString
import co.touchlab.stately.collections.ConcurrentMutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlin.String
import kotlin.math.pow

/**
 * Interpreter events.
 * UI shows them as output as soon as it gets a new event.
 */
sealed interface InterpreterEvent {

    /**
     * Interpretation started
     */
    object Started : InterpreterEvent

    /**
     * Interpretation completed
     */
    object Completed : InterpreterEvent

    /**
     * Interpretation cancelled (actually, a coroutine is cancelled)
     */
    object Cancelled : InterpreterEvent

    /**
     * New message to be added to output
     */
    data class Output(val message: String) : InterpreterEvent

    /**
     * New error to be added to output
     */
    data class Error(val message: String) : InterpreterEvent
}

/**
 * Interpreter.
 *
 * Made as interface just to reflect the need to use DI and unit-testing for every class.
 * But we don't have DI in this project.
 */
interface Interpreter {

    val events: SharedFlow<InterpreterEvent>

    suspend fun interpret(input: AnnotatedString)
}

class InterpreterImpl(
    // Lexer and parser are not thread-safe, so we create new instances every time
    // as a fool-proof against bugs in multi-threading on a view model's side.
    private val lexerProvider: () -> Lexer = { LexerImpl() }, // No DI for simplicity
    private val parserProvider: () -> Parser = { ParserImpl() },
) : Interpreter {

    // [replay] is for unit-tests. In reality, need to think better.
    private val _events = MutableSharedFlow<InterpreterEvent>(replay = 100)
    override val events: SharedFlow<InterpreterEvent> = _events.asSharedFlow()

    private suspend fun onEvent(event: InterpreterEvent) {
        _events.emit(event)
    }

    // [AnnotatedString] instead of [String] to prepare for syntax/bugs highlighting
    // of the source code in the future.
    // Doesn't matter here (it's more for the view model) but let's have same types everywhere
    // and avoid any casting AnnotatedString <-> String.
    override suspend fun interpret(input: AnnotatedString) = withContext(Dispatchers.Default) {
        InterpreterRun(
            lexer = lexerProvider(),
            parser = parserProvider(),
            onEvent = ::onEvent,
        ).interpret(input)
    }
}

private class InterpreterRun(
    private val lexer: Lexer,
    private val parser: Parser,
    private val onEvent: suspend (InterpreterEvent) -> Unit,
) {
    /**
     * Global variables (that are introduced on a statement level).
     * All expressions have access to them as soon as they are defined.
     */
    private val globalVariables: ConcurrentMutableMap<String, Value> = ConcurrentMutableMap()

    suspend fun interpret(input: AnnotatedString) {
        onEvent(InterpreterEvent.Started)
        try {
            val tokens = lexer.tokenize(input)
            val statements = parser.parse(tokens)
            interpretStatements(statements)
            onEvent(InterpreterEvent.Completed)
        } catch (_: CancellationException) {
            withContext(NonCancellable) {
                onEvent(InterpreterEvent.Cancelled)
            }
        } catch (e: Exception) {
            withContext(NonCancellable) {
                onEvent(InterpreterEvent.Error(message = e.message ?: "Unknown error"))
                onEvent(InterpreterEvent.Completed)
            }
        }
    }

    suspend fun interpretStatements(statements: Collection<Statement>) {
        statements.forEach { statement ->
            interpretStatement(statement)
        }
    }

    private suspend fun interpretStatement(statement: Statement) {
        when (statement) {
            is Statement.Var -> {
                val value = interpretExpression(statement.expression, globalVariables)
                globalVariables[statement.identifier] = value
            }
            is Statement.Out -> {
                val result = interpretExpression(statement.expression, globalVariables)
                onEvent(
                    InterpreterEvent.Output(
                        message = when (result) {
                            is Value.Number -> result.value.toString()
                            is Value.Str -> result.value
                            is Value.Sequence -> "{${result.start}, ${result.end}}"
                        }
                    )
                )
            }
            is Statement.Print -> {
                onEvent(
                    InterpreterEvent.Output(
                        message = statement.text
                    )
                )
            }
        }
    }

    private suspend fun interpretExpression(
        expression: Expression,
        scopeVariables: Map<String, Value>
    ): Value = coroutineScope {
        when (expression) {
            is Expression.Number -> {
                Value.Number(expression.value)
            }

            is Expression.Identifier -> {
                var result = scopeVariables[expression.name]
                    ?: throw RuntimeException("Variable ${expression.name} not found")
                if (expression.unaryMinus) {
                    result = when (result) {
                        is Value.Number -> result.copy(value = -result.value)
                        // For uniformity only as we don't support string literals as expressions
                        is Value.Str -> result.copy(value = "-${result.value})")
                        // Do we need to exchange start and end? Let's assume, yes.
                        // Otherwise, it'll almost always be generating an invalid sequence (start < end).
                        is Value.Sequence -> result.copy(start = -result.end, end = -result.start)
                    }
                }
                result
            }

            is Expression.BinaryOp -> {
                val left = async { interpretExpression(expression.left, scopeVariables) }
                val right = async { interpretExpression(expression.right, scopeVariables) }
                val leftResult = left.await()
                require(leftResult is Value.Number)
                val rightResult = right.await()
                require(rightResult is Value.Number)
                val result = when (expression.op) {
                    TokenType.PLUS -> leftResult.value + rightResult.value
                    TokenType.MINUS -> leftResult.value - rightResult.value
                    TokenType.MULTIPLY -> leftResult.value * rightResult.value
                    TokenType.DIVIDE -> leftResult.value / rightResult.value
                    TokenType.POWER -> leftResult.value.pow(rightResult.value)
                    else -> .0
                }
                Value.Number(result)
            }

            is Expression.Sequence -> {
                val start = async { interpretExpression(expression.start, scopeVariables) }
                val end = async { interpretExpression(expression.end, scopeVariables) }
                val startResult = start.await()
                require(startResult is Value.Number)
                val endResult = end.await()
                require(endResult is Value.Number)
                require(startResult.value % 1 == 0.0 && endResult.value % 1 == 0.0) {
                    "Sequence {${startResult.value}, ${endResult.value}} has non-integer bound(s)"
                }
                require(startResult.value.toInt() <= endResult.value.toInt()) {
                    "Sequence {${startResult.value.toInt()}, ${endResult.value.toInt()}} has end bound < start bound"
                }
                Value.Sequence(start = startResult.value.toInt(), end = endResult.value.toInt())
            }

            is Expression.Map -> {
                // For [map], we are fine to calculate just new start and end bounds
                val sequenceResult = interpretExpression(expression.sequence, scopeVariables)
                require(sequenceResult is Value.Sequence)
                val start = async {
                    val lambdaVariables: ConcurrentMutableMap<String, Value> = ConcurrentMutableMap()
                    lambdaVariables.putAll(scopeVariables)
                    lambdaVariables[expression.param] = Value.Number(sequenceResult.start.toDouble())
                    interpretExpression(expression.lambda, lambdaVariables)
                }
                val end = async {
                    val lambdaVariables: ConcurrentMutableMap<String, Value> = ConcurrentMutableMap()
                    lambdaVariables.putAll(scopeVariables)
                    lambdaVariables[expression.param] = Value.Number(sequenceResult.end.toDouble())
                    interpretExpression(expression.lambda, lambdaVariables)
                }
                val startResult = start.await()
                require(startResult is Value.Number)
                val endResult = end.await()
                require(endResult is Value.Number)
                require(startResult.value % 1 == 0.0 && endResult.value % 1 == 0.0) {
                    "Sequence {${startResult.value}, ${endResult.value}} has non-integer bound(s)"
                }
                require(startResult.value <= endResult.value) {
                    "Sequence {${startResult.value.toInt()}, ${endResult.value.toInt()}} has end bound < start bound"
                }
                Value.Sequence(start = startResult.value.toInt(), end = endResult.value.toInt())
            }

            is Expression.Reduce -> {
                // Actually, cool but not very working in real life solution.
                // I use here a Divide And Conquer approach:
                // all lambda invocations are divided by halves
                // (+ one with a neutral value) and then are joined.
                // It allows us to significantly reduce the number of steps (O(n) -> O(log(n))).
                // For example, map({1, 1000000}, 0, x y -> x + y) requires ~20 steps
                // instead of ~1000000.
                // It's clearly visible in unit tests (9 seconds in a unit test, 1 second in an app).
                // The problem is that if lambda does something more complicated that just
                // a simple +/-, joining gives wrong result.
                // We can discuss it.
                val sequence = async { interpretExpression(expression.sequence, scopeVariables) }
                val neutral = async { interpretExpression(expression.neutral, scopeVariables) }
                val sequenceResult = sequence.await()
                require(sequenceResult is Value.Sequence)
                require(sequenceResult.start <= sequenceResult.end)
                val neutralResult = neutral.await()
                require(neutralResult is Value.Number)
                // First step (with neutral)
                val left = async {
                    interpretReduceLambda(
                        identifier1 = expression.identifier1,
                        identifier2 = expression.identifier2,
                        argumentValue1 = neutralResult.value,
                        argumentValue2 = sequenceResult.start.toDouble(),
                        lambda = expression.lambda,
                        scopeVariables = scopeVariables,
                    )
                }
                // Second step (dividing by halves and joining results for all sequence elements)
                val right = async {
                    reduceRecursion(
                        start = sequenceResult.start,
                        end = sequenceResult.end,
                        identifier1 = expression.identifier1,
                        identifier2 = expression.identifier2,
                        lambda = expression.lambda,
                        scopeVariables = scopeVariables
                    )
                }
                val leftResult = left.await()
                require(leftResult is Value.Number)
                val rightResult = right.await()
                // Third step (joining neutral value result and results for all sequence elements)
                if (rightResult != null) {
                    interpretReduceLambda(
                        identifier1 = expression.identifier1,
                        identifier2 = expression.identifier2,
                        argumentValue1 = leftResult.value,
                        argumentValue2 = rightResult.value,
                        lambda = expression.lambda,
                        scopeVariables = scopeVariables,
                    )
                } else {
                    leftResult
                }
            }
        }
    }

    // Divide and Conquer logic.
    // Recursion is not a problem here because it uses suspend functions,
    // so no risk of stack overflow.
    private suspend fun reduceRecursion(
        start: Int,
        end: Int,
        identifier1: String,
        identifier2: String,
        lambda: Expression,
        scopeVariables: Map<String, Value>
    ): Value.Number? = coroutineScope {
        require(end >= start)
        when (end - start) {
            0 -> null
            1 -> {
                val result = interpretReduceLambda(
                    identifier1 = identifier1,
                    identifier2 = identifier2,
                    argumentValue1 = start.toDouble(),
                    argumentValue2 = end.toDouble(),
                    lambda = lambda,
                    scopeVariables = scopeVariables,
                )
                require(result is Value.Number)
                result
            }
            else -> {
                val middle = (start + end) / 2
                val left = async {
                    reduceRecursion(
                        start = start,
                        end = middle,
                        identifier1 = identifier1,
                        identifier2 = identifier2,
                        lambda = lambda,
                        scopeVariables = scopeVariables
                    )
                }
                val right = async {
                    reduceRecursion(
                        start = middle,
                        end = end,
                        identifier1 = identifier1,
                        identifier2 = identifier2,
                        lambda = lambda,
                        scopeVariables = scopeVariables
                    )
                }
                val leftResult = left.await()
                val rightResult = right.await()
                if (leftResult != null && rightResult != null) {
                    val result = interpretReduceLambda(
                        identifier1 = identifier1,
                        identifier2 = identifier2,
                        argumentValue1 = leftResult.value,
                        argumentValue2 = rightResult.value,
                        lambda = lambda,
                        scopeVariables = scopeVariables,
                    )
                    require(result is Value.Number)
                    result
                } else {
                    leftResult ?: rightResult
                }
            }
        }
    }

    private suspend fun interpretReduceLambda(
        identifier1: String,
        identifier2: String,
        argumentValue1: Double,
        argumentValue2: Double,
        lambda: Expression,
        scopeVariables: Map<String, Value>
    ): Value {
        val lambdaVariables: ConcurrentMutableMap<String, Value> = ConcurrentMutableMap()
        lambdaVariables.putAll(scopeVariables)
        lambdaVariables[identifier1] = Value.Number(argumentValue1)
        lambdaVariables[identifier2] = Value.Number(argumentValue2)
        val result = interpretExpression(lambda, lambdaVariables)
        require(result is Value.Number)
        return result
    }

    /**
     * Node interpretation result
     */
    sealed interface Value {
        data class Number(val value: Double) : Value
        data class Str(val value: String) : Value
        data class Sequence(val start: Int, val end: Int) : Value
    }
}
