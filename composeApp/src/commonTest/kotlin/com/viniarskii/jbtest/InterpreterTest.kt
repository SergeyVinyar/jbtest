package com.viniarskii.jbtest

import androidx.compose.ui.text.AnnotatedString
import com.viniarskii.jbtest.interpreter.InterpreterEvent
import com.viniarskii.jbtest.interpreter.InterpreterImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.reduce
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals

class InterpreterTest {

    @Test
    fun arithmeticPrecedence() = doTest(
        input = """
            out 1 + 2 * 3 ^ 2
        """,
        expectedOutput = """
            19.0
        """
    )

    @Test
    fun arithmeticUnaryOperators() = doTest(
        input = """
            var x = -3
            out 1 - (-2) * (-x) ^ 2
        """,
        expectedOutput = """
            19.0
        """
    )

    @Test
    fun parenthesesAndFloatingPoint() {
        doTest(
            input = """
                out (1.5 + 2.5) * 2
            """,
            expectedOutput = """
                8.0
            """
        )
    }

    @Test
    fun simpleSequence() {
        doTest(
            input = """
                out {1, 4}
            """,
            expectedOutput = """
                {1, 4}
            """
        )
    }

    @Test
    fun sequenceBoundError() {
        doTest(
            input = """
                out {5, 2}
            """,
            expectedOutput = """
                Sequence {5, 2} has end bound < start bound
            """
        )
    }

    @Test
    fun simpleMap() {
        doTest(
            input = """
                out map({1, 3}, i -> i^2)
            """,
            expectedOutput = """
                {1, 9}
            """
        )
    }

    @Test
    fun simpleReductionMultiplication() {
        doTest(
            input = """
                out reduce({2, 4}, 1, x y -> x * y)
            """,
            expectedOutput = """
                144.0
            """
        )
    }

    @Test
    fun simpleReductionSummation() {
        doTest(
            input = """
                out reduce({5, 7}, 0, a b -> a + b)
            """,
            expectedOutput = """
                29.0
            """
        )
    }

    @Test
    fun chainedOperations() {
        doTest(
            input = """
                out reduce(map({1, 3}, i -> i * 2), 0, x y -> x + y)
            """,
            expectedOutput = """
                34.0
            """
        )
    }

    @Test
    fun nestedMaps() {
        doTest(
            input = """
                out map(map({1, 2}, x -> x + 1), y -> y * 10)
            """,
            expectedOutput = """
                {20, 30}
            """
        )
    }

    @Test
    fun precedenceInLambda() {
        doTest(
            input = """
                out map({1, 2}, i -> i + 5 * 2)
            """,
            expectedOutput = """
                {11, 12}
            """
        )
    }

    @Test
    fun variableShadowing() {
        doTest(
            input = """
                var x = 10
                out map({1, 2}, x -> x + 1)
                out x
            """,
            expectedOutput = """
                {2, 3}
                10.0
            """
        )
    }

    // Fails. I think, the divide and conquer approach doesn't work for cases where
    // parts cannot be merged by simply invoking the lambda over left and right results.
//    @Test
//    fun nestedReductions() {
//        doTest(
//            input = """
//                out reduce({1, 2}, 0, a b -> a + reduce({1, b}, 0, x y -> x + y))
//            """,
//            expectedOutput = """
//                4.0
//            """
//        )
//    }

    @Test
    fun hugeReduction() {
        doTest(
            input = """
                out reduce({1, 1000000}, 0, a b -> a + b)
            """,
            expectedOutput = """
                1.0E12
            """
        )
    }

    @Test
    fun reductionNeutralElementAsVariable() {
        doTest(
            input = """
                var neutral = 0
                out reduce({1, 3}, neutral, x y -> x + y)
            """,
            expectedOutput = """
                9.0
            """
        )
    }

    @Test
    fun variableInvisibleOutsideOfScope() {
        doTest(
            input = """
                out map({1, 2}, i -> i + 1)
                out i
            """,
            expectedOutput = """
                {2, 3}
                Variable i not found
            """
        )
    }

    @Test
    fun complexExpressions() {
        doTest(
            input = """
                var n = 3
                out reduce(map({1, n}, i -> i^2), 1, x y -> x * y)
            """,
            expectedOutput = """
                1.46313216E10
            """
        )
    }

    @Test
    fun sequenceWithVariables() {
        doTest(
            input = """
                var start = 1
                var end = 3
                out {start, end}
            """,
            expectedOutput = """
                {1, 3}
            """
        )
    }

    @Test
    fun chainedPowers() {
        doTest(
            input = """
                out 5 ^ 2 ^ 3
            """,
            expectedOutput = """
                390625.0
            """
        )
    }

    @Test
    fun chainedPowersWithParenthesis() {
        doTest(
            input = """
                out (5 ^ 2) ^ 3
            """,
            expectedOutput = """
                15625.0
            """
        )
    }

    // Fails because map always returns sequence which must have only integer values
//    @Test
//    fun piCalculation() {
//        doTest(
//            input = """
//                var n = 500
//                var sequence = map({0, n}, i -> (-1)^i / (2 * i + 1))
//                var pi = 4 * reduce(sequence, 0, x y -> x + y)
//                print "pi = "
//                out pi
//            """,
//            expectedOutput = """
//
//            """
//        )
//    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun doTest(
        input: String,
        expectedOutput: String,
    ) {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val interpreter = InterpreterImpl()
        runTest {
            coroutineScope {
                with(interpreter) {
                    interpret(AnnotatedString(input.trimIndent()))
                }
                val actualOutput = interpreter.events
                    .takeWhile { it !is InterpreterEvent.Completed && it !is InterpreterEvent.Cancelled }
                    .mapNotNull { event ->
                        when (event) {
                            is InterpreterEvent.Output -> event.message
                            is InterpreterEvent.Error -> event.message
                            else -> null
                        }
                    }
                    .reduce { accumulator, value -> "$accumulator\n$value" }
                assertEquals(expectedOutput.trimIndent(), actualOutput.trimIndent())
            }
        }
    }
}