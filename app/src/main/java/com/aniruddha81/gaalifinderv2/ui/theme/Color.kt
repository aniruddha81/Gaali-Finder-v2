package com.aniruddha81.gaalifinderv2.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * A bold, flat palette — solid colours, no gradients. Surfaces are the natural Android
 * light/dark tones (pure white, near-black) rather than tinted greys.
 */

// Brand — a hot coral, carried over from the old FAB.
val Ember10 = Color(0xFF400008)
val Ember20 = Color(0xFF640214)
val Ember30 = Color(0xFF8C1022)
val Ember40 = Color(0xFFB42232)
val Ember80 = Color(0xFFFF6B6B)
val Ember90 = Color(0xFFFFDAD9)

// Secondary — warm amber, for accents and the "new" badge.
val Amber10 = Color(0xFF2B1700)
val Amber20 = Color(0xFF472A00)
val Amber30 = Color(0xFF663E00)
val Amber40 = Color(0xFF875400)
val Amber80 = Color(0xFFFFB74D)
val Amber90 = Color(0xFFFFDCBE)

// Tertiary — electric violet, for the playing state.
val Violet10 = Color(0xFF23003D)
val Violet20 = Color(0xFF3A0063)
val Violet30 = Color(0xFF52148A)
val Violet40 = Color(0xFF6B2FA3)
val Violet80 = Color(0xFFB388FF)
val Violet90 = Color(0xFFEEDBFF)

// Neutrals — true greys: pure white / near-black, not tinted or pitch-dark.
val Neutral6 = Color(0xFF121212)
val Neutral10 = Color(0xFF1C1C1C)
val Neutral12 = Color(0xFF202020)
val Neutral17 = Color(0xFF272727)
val Neutral20 = Color(0xFF2C2C2C)
val Neutral22 = Color(0xFF313131)
val Neutral24 = Color(0xFF363636)
val Neutral90 = Color(0xFFE4E4E4)
val Neutral95 = Color(0xFFF2F2F2)
val Neutral98 = Color(0xFFFAFAFA)
val Neutral99 = Color(0xFFFDFDFD)
val Neutral100 = Color(0xFFFFFFFF)

val NeutralVariant30 = Color(0xFF474747)
val NeutralVariant50 = Color(0xFF787878)
val NeutralVariant60 = Color(0xFF949494)
val NeutralVariant80 = Color(0xFFD0D0D0)
val NeutralVariant90 = Color(0xFFE8E8E8)

val Error10 = Color(0xFF410002)
val Error20 = Color(0xFF690005)
val Error40 = Color(0xFFBA1A1A)
val Error80 = Color(0xFFFFB4AB)
val Error90 = Color(0xFFFFDAD6)

/**
 * Per-clip accent colours.
 *
 * Picked deterministically from the clip id rather than at random: the old card re-rolled its
 * colour on every recomposition, so the grid flickered while scrolling.
 */
val ClipAccentsLight = listOf(
    Color(0xFFD32F2F),
    Color(0xFFC2185B),
    Color(0xFF7B1FA2),
    Color(0xFF1976D2),
    Color(0xFF00897B),
    Color(0xFF388E3C),
    Color(0xFFF57C00),
    Color(0xFFE64A19),
)

val ClipAccentsDark = listOf(
    Color(0xFFFF5252),
    Color(0xFFFF4081),
    Color(0xFFCE93D8),
    Color(0xFF64B5F6),
    Color(0xFF4DB6AC),
    Color(0xFF81C784),
    Color(0xFFFFB74D),
    Color(0xFFFF8A65),
)
