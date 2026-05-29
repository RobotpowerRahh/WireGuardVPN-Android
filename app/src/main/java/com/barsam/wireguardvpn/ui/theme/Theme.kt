package com.barsam.wireguardvpn.ui.theme

import androidx.compose.ui.graphics.Color

object WireGuardTheme {
    // Backgrounds
    val bg = Color(0xFF0A0A0B)
    val surface = Color(0x08FFFFFF)
    val surfaceHover = Color(0x0DFFFFFF)
    val surfaceActive = Color(0x12FFFFFF)

    // Borders
    val border = Color(0x0AFFFFFF)
    val borderHover = Color(0x14FFFFFF)

    // Text
    val text1 = Color(0xE6FFFFFF)
    val text2 = Color(0x73FFFFFF)
    val text3 = Color(0x33FFFFFF)

    // Accent (green) — matches macOS Theme.accent
    val accent = Color(0xFF5EC48F)
    val accentDim = Color(0x145EC48F)
    val accentBorder = Color(0x265EC48F)
    val accentText = Color(0xCC5EC48F)

    // Status colors
    val blue = Color(0xFF6B9FFF)
    val blueDim = Color(0x146B9FFF)
    val orange = Color(0xFFE8943A)
    val orangeDim = Color(0x14E8943A)
    val purple = Color(0xFFA78BFA)
    val purpleDim = Color(0x14A78BFA)
    val red = Color(0xFFF87171)
    val redDim = Color(0x14F87171)

    // Sizing
    const val radius = 8f
    const val radiusSmall = 6f
}
