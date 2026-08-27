package com.aniruddha81.gaalifinderv2.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlin.math.absoluteValue

private val LightColors = lightColorScheme(
    primary = Ember40,
    onPrimary = Neutral100,
    primaryContainer = Ember90,
    onPrimaryContainer = Ember10,
    secondary = Amber40,
    onSecondary = Neutral100,
    secondaryContainer = Amber90,
    onSecondaryContainer = Amber10,
    tertiary = Violet40,
    onTertiary = Neutral100,
    tertiaryContainer = Violet90,
    onTertiaryContainer = Violet10,
    error = Error40,
    onError = Neutral100,
    errorContainer = Error90,
    onErrorContainer = Error10,
    background = Neutral100,
    onBackground = Neutral10,
    surface = Neutral100,
    onSurface = Neutral10,
    surfaceVariant = NeutralVariant90,
    onSurfaceVariant = NeutralVariant30,
    surfaceContainerLowest = Neutral100,
    surfaceContainerLow = Neutral99,
    surfaceContainer = Neutral98,
    surfaceContainerHigh = Neutral95,
    surfaceContainerHighest = Neutral90,
    outline = NeutralVariant50,
    outlineVariant = NeutralVariant80,
    inverseSurface = Neutral20,
    inverseOnSurface = Neutral95,
    inversePrimary = Ember80,
)

private val DarkColors = darkColorScheme(
    primary = Ember80,
    onPrimary = Ember20,
    primaryContainer = Ember30,
    onPrimaryContainer = Ember90,
    secondary = Amber80,
    onSecondary = Amber20,
    secondaryContainer = Amber30,
    onSecondaryContainer = Amber90,
    tertiary = Violet80,
    onTertiary = Violet20,
    tertiaryContainer = Violet30,
    onTertiaryContainer = Violet90,
    error = Error80,
    onError = Error20,
    errorContainer = Error10,
    onErrorContainer = Error90,
    background = Neutral6,
    onBackground = Neutral90,
    surface = Neutral6,
    onSurface = Neutral90,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Neutral10,
    surfaceContainer = Neutral12,
    surfaceContainerHigh = Neutral17,
    surfaceContainerHighest = Neutral22,
    outline = NeutralVariant60,
    outlineVariant = NeutralVariant30,
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
    inversePrimary = Ember40,
)

/** Exposes the clip accent list to composables without threading it through every parameter. */
val LocalClipAccents = staticCompositionLocalOf { ClipAccentsLight }

@Composable
fun GaaliFinderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Off by default: the brand palette is the point of the redesign, and Material You would
     * replace it with whatever the user's wallpaper happens to be.
     */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    CompositionLocalProvider(
        LocalClipAccents provides if (darkTheme) ClipAccentsDark else ClipAccentsLight,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = GaaliTypography,
            shapes = GaaliShapes,
            content = content,
        )
    }
}

/**
 * Stable accent for a clip. Same id always yields the same colour, across scrolls, restarts
 * and theme changes.
 */
@Composable
@ReadOnlyComposable
fun clipAccentFor(clipId: String): Color {
    val accents = LocalClipAccents.current
    // `Int.MIN_VALUE.absoluteValue` is still negative, which would throw here, so the sign is
    // masked off instead of negated.
    val index = (clipId.hashCode() and Int.MAX_VALUE) % accents.size
    return accents[index]
}
