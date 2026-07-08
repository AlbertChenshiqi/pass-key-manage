package com.passkey.vault.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.passkey.vault.VaultViewModel
import com.passkey.vault.data.VaultItem
import com.passkey.vault.data.VaultItemType
import com.passkey.vault.ui.user.UserCenterScreen
import com.passkey.vault.ui.vault.ItemFormScreen
import com.passkey.vault.ui.vault.VaultListScreen

enum class MainTab { VAULT, USER }

@Composable
fun MainScreen(vm: VaultViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()
    val context = LocalContext.current
    var tab by remember { mutableStateOf(MainTab.VAULT) }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute?.startsWith("form/") != true

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it.text)
            vm.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                    NavigationBarItem(
                        selected = tab == MainTab.VAULT,
                        onClick = {
                            tab = MainTab.VAULT
                            navController.navigate("vault") {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        label = { Text("密码库") },
                    )
                    NavigationBarItem(
                        selected = tab == MainTab.USER,
                        onClick = {
                            tab = MainTab.USER
                            navController.navigate("user") {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.Person, contentDescription = null) },
                        label = { Text("用户中心") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "vault",
            modifier = Modifier.padding(padding),
        ) {
            composable("vault") {
                tab = MainTab.VAULT
                VaultListScreen(
                    vm = vm,
                    onAdd = { type -> navController.navigate("form/new/${type.name}") },
                    onEdit = { item -> navController.navigate("form/edit/${item.id}") },
                    onCopy = { text, label -> copyText(context, text, label) },
                    onOpenUserCenter = {
                        tab = MainTab.USER
                        navController.navigate("user") {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable("user") {
                tab = MainTab.USER
                UserCenterScreen(vm = vm)
            }
            composable(
                route = "form/{mode}/{arg}",
                arguments = listOf(
                    navArgument("mode") { type = NavType.StringType },
                    navArgument("arg") { type = NavType.StringType },
                ),
            ) { entry ->
                val mode = entry.arguments?.getString("mode") ?: "new"
                val arg = entry.arguments?.getString("arg") ?: VaultItemType.TOTP.name
                val items by vm.items.collectAsState()
                val editing = if (mode == "edit") items.find { it.id == arg } else null
                val type = editing?.resolvedType()
                    ?: runCatching { VaultItemType.valueOf(arg) }.getOrDefault(VaultItemType.TOTP)

                ItemFormScreen(
                    itemType = type,
                    editingItem = editing,
                    onSave = { item, isEdit ->
                        vm.upsertItem(item, isEdit)
                        navController.popBackStack()
                    },
                    onCancel = { navController.popBackStack() },
                )
            }
        }
    }
}

fun copyText(context: Context, text: String, label: String = "已复制") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
