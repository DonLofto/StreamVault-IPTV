package com.streamvault.app.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceDefaults

/**
 * Directional specular reflection brushes mimicking light catching the beveled edge of cut glass.
 */
val SpecularFocusBrush = Brush.linearGradient(
    0.0f to Color.White.copy(alpha = 0.65f),
    0.45f to Color.White.copy(alpha = 0.18f),
    1.0f to Color.White.copy(alpha = 0.38f),
    start = Offset(0f, 0f),
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
)

val SpecularRestingBrush = Brush.linearGradient(
    0.0f to Color.White.copy(alpha = 0.15f),
    0.5f to Color.White.copy(alpha = 0.04f),
    1.0f to Color.White.copy(alpha = 0.08f),
    start = Offset(0f, 0f),
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
)

/**
 * Creates a tvOS Liquid Glass ClickableSurfaceBorder with directional specular rim lighting.
 */
@Composable
fun liquidGlassBorder(
    shape: Shape = RoundedCornerShape(18.dp),
    focusedWidth: Dp = 1.dp,
    restingWidth: Dp = 0.75.dp,
    hasRestingBorder: Boolean = true
): ClickableSurfaceBorder {
    return ClickableSurfaceDefaults.border(
        border = if (hasRestingBorder) {
            Border(
                border = BorderStroke(restingWidth, SpecularRestingBrush),
                shape = shape
            )
        } else {
            Border(
                border = BorderStroke(0.dp, Color.Transparent),
                shape = shape
            )
        },
        focusedBorder = Border(
            border = BorderStroke(focusedWidth, SpecularFocusBrush),
            shape = shape
        )
    )
}

/**
 * Applies Apple Liquid Glass styling with specular rim lighting and focus bloom to any Compose layout.
 */
fun Modifier.liquidGlassSurface(
    isFocused: Boolean,
    shape: Shape = RoundedCornerShape(18.dp),
    restingColor: Color = AppColors.GlassThin,
    focusedColor: Color = AppColors.FocusCardSurface,
    borderWidth: Dp = if (isFocused) 1.dp else 0.75.dp
): Modifier = this
    .clip(shape)
    .background(if (isFocused) focusedColor else restingColor)
    .border(
        width = borderWidth,
        brush = if (isFocused) SpecularFocusBrush else SpecularRestingBrush,
        shape = shape
    )
