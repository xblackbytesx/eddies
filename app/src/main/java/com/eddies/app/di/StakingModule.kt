package com.eddies.app.di

import com.eddies.app.data.staking.CardanoKoiosProvider
import com.eddies.app.data.staking.StakingProvider
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The staking providers, as a set.
 *
 * Adding a chain is a new implementation plus one line here. Nothing in
 * StakingRepository or the UI changes, which is what the interface is for.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StakingModule {

    @Binds
    @IntoSet
    abstract fun bindCardano(impl: CardanoKoiosProvider): StakingProvider
}
