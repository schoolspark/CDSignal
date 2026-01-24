package `in`.chinmoydas.signal.screens

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavController
import `in`.chinmoydas.signal.VoiceService
import `in`.chinmoydas.signal.viewmodel.WalkieViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    service: VoiceService?,
    viewModel: WalkieViewModel,
    myName: String,
    onPermissionsGranted: () -> Unit,
    onLogout: () -> Unit,
    onExit: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    // Live count of nearby devices for the Badge
    val nearbyCount = viewModel.nearbyUsers.size

    Scaffold(
        modifier = Modifier.navigationBarsPadding(),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "CD Signal",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary
                ),
                actions = {
                    IconButton(onClick = { navController.navigate("help") }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Help,
                            contentDescription = "Help",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.GraphicEq, contentDescription = "Radio") },
                    label = { Text("Radio") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.History, contentDescription = "History") },
                    label = { Text("History") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )

                NavigationBarItem(
                    icon = {
                        BadgedBox(
                            badge = {
                                if (nearbyCount > 0) {
                                    Badge { Text("$nearbyCount") }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Connect")
                        }
                    },
                    label = { Text("Connect") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )

                NavigationBarItem(
                    icon = { Icon(Icons.Default.Person, contentDescription = "My ID") },
                    label = { Text("My ID") },
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 }
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars
    ) { innerPadding ->

        val contentModifier = Modifier.padding(innerPadding)

        when (selectedTab) {
            0 -> TalkTab(
                modifier = contentModifier,
                viewModel = viewModel,
                service = service,
                onPermissionsGranted = onPermissionsGranted
            )
            1 -> HistoryTab(
                modifier = contentModifier,
                viewModel = viewModel,
                service = service // [FIXED] Now passing the required service parameter
            )
            2 -> ConnectTab(
                modifier = contentModifier,
                viewModel = viewModel,
                onConnected = { selectedTab = 0 }
            )
            3 -> ProfileTab(
                modifier = contentModifier,
                navController = navController,
                myName = myName,
                viewModel = viewModel,
                onLogout = onLogout,
                onExit = onExit
            )
        }
    }
}