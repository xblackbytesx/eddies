package com.eddies.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.eddies.app.feature.about.AboutScreen
import com.eddies.app.feature.accounts.AccountsScreen
import com.eddies.app.feature.addtransaction.AddTransactionScreen
import com.eddies.app.feature.assetdetail.AssetDetailScreen
import com.eddies.app.feature.backup.BackupScreen
import com.eddies.app.feature.insights.InsightsScreen
import com.eddies.app.feature.lock.LockScreen
import com.eddies.app.feature.markets.MarketsScreen
import com.eddies.app.feature.portfolio.PortfolioScreen
import com.eddies.app.feature.settings.SettingsScreen
import com.eddies.app.feature.transactions.TransactionsScreen
import kotlin.reflect.KClass

private data class Tab(
    val route: Any,
    val routeClass: KClass<*>,
    val label: String,
    val icon: ImageVector,
)

private val tabs = listOf(
    Tab(PortfolioRoute, PortfolioRoute::class, "Portfolio", Icons.Default.PieChart),
    Tab(InsightsRoute, InsightsRoute::class, "Insights", Icons.Default.Insights),
    Tab(MarketsRoute, MarketsRoute::class, "Markets", Icons.Default.Search),
    Tab(SettingsRoute, SettingsRoute::class, "Settings", Icons.Default.Settings),
)

/**
 * The one and only Scaffold in the app.
 *
 * Feature screens are pure content. Giving one its own Scaffold or TopAppBar
 * doubles the top inset, which is a bug that looks like a design choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EddiesNavHost(
    locked: Boolean,
    onUnlocked: () -> Unit,
) {
    if (locked) {
        LockScreen(onUnlocked = onUnlocked)
        return
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    val isTab = tabs.any { tab -> destination?.hasRoute(tab.routeClass) == true }
    val title = when {
        destination?.hasRoute(AssetDetailRoute::class) == true -> "Asset"
        destination?.hasRoute(AddTransactionRoute::class) == true -> "Transaction"
        destination?.hasRoute(AddPositionSearchRoute::class) == true -> "Add a position"
        destination?.hasRoute(BackupRoute::class) == true -> "Backup and restore"
        destination?.hasRoute(AccountsRoute::class) == true -> "Accounts"
        destination?.hasRoute(AboutRoute::class) == true -> "About"
        destination?.hasRoute(TransactionsRoute::class) == true -> "All transactions"
        else -> tabs.firstOrNull { destination?.hasRoute(it.routeClass) == true }?.label ?: "Eddies"
    }

    Scaffold(
        // The bars own their system insets; the content must not add them again.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                actions = {
                    if (destination?.hasRoute(PortfolioRoute::class) == true) {
                        IconButton(onClick = { navController.navigate(TransactionsRoute) }) {
                            Icon(
                                Icons.Default.ReceiptLong,
                                contentDescription = "All transactions",
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (!isTab) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (isTab) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = destination?.hasRoute(tab.routeClass) == true,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            // Only where adding a position is the obvious next action.
            if (destination?.hasRoute(PortfolioRoute::class) == true) {
                FloatingActionButton(onClick = { navController.navigate(AddPositionSearchRoute) }) {
                    Icon(Icons.Default.Add, contentDescription = "Add a position")
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            NavHost(navController = navController, startDestination = PortfolioRoute) {
                composable<PortfolioRoute> {
                    PortfolioScreen(
                        onOpenAsset = { navController.navigate(AssetDetailRoute(it)) },
                        onAddPosition = { navController.navigate(AddPositionSearchRoute) },
                        onOpenBackup = { navController.navigate(BackupRoute) },
                    )
                }
                composable<InsightsRoute> { InsightsScreen() }
                composable<MarketsRoute> {
                    MarketsScreen(onOpenAsset = { navController.navigate(AssetDetailRoute(it)) })
                }
                composable<SettingsRoute> {
                    SettingsScreen(
                        onOpenBackup = { navController.navigate(BackupRoute) },
                        onOpenTransactions = { navController.navigate(TransactionsRoute) },
                        onOpenAccounts = { navController.navigate(AccountsRoute) },
                        onOpenAbout = { navController.navigate(AboutRoute) },
                    )
                }
                composable<AssetDetailRoute> { entry ->
                    val assetId = entry.toRoute<AssetDetailRoute>().assetId
                    AssetDetailScreen(
                        onEditTransaction = { navController.navigate(AddTransactionRoute(assetId, it)) },
                        onAddTransaction = { navController.navigate(AddTransactionRoute(it)) },
                    )
                }
                composable<AddPositionSearchRoute> {
                    MarketsScreen(
                        onOpenAsset = { assetId ->
                            // Straight into the form: the user came here to add
                            // something, not to browse.
                            navController.navigate(AddTransactionRoute(assetId)) {
                                popUpTo(AddPositionSearchRoute) { inclusive = true }
                            }
                        },
                    )
                }
                composable<AddTransactionRoute> {
                    AddTransactionScreen(onDone = { navController.popBackStack() })
                }
                composable<TransactionsRoute> {
                    TransactionsScreen(
                        onEdit = { assetId, txId -> navController.navigate(AddTransactionRoute(assetId, txId)) },
                    )
                }
                composable<AccountsRoute> { AccountsScreen() }
                composable<BackupRoute> { BackupScreen() }
                composable<AboutRoute> { AboutScreen() }
            }
        }
    }
}
