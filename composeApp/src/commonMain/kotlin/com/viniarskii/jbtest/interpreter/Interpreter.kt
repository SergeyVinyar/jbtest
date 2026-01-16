package com.viniarskii.jbtest.interpreter

import androidx.compose.ui.text.AnnotatedString
import co.touchlab.stately.collections.ConcurrentMutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
                            is Value.Sequence -> result.elements.joinToString(
                                prefix = "[",
                                separator = ", ",
                                postfix = "]",
                                transform = { it.toString() }
                            )
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

            is Expression.UnaryMinus -> {
                when (val result = interpretExpression(expression.value, scopeVariables)) {
                    is Value.Number -> Value.Number(value = -result.value)
                    is Value.Sequence -> Value.Sequence(elements = result.elements.map { -it })
                    is Value.Str -> Value.Str(value = "-${result.value}")
                }
            }

            is Expression.Identifier -> {
                scopeVariables[expression.name]
                    ?: throw RuntimeException("Variable ${expression.name} not found")
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
                val startResultAsInt = startResult.value.toInt()
                val endResultAsInt = endResult.value.toInt()
                require(startResultAsInt <= endResultAsInt) {
                    "Sequence {$startResultAsInt, $endResultAsInt} has end bound < start bound"
                }
                // 1. Would concurrency here be a performance improvement? I doubt. Likely the opposite.
                // 2. Also not using DoubleArray because most likely, we'll be using these elements
                //    for map or reduce which box them again. Let them be boxed here.
                Value.Sequence(
                    elements = List(endResultAsInt - startResultAsInt + 1) { index ->
                        startResultAsInt.toDouble() + index
                    }
                )
            }

            is Expression.Map -> {
                val sequenceResult = interpretExpression(expression.sequence, scopeVariables)
                require(sequenceResult is Value.Sequence)
                val mappedElements = sequenceResult.elements.map { sourceElement ->
                    async {
                        val lambdaVariables: ConcurrentMutableMap<String, Value> = ConcurrentMutableMap()
                        lambdaVariables.putAll(scopeVariables)
                        lambdaVariables[expression.param] = Value.Number(sourceElement)
                        interpretExpression(expression.lambda, lambdaVariables)
                    }
                }.awaitAll().map {
                    require(it is Value.Number) { "$expression returned non-double element '$it'" }
                    it.value
                }
                Value.Sequence(elements = mappedElements)
            }

            is Expression.Reduce -> {
                val sequence = async { interpretExpression(expression.sequence, scopeVariables) }
                val neutral = async { interpretExpression(expression.neutral, scopeVariables) }
                val sequenceResult = sequence.await()
                require(sequenceResult is Value.Sequence)
                val neutralResult = neutral.await()
                require(neutralResult is Value.Number)
                if (expression.lambda.isAssociative) {
                    // Divide And Conquer approach
                    val elementsWithNeutralValue: List<Double> = ArrayList(sequenceResult.elements).apply {
                        add(0, neutralResult.value)
                    }
                    reduceRecursion(
                        elements = elementsWithNeutralValue,
                        startIndex = 0,
                        endIndex = elementsWithNeutralValue.lastIndex,
                        identifier1 = expression.identifier1,
                        identifier2 = expression.identifier2,
                        lambda = expression.lambda,
                        scopeVariables = scopeVariables
                    )
                } else {
                    // Non-concurrent approach
                    Value.Number(
                        value = sequenceResult.elements.fold(neutralResult.value) { acc, element ->
                            val lambdaVariables: ConcurrentMutableMap<String, Value> = ConcurrentMutableMap()
                            lambdaVariables.putAll(scopeVariables)
                            lambdaVariables[expression.identifier1] = Value.Number(acc)
                            lambdaVariables[expression.identifier2] = Value.Number(element)
                            val result = interpretExpression(expression.lambda, lambdaVariables)
                            require(result is Value.Number) {
                                "Lambda ${expression.lambda} returned non-double value '$result'"
                            }
                            result.value
                        }
                    )
                }
            }
        }
    }

    // Divide and Conquer logic.

    // so no risk of stack overflow.
    private suspend fun reduceRecursion(
        elements: List<Double>,
        startIndex: Int,
        endIndex: Int,
        identifier1: String,
        identifier2: String,
        lambda: Expression,
        scopeVariables: Map<String, Value>
    ): Value.Number = coroutineScope {
        require(endIndex >= startIndex)
        when (endIndex - startIndex) {
            0 -> Value.Number(value = elements[startIndex])
            1 -> {
                val result = interpretReduceLambda(
                    identifier1 = identifier1,
                    identifier2 = identifier2,
                    argumentValue1 = elements[startIndex],
                    argumentValue2 = elements[endIndex],
                    lambda = lambda,
                    scopeVariables = scopeVariables,
                )
                require(result is Value.Number)
                result
            }
            else -> {
                val middleIndex = startIndex + (endIndex - startIndex) / 2
                val left = async {
                    reduceRecursion(
                        elements = elements,
                        startIndex = startIndex,
                        endIndex = middleIndex,
                        identifier1 = identifier1,
                        identifier2 = identifier2,
                        lambda = lambda,
                        scopeVariables = scopeVariables
                    )
                }
                val right = async {
                    reduceRecursion(
                        elements = elements,
                        startIndex = middleIndex + 1,
                        endIndex = endIndex,
                        identifier1 = identifier1,
                        identifier2 = identifier2,
                        lambda = lambda,
                        scopeVariables = scopeVariables
                    )
                }
                val result = interpretReduceLambda(
                    identifier1 = identifier1,
                    identifier2 = identifier2,
                    argumentValue1 = left.await().value,
                    argumentValue2 = right.await().value,
                    lambda = lambda,
                    scopeVariables = scopeVariables,
                )
                require(result is Value.Number)
                result
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
        data class Sequence(val elements: List<Double>) : Value
    }
}
