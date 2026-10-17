package com.nekohome.translate.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nekohome.translate.R
import com.nekohome.translate.data.ThemeSeed
import com.nekohome.translate.ui.motion.LocalReduceMotion
import com.nekohome.translate.ui.motion.Springs
import com.nekohome.translate.ui.motion.bounceClick
import com.nekohome.translate.ui.motion.motion
import com.nekohome.translate.ui.theme.SeedSchemes

@StringRes
fun ThemeSeed.labelRes(): Int = when (this) {
    ThemeSeed.SAKURA -> R.string.theme_seed_sakura
    ThemeSeed.CRIMSON -> R.string.theme_seed_crimson
    ThemeSeed.ORANGE -> R.string.theme_seed_orange
    ThemeSeed.AMBER -> R.string.theme_seed_amber
    ThemeSeed.LIME -> R.string.theme_seed_lime
    ThemeSeed.GREEN -> R.string.theme_seed_green
    ThemeSeed.TEAL -> R.string.theme_seed_teal
    ThemeSeed.SKY -> R.string.theme_seed_sky
    ThemeSeed.BLUE -> R.string.theme_seed_blue
    ThemeSeed.INDIGO -> R.string.theme_seed_indigo
    ThemeSeed.VIOLET -> R.string.theme_seed_violet
    ThemeSeed.TAUPE -> R.string.theme_seed_taupe
}

private const val PER_ROW = 6

/** 「主题色」: 12 circular swatches in two rows; the selected one grows and shows a check. */
@Composable
fun ThemeColorPicker(selected: ThemeSeed, onSelect: (ThemeSeed) -> Unit) {
    // Swatches preview the generated scheme for the theme actually on screen.
    val dark = MaterialTheme.colorScheme.surface.luminanceIsDark()
    Column {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_theme_color)) },
            supportingContent = {
                Text(stringResource(R.string.settings_theme_color_desc, stringResource(selected.labelRes())))
            },
            leadingContent = { Icon(Icons.Outlined.Palette, contentDescription = null) },
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        ) {
            ThemeSeed.entries.chunked(PER_ROW).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { seed ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Swatch(seed, dark, seed == selected) { onSelect(seed) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Swatch(seed: ThemeSeed, dark: Boolean, selected: Boolean, onClick: () -> Unit) {
    val scheme = remember(seed, dark) { SeedSchemes.of(seed, dark) }
    val primary = Color(scheme.primary)
    val container = Color(scheme.primaryContainer)
    val tertiary = Color(scheme.tertiaryContainer)
    val name = stringResource(seed.labelRes())
    val src = remember { MutableInteractionSource() }
    // Both animated values are read only in graphicsLayer / drawWithContent (draw phase), so
    // selecting a swatch never recomposes it per frame.
    val scale = animateFloatAsState(
        targetValue = if (selected) 1f else 0.84f,
        animationSpec = motion(Springs.bouncy(stiffness = 500f)),
        label = "swatchScale",
    )
    val ring = animateDpAsState(
        targetValue = if (selected) 2.dp else 0.dp,
        animationSpec = motion(Springs.smooth()),
        label = "swatchRing",
    )
    val reduceMotion = LocalReduceMotion.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .bounceClick(src, pressedScale = 0.8f)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(CircleShape)
            .drawWithContent {
                drawContent()
                val w = ring.value.toPx()
                if (w > 0f) drawCircle(primary, radius = size.minDimension / 2f - w / 2f, style = Stroke(w))
            }
            .selectable(selected = selected, interactionSource = src, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
    ) {
        // Primary on the top half, container + tertiary container below — like the system palette picker.
        Box(
            Modifier
                .padding(if (selected) 5.dp else 0.dp)
                .aspectRatio(1f)
                .fillMaxSize()
                .clip(CircleShape)
                .background(container),
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().background(primary))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(1f).fillMaxSize().background(container))
                    Box(Modifier.weight(1f).fillMaxSize().background(tertiary))
                }
            }
        }
        AnimatedVisibility(
            visible = selected,
            enter = if (reduceMotion) fadeIn(motion(Springs.smooth())) else scaleIn(Springs.bouncy()) + fadeIn(Springs.smooth()),
            exit = if (reduceMotion) fadeOut(motion(Springs.smooth())) else scaleOut(Springs.smooth()) + fadeOut(Springs.smooth()),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(primary),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color(scheme.onPrimary), modifier = Modifier.size(16.dp))
            }
        }
    }
}

private fun Color.luminanceIsDark(): Boolean = (0.2126f * red + 0.7152f * green + 0.0722f * blue) < 0.5f
