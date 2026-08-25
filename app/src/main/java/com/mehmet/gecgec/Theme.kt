package com.mehmet.gecgec

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Uygulama her zaman koyu. Telefonun acik/koyu ayarindan etkilenmesin diye
 * renkler burada sabit; hicbir yerde ham renk kullanilmiyor.
 */
val GecGecDark = darkColorScheme(
    primary = Color(0xFF7CC33F),
    onPrimary = Color(0xFF0C1206),
    primaryContainer = Color(0xFF2C5C1B),
    onPrimaryContainer = Color(0xFFDCF3C6),

    background = Color(0xFF10160B),
    onBackground = Color(0xFFE9F0E0),

    surface = Color(0xFF1A2313),
    onSurface = Color(0xFFE9F0E0),
    surfaceVariant = Color(0xFF223019),
    onSurfaceVariant = Color(0xFFC7D3BB),

    // Kartlarin ve pencerelerin zemini - bunlar verilmezse Material kendi
    // mor tonlarini kullaniyor ve tema tutmuyor
    surfaceContainerLowest = Color(0xFF0B1007),
    surfaceContainerLow = Color(0xFF161E10),
    surfaceContainer = Color(0xFF1A2313),
    surfaceContainerHigh = Color(0xFF223019),
    surfaceContainerHighest = Color(0xFF2A3A20),
    surfaceDim = Color(0xFF10160B),
    surfaceBright = Color(0xFF2F3F24),
    inverseSurface = Color(0xFFE9F0E0),
    inverseOnSurface = Color(0xFF16200F),

    outline = Color(0xFF94A188),
    outlineVariant = Color(0xFF2C3822),

    error = Color(0xFFFF8A7A),
    onError = Color(0xFF3A0B06)
)

/** "Sil" gibi tehlikeli islemler icin - koyu zeminde okunur kirmizi. */
val DangerRed = Color(0xFFFF8A7A)

/** Onay yesili. */
val OkGreen = Color(0xFF9CD86B)
