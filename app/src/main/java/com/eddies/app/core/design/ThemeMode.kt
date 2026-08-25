package com.eddies.app.core.design

/**
 * DARK is the default rather than SYSTEM. A portfolio is read in glances, often
 * at night, and the charts are designed against a dark ground.
 */
enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
    OLED("OLED black"),
}
