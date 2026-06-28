package com.humanemagica.nothing.terminal.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Pitch Cyan — the cockpit's color tokens, copied from nothing-to-say's design system
 * (ui/theme/Color.kt) per the reuse plan. Values mirror specs/mockups/styles.css. The last two
 * are derived for the recorder's hands-free areas (panel = accent at ~0.5 over bg; lock =
 * accentSoft at ~0.6 over the panel).
 */
object Cyan {
    val bg = Color(0xFF000000)
    val ink = Color(0xFFD0E8EB)
    val muted = Color(0xFF5A7376)
    val faint = Color(0xFF2D3D40)
    val rule = Color(0xFF0E1A1C)
    val accent = Color(0xFF5DD5E0)
    val accentSoft = Color(0xFF0A2426)
    val danger = Color(0xFFB68CFF)
    val accentPanel = Color(0xFF2F6A70)
    val accentLock = Color(0xFF194044)
}
