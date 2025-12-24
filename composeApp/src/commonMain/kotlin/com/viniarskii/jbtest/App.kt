package com.viniarskii.jbtest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.compose.lightScheme
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun App() {
    MaterialTheme(colorScheme = lightScheme) {
        Box(
            modifier = Modifier
                .windowInsetsPadding(
                    WindowInsets.safeContent
                        // Okay, let's ignore horizontal insets
                        // because it doesn't look nice.
                        // In reality, it need to be covered in design.
                        .only(WindowInsetsSides.Vertical)
                )
        ) {
            MainScreen()
        }
    }
}