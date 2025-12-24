package com.viniarskii.jbtest

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viniarskii.jbtest.interpreter.Interpreter
import com.viniarskii.jbtest.interpreter.InterpreterEvent
import com.viniarskii.jbtest.interpreter.InterpreterImpl
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalAtomicApi::class, FlowPreview::class)
class MainScreenViewModel(
    private val interpreter: Interpreter = InterpreterImpl(), // No DI for simplicity
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    @Volatile
    private var _currentInterpreterJob: Job? = null

    /**
     * Queue for interpretation requests.
     *
     * For debouncing and making sure we don't get a mixture of outputs from
     * different versions of inputs.
     */
    private val _interpretationQueue = MutableSharedFlow<AnnotatedString>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).also { flow ->
        // [viewModelScope] for cancelling interpretation if user navigates away of this screen.
        viewModelScope.launch(Dispatchers.Default.limitedParallelism(1)) {
            flow.debounce(300.milliseconds).collect { input ->
                // We don't need to use any special synchronisation here because of [limitedParallelism(1)].
                // But we still need to mark [_currentInterpreterJob] as [volatile] because
                // [limitedParallelism(1)] means "no more than one thread runs this block at any given moment of time",
                // but these can still be different system threads (even on every suspend-function
                // invocation the thread may be changed).
                _currentInterpreterJob?.let { job ->
                    if (job.isActive) {
                        job.cancelAndJoin()
                    }
                }
                _currentInterpreterJob = launch {
                    interpreter.interpret(input) // [interpret] switches to [Dispatchers.Default]
                }
            }
        }
    }

    init {
        // This [collect] just emits new UI states. It doesn't change UI directly so
        // using [Dispatchers.Default] is preferable.
        viewModelScope.launch(Dispatchers.Default) {
            interpreter.events.collect { event ->
                newState {
                    when (event) {
                        is InterpreterEvent.Started -> {
                            copy(
                                isInProgress = true,
                                output = (output as PersistentList).clear()
                            )
                        }

                        is InterpreterEvent.Completed,
                        is InterpreterEvent.Cancelled -> {
                            copy(
                                isInProgress = false,
                            )
                        }

                        is InterpreterEvent.Output -> {
                            copy(
                                output = (output as PersistentList).add(
                                    UiMessage(
                                        message = event.message,
                                        type = UiMessageType.OUTPUT,
                                    )
                                )
                            )
                        }

                        is InterpreterEvent.Error -> {
                            copy(
                                output = (output as PersistentList).add(
                                    UiMessage(
                                        message = event.message,
                                        type = UiMessageType.ERROR,
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    fun onInputChanged(newTextFieldValue: TextFieldValue) {
        // [TextFieldValue] also contains the cursor position +
        // an internal [AnnotatedString] that may have changed because of (not implemented)
        // syntax highlighting. We don't need to trigger interpretation is such cases.
        val needReinterpretation = newTextFieldValue.text != uiState.value.input.text
        newState {
            copy(input = newTextFieldValue)
        }
        if (needReinterpretation) {
            _interpretationQueue.tryEmit(newTextFieldValue.annotatedString)
        }
    }

    private inline fun newState(block: UiState.() -> UiState) {
        _uiState.update { oldState ->
            oldState.block()
        }
    }

    @Immutable
    data class UiState(
        val input: TextFieldValue = TextFieldValue(),
        val output: ImmutableList<UiMessage> = persistentListOf(),
        val isInProgress: Boolean = false, // A small circular progress widget
    )

    @Immutable
    data class UiMessage(
        val message: String,
        val type: UiMessageType,
    )

    enum class UiMessageType {
        OUTPUT, ERROR, COMPLETED, CANCELLED;
    }
}
