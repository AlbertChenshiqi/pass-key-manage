package com.passkey.vault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.passkey.vault.ui.MainScreen
import com.passkey.vault.ui.theme.PassKeyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PassKeyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: VaultViewModel = viewModel()
                    MainScreen(vm)
                }
            }
        }
    }
}
