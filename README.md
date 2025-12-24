This is a Kotlin Multiplatform project targeting Android, iOS, Desktop (JVM) that implements
an interpreter for a made-up simple programming language.
It supports numeric and string literals, global and local variables, sequences, and lambdas. 
It may also execute arithmetic operations, map / reduce logic for sequences, and print string literals.  

There are some limitations:
* sequences may only consist of integers;
* unary minuses work only with numeric literals and variables (of numeric and sequence types);
* [reduce] uses Divide-And-Conquer approach that gives wrong results for complicated lambda logic.

Interpretation logic consists of three steps:
* [Lexer] that converts an input string into a list of tokens (no syntax checks at this point);
* [Parser] that converts a list of tokens into an Abstract Syntax Tree (AST);
* [Interpreter] that calculate the result based on the given AST.

Technically, we can implement an interpreter that works directly with the list of tokens. But this
approach limits heavily the usage of multithreading whereas "parsing" the AST makes it possible
to interpret all the left/right sides of binary operations, and lambdas concurrently.

The lexer here uses regexps for processing input strings. Apparently, that's not the best approach
from the performance perspective. The production code should use formal language grammar
and tools for generating parsers/lexers like [Bison](https://www.gnu.org/software/bison/) 
and [Flex](https://github.com/westes/flex). But these tools generate not very human readable code.

Also as a follow-up, [Lexer], [Parser] and [Interpreter] can be linked via flows instead of lists/trees. 
It allows processing the input string lazily. For example, no need for [Lexer] to parse 
the input further if [Parser] has already found a syntax error.

A short video is [here](https://drive.google.com/file/d/1S-oBfrBYh2xlr20MUO5_OSrGuB_ZuBrc/view?usp=sharing).