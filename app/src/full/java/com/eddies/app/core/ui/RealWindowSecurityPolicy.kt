package com.eddies.app.core.ui

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** The real app honours the user's setting, which is the whole point of having it. */
@Singleton
class RealWindowSecurityPolicy @Inject constructor() : WindowSecurityPolicy {
    override fun shouldSecureWindow(userPreference: Boolean): Boolean = userPreference
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WindowSecurityModule {
    @Binds
    abstract fun bindPolicy(impl: RealWindowSecurityPolicy): WindowSecurityPolicy
}
