package com.eddies.app.demo

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real app seeds nothing. This is the whole of demo mode in a full build:
 * a method that returns.
 */
@Singleton
class NoDemoSeeder @Inject constructor() : DemoSeeder {
    override suspend fun seedIfNeeded() = Unit
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DemoModule {
    @Binds
    abstract fun bindSeeder(impl: NoDemoSeeder): DemoSeeder
}
