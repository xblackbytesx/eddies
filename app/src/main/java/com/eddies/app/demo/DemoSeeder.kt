package com.eddies.app.demo

/**
 * Populates a portfolio for screenshots.
 *
 * Deliberately an interface with a build-time implementation rather than a
 * runtime flag. The demo flavour carries a real seeder, the full flavour a no-op,
 * and no code anywhere asks whether it is in demo mode.
 *
 * That matters because six places write to the database and two of them run
 * outside any screen: RootViewModel on launch and DailyWorker on a schedule. A
 * runtime flag would have to be honoured by all of them, forever, including in
 * code not yet written. A separate applicationId means the demo build cannot
 * reach the real database at all, which the operating system enforces rather
 * than us.
 */
interface DemoSeeder {
    /** Called once per launch. Implementations must be idempotent and cheap when already seeded. */
    suspend fun seedIfNeeded()
}
