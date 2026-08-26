package com.eddies.app.core.ui

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The demo build never sets FLAG_SECURE, whatever the setting says.
 *
 * Taking screenshots is the only reason this build exists, and FLAG_SECURE
 * blocks them outright. There are no real holdings here to protect, so the flag
 * has nothing to defend.
 *
 * Deliberately ignores the preference rather than defaulting it off: a default
 * can be changed, and an existing install would never see a new default anyway.
 */
@Singleton
class DemoWindowSecurityPolicy @Inject constructor() : WindowSecurityPolicy {
    override fun shouldSecureWindow(userPreference: Boolean): Boolean = false
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WindowSecurityModule {
    @Binds
    abstract fun bindPolicy(impl: DemoWindowSecurityPolicy): WindowSecurityPolicy
}
