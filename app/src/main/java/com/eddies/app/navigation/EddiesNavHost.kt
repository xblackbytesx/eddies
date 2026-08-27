package com.eddies.app.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.eddies.app.feature.about.AboutScreen
import com.eddies.app.feature.accounts.AccountsScreen
import com.eddies.app.feature.merge.MergeDuplicatesScreen
import com.eddies.app.feature.settings.CryptoSettingsScreen
import com.eddies.app.feature.settings.DataSettingsScreen
import com.eddies.app.feature.settings.GeneralSettingsScreen
import com.eddies.app.feature.settings.PortfolioSettingsScreen
import com.eddies.app.feature.settings.PrivacySettingsScreen
import com.eddies.app.feature.settings.StockSettingsScreen
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
        destination?.hasRoute(MergeDuplicatesRoute::class) == true -> "Merge duplicates"
        destination?.hasRoute(GeneralSettingsRoute::class) == true -> "General"
        destination?.hasRoute(CryptoSettingsRoute::class) == true -> "Crypto"
        destination?.hasRoute(StockSettingsRoute::class) == true -> "Stocks"
        destination?.hasRoute(PortfolioSettingsRoute::class) == true -> "Portfolio"
        destination?.hasRoute(PrivacySettingsRoute::class) == true -> "Privacy and security"
        destination?.hasRoute(DataSettingsRoute::class) == true -> "Data management"
        else -> tabs.firstOrNull { destination?.hasRoute(it.routeClass) == true }?.label ?: "Eddies"
    }

    Box {
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
                                Icons.AutoMirrored.Filled.ReceiptLong,
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
                        onOpenGeneral = { navController.navigate(GeneralSettingsRoute) },
                        onOpenCrypto = { navController.navigate(CryptoSettingsRoute) },
                        onOpenStocks = { navController.navigate(StockSettingsRoute) },
                        onOpenPortfolio = { navController.navigate(PortfolioSettingsRoute) },
                        onOpenPrivacy = { navController.navigate(PrivacySettingsRoute) },
                        onOpenData = { navController.navigate(DataSettingsRoute) },
                        onOpenAbout = { navController.navigate(AboutRoute) },
                    )
                }
                composable<GeneralSettingsRoute> { GeneralSettingsScreen() }
                composable<CryptoSettingsRoute> { CryptoSettingsScreen() }
                composable<StockSettingsRoute> { StockSettingsScreen() }
                composable<PortfolioSettingsRoute> {
                    PortfolioSettingsScreen(
                        onOpenAccounts = { navController.navigate(AccountsRoute) },
                    )
                }
                composable<PrivacySettingsRoute> { PrivacySettingsScreen() }
                composable<DataSettingsRoute> {
                    DataSettingsScreen(
                        onOpenTransactions = { navController.navigate(TransactionsRoute) },
                        onOpenBackup = { navController.navigate(BackupRoute) },
                        onOpenMergeDuplicates = { navController.navigate(MergeDuplicatesRoute) },
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
                composable<MergeDuplicatesRoute> { MergeDuplicatesScreen() }
                composable<BackupRoute> { BackupScreen() }
                composable<AboutRoute> { AboutScreen() }
            }

            if (isTab) {
                FloatingNavPill(
                    isSelected = { tab -> destination?.hasRoute(tab.routeClass) == true },
                    onSelect = { tab ->
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    // Drawn over the nav host rather than replacing it.
    //
    // Returning early instead used to unmount the whole NavHost, taking
    // rememberNavController with it, so unlocking built a fresh controller
    // starting at the portfolio. Every screen lock threw away where you were and
    // anything half typed. An opaque overlay hides the content just as
    // completely and costs nothing.
    if (locked) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            LockScreen(onUnlocked = onUnlocked)
        }
    }
    }
}

/**
 * The bottom navigation, as a floating pill rather than a full-width bar.
 *
 * `HorizontalFloatingToolbar` is a real Material 3 component, but only in
 * material3 1.5.0-alpha27: 1.4.0 stable ships its design tokens and not the
 * composable. That alpha is why the version catalog overrides the Compose BOM
 * for material3 alone, and why this lives on its own branch.
 *
 * Selected tab shows its label, the others are icon only. Four labels do not fit
 * a pill at any readable size, and a pill of four unlabelled icons is a guessing
 * game, so the label follows the selection. That also gives the thing something
 * to animate, which is most of why the shape is worth having.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FloatingNavPill(
    isSelected: (Tab) -> Boolean,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalFloatingToolbar(
        // Always expanded: this is navigation, not a contextual toolbar, and it
        // must not collapse itself out from under a scrolling list.
        expanded = true,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
        modifier = modifier
            // The overlay sits outside any inset-aware slot, so the gesture bar
            // is this composable's own problem.
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = FloatingToolbarDefaults.ScreenOffset),
    ) {
        tabs.forEach { tab ->
            val selected = isSelected(tab)
            ToggleButton(
                checked = selected,
                onCheckedChange = { if (!selected) onSelect(tab) },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(tab.icon, contentDescription = tab.label, modifier = Modifier.size(20.dp))
                AnimatedVisibility(visible = selected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.size(8.dp))
                        Text(tab.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
        }
    }
}
