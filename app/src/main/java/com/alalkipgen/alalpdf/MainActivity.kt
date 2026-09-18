package com.alalkipgen.alalpdf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.alalkipgen.alalpdf.ui.theme.AlalPdfTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { AlalPdfTheme { LibraryPlaceholder() } } }
}

@Composable private fun LibraryPlaceholder() { Text("Alal PDF") }

@Preview(showBackground = true)
@Composable private fun PreviewLibrary() { AlalPdfTheme { LibraryPlaceholder() } }