package com.eddies.app.core.ui

/**
 * Whether the window should carry FLAG_SECURE.
 *
 * An interface with a build-time implementation, for the same reason
 * `DemoSeeder` is one: the demo build differs from the real one structurally,
 * not by checking a flag at runtime.
 *
 * FLAG_SECURE blocks screenshots outright, not merely the recents thumbnail. In
 * the real app that is exactly what the "hide from recent apps" setting is for.
 * In the demo build it makes the app useless for its only purpose, and the demo
 * has no real holdings to protect in the first place.
 *
 * Doing this in the seeder instead would not hold: the seeder runs once on first
 * launch, so an existing demo install would stay locked out, and the setting
 * could be switched back on at any time.
 */
interface WindowSecurityPolicy {
    /** [userPreference] is the user's "hide from recent apps" setting. */
    fun shouldSecureWindow(userPreference: Boolean): Boolean
}
