package com.viniarskii.jbtest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun MainScreen(
    viewModel: MainScreenViewModel = viewModel { MainScreenViewModel() },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            text = "Input",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        TextField(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.7f),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            value = uiState.input,
            onValueChange = viewModel::onInputChanged,
        )
        HorizontalDivider()
        Text(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            text = "Output",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        HorizontalDivider()
        Box(modifier = Modifier
            .fillMaxWidth()
            .weight(0.3f)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = uiState.output) { item ->
                    MessageItem(item)
                }
            }
            if (uiState.isInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp),
                    color = ProgressIndicatorDefaults.circularColor.copy(alpha = 0.3f)
                )
            }
        }
    }
}

@Composable
fun MessageItem(message: MainScreenViewModel.UiMessage) {
    Text(
        text = message.message,
        style = LocalTextStyle.current.copy(fontWeight =
            when (message.type) {
                MainScreenViewModel.UiMessageType.OUTPUT -> FontWeight.Normal
                MainScreenViewModel.UiMessageType.ERROR,
                MainScreenViewModel.UiMessageType.CANCELLED,
                MainScreenViewModel.UiMessageType.COMPLETED -> FontWeight.Bold
            }
        ),
        fontFamily = FontFamily.Monospace,
    )
}
