package com.guardianmesh.relay.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Colour decisions, and why.
 *
 * Only one thing on screen is allowed to be red: the SOS action. Everything else — the
 * mesh screen, settings, the followed-detection form — is calm indigo and slate, so red
 * never loses its meaning through repetition. A safety app whose every screen is alarming
 * teaches the user to stop reading it.
 *
 * [Danger] is also used as the "closed / failed" colour and [Safe] as "open / delivered",
 * which keeps the Safe Exit list and the SOS status legible at a glance.
 */

val Danger = Color(0xFFB3001F)
val DangerBright = Color(0xFFE11D3A)
val DangerContainer = Color(0xFFFFE2E5)

val Safe = Color(0xFF0F7B3F)
val SafeContainer = Color(0xFFD9F2E3)

val Caution = Color(0xFFB26A00)
val CautionContainer = Color(0xFFFFEBCC)

private val Indigo = Color(0xFF3A3F8F)
private val IndigoLight = Color(0xFFA9AEF2)
private val Slate = Color(0xFF444B57)

private val LightScheme = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E3FB),
    onPrimaryContainer = Color(0xFF1B1F5C),
    secondary = Slate,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E9EF),
    onSecondaryContainer = Color(0xFF1E232B),
    error = Danger,
    onError = Color.White,
    errorContainer = DangerContainer,
    onErrorContainer = Color(0xFF5C0010),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF14161C),
    surface = Color.White,
    onSurface = Color(0xFF14161C),
    surfaceVariant = Color(0xFFEDEFF5),
    onSurfaceVariant = Color(0xFF42464F),
    outline = Color(0xFF8C919B),
    outlineVariant = Color(0xFFD7DAE2)
)

private val DarkScheme = darkColorScheme(
    primary = IndigoLight,
    onPrimary = Color(0xFF151A4D),
    primaryContainer = Color(0xFF2A2F77),
    onPrimaryContainer = Color(0xFFE2E3FB),
    secondary = Color(0xFFBFC6D2),
    onSecondary = Color(0xFF2A2F38),
    secondaryContainer = Color(0xFF3B424C),
    onSecondaryContainer = Color(0xFFDDE2EA),
    error = Color(0xFFFF8A95),
    onError = Color(0xFF5C0010),
    errorContainer = Color(0xFF7A0018),
    onErrorContainer = Color(0xFFFFDADD),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE4E6ED),
    surface = Color(0xFF181B21),
    onSurface = Color(0xFFE4E6ED),
    surfaceVariant = Color(0xFF2A2E36),
    onSurfaceVariant = Color(0xFFC3C7D1),
    outline = Color(0xFF8C919B),
    outlineVariant = Color(0xFF3A3F48)
)

private val GuardianTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun GuardianTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (LocalContext.current as? Activity)?.window
        SideEffect {
            window?.let {
                WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = scheme, typography = GuardianTypography, content = content)
}
