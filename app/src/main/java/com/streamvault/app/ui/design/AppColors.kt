package com.streamvault.app.ui.design

import androidx.compose.ui.graphics.Color

object AppColors {
    // Canvas & Void - Deep cinematic obsidian
    val Canvas = Color(0xFF050608)
    val CanvasElevated = Color(0xFF090B10)

    // Liquid Glass Metamaterial Tiers
    val GlassUltraThin = Color(0x0AFFFFFF) // ~4% white
    val GlassThin = Color(0x14FFFFFF)      // ~8% white
    val GlassRegular = Color(0x24161B26)   // Translucent slate/glass ~65%
    val GlassThick = Color(0x38181E2B)     // Translucent obsidian ~75%
    val GlassSheet = Color(0x2810141D)     // Modal / drawer sheet glass

    // Surface mappings refined to obsidian-compatible tones
    val Surface = Color(0xFF0E1117)
    val SurfaceElevated = Color(0xFF141822)
    val SurfaceEmphasis = Color(0xFF1B2230)
    val SurfaceAccent = Color(0xFF222B3D)

    // Brand & Focus Accents
    val Brand = Color(0xFF69A8FF)
    val BrandMuted = Color(0x335FA4FF)
    val BrandStrong = Color(0xFF8BBCFF)
    val Focus = Color(0xFFF4F8FF)
    val FocusGlass = Color(0xF5FFFFFF)     // Milky white for focused pills
    val FocusCardSurface = Color(0x2EFFFFFF) // Luminous glass bloom for focused cards

    // Specular Rim Lightings
    val SpecularRimResting = Color(0x1FFFFFFF)  // 12% white subtle rim
    val SpecularRimFocused = Color(0x8AFFFFFF)  // 54% white directional highlight
    val SpecularRimFocusedMuted = Color(0x33FFFFFF) // 20% white ambient rim

    // Vibrancy Typography
    val TextPrimary = Color(0xFFF7F9FC)
    val TextSecondary = Color(0x9EFFFFFF)  // 62% frosted white
    val TextTertiary = Color(0x61FFFFFF)   // 38% subtle white
    val TextDisabled = Color(0x38FFFFFF)   // 22% disabled white
    val TextInverted = Color(0xFF050608)   // Inverted black text on milky-white focused pills

    // System Badges & Accents
    val Live = Color(0xFFFF5C61)
    val Success = Color(0xFF4FD39A)
    val Warning = Color(0xFFFFC766)
    val Info = Color(0xFF57C9FF)

    val Divider = Color(0x14FFFFFF)
    val Outline = Color(0x1FFFFFFF)

    val HeroTop = Color(0xCC050608)
    val HeroBottom = Color(0xF2050608)
}
