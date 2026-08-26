package com.eddies.app.navigation

import kotlinx.serialization.Serializable

@Serializable data object PortfolioRoute
@Serializable data object InsightsRoute
@Serializable data object MarketsRoute
@Serializable data object SettingsRoute

@Serializable data class AssetDetailRoute(val assetId: String)
@Serializable data class AddTransactionRoute(val assetId: String? = null, val transactionId: Long = 0)
@Serializable data object AddPositionSearchRoute
@Serializable data object AccountsRoute
@Serializable data object BackupRoute
@Serializable data object AboutRoute

/** The whole ledger, across every asset. */
@Serializable data object TransactionsRoute
