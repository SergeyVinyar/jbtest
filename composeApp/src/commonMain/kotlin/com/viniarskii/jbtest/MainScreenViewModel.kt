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

    private val _interpretationQueue = MutableSharedFlow<AnnotatedString>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).also { flow ->
        viewModelScope.launch(Dispatchers.Default.limitedParallelism(1)) {
            flow.debounce(300.milliseconds).collect { input ->
                _currentInterpreterJob?.let { job ->
                    if (job.isActive) {
                        job.cancelAndJoin()
                    }
                }
                _currentInterpreterJob = launch {
                    interpreter.interpret(input)
                }
            }
        }
    }

    init {
        viewModelScope.launch {
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
        val isInProgress: Boolean = false,
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
