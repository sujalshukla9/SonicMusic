package com.sonicmusic.app.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import com.sonicmusic.app.theme.pressScale

@Immutable
class FloatingToolbarColors(
    val containerColor: Color,
    val contentColor: Color,
)

object FloatingToolbarDefaults {
    val ContainerHeight: Dp = 58.dp
    val ContainerElevation: Dp = 0.dp
    val ContainerShape: Shape = CircleShape
    val ContentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 6.dp)

    @Composable
    fun standardFloatingToolbarColors(
        containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor: Color = MaterialTheme.colorScheme.onSurface
    ): FloatingToolbarColors = FloatingToolbarColors(
        containerColor = containerColor,
        contentColor = contentColor
    )

    @Composable
    fun vibrantFloatingToolbarColors(
        containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
        contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
    ): FloatingToolbarColors = FloatingToolbarColors(
        containerColor = containerColor,
        contentColor = contentColor
    )
}

/**
 * Material 3 Expressive HorizontalFloatingToolbar without shadow or borders.
 * Highly optimized for 120 FPS buttery smooth tab transitions.
 */
@Composable
fun HorizontalFloatingToolbar(
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") expanded: Boolean = true,
    colors: FloatingToolbarColors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
    contentPadding: PaddingValues = FloatingToolbarDefaults.ContentPadding,
    shape: Shape = FloatingToolbarDefaults.ContainerShape,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    floatingActionButton: (@Composable () -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier.wrapContentWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.height(FloatingToolbarDefaults.ContainerHeight),
            shape = shape,
            color = colors.containerColor,
            contentColor = colors.contentColor,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp
        ) {
            CompositionLocalProvider(LocalContentColor provides colors.contentColor) {
                Row(
                    modifier = Modifier.padding(contentPadding),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (leadingContent != null) {
                        leadingContent()
                    }

                    content()

                    if (trailingContent != null) {
                        trailingContent()
                    }
                }
            }
        }

        if (floatingActionButton != null) {
            Spacer(modifier = Modifier.width(10.dp))
            floatingActionButton()
        }
    }
}

/**
 * Modern Material 3 Expressive Floating Navigation Bar for SonicMusic.
 * When a tab is selected, its text label smoothly appears beside its logo inside an active pill.
 * Shadowless, borderless, clean Material 3 design, optimized for high refresh rate rendering.
 */
@Composable
fun HorizontalFloatingNavBar(
    items: List<Screen>,
    currentDestination: NavDestination?,
    onItemClick: (Screen) -> Unit,
    modifier: Modifier = Modifier,
    floatingActionButton: (@Composable () -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    indicatorColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    selectedContentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    unselectedContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val currentRoute = currentDestination?.route

    HorizontalFloatingToolbar(
        modifier = modifier,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        floatingActionButton = floatingActionButton
    ) {
        items.forEach { screen ->
            key(screen.route) {
                val isSelected = remember(currentRoute, screen.route) {
                    currentDestination?.hierarchy?.any { it.route == screen.route } == true
                }
                val onClick = remember(screen, onItemClick) {
                    { onItemClick(screen) }
                }

                FloatingNavBarItem(
                    screen = screen,
                    selected = isSelected,
                    onClick = onClick,
                    indicatorColor = indicatorColor,
                    selectedContentColor = selectedContentColor,
                    unselectedContentColor = unselectedContentColor
                )
            }
        }
    }
}

/**
 * Individual tab item. When selected, displays the icon AND text label beside it.
 * Uses a single unified transition and constant padding for 120 FPS stutter-free transitions.
 */
@Composable
private fun FloatingNavBarItem(
    screen: Screen,
    selected: Boolean,
    onClick: () -> Unit,
    indicatorColor: Color,
    selectedContentColor: Color,
    unselectedContentColor: Color,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val reduceMotion = com.sonicmusic.app.theme.shouldReduceMotion()
    val interactionSource = remember { MutableInteractionSource() }

    // Synchronize all tab animations into a single coordinated transition
    val transition = updateTransition(targetState = selected, label = "tab_${screen.route}")

    val animatedBgColor by transition.animateColor(
        transitionSpec = {
            tween(
                durationMillis = com.sonicmusic.app.theme.MotionTokens.DurationFast,
                easing = com.sonicmusic.app.theme.MotionTokens.EmphasizedDecelerate
            )
        },
        label = "bg_${screen.route}"
    ) { isSelected ->
        if (isSelected) indicatorColor else Color.Transparent
    }

    val animatedContentColor by transition.animateColor(
        transitionSpec = {
            tween(
                durationMillis = com.sonicmusic.app.theme.MotionTokens.DurationFast,
                easing = com.sonicmusic.app.theme.MotionTokens.EmphasizedDecelerate
            )
        },
        label = "content_${screen.route}"
    ) { isSelected ->
        if (isSelected) selectedContentColor else unselectedContentColor
    }

    val iconScale by transition.animateFloat(
        transitionSpec = {
            if (reduceMotion) {
                androidx.compose.animation.core.snap()
            } else {
                androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                )
            }
        },
        label = "scale_${screen.route}"
    ) { isSelected ->
        if (isSelected && !reduceMotion) 1.06f else 1.0f
    }

    Row(
        modifier = modifier
            .height(44.dp)
            .pressScale(interactionSource, targetScale = 0.94f)
            .clip(CircleShape)
            .background(animatedBgColor)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, radius = 24.dp),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
            contentDescription = screen.title,
            tint = animatedContentColor,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )

        AnimatedVisibility(
            visible = selected,
            enter = if (reduceMotion) fadeIn(tween(100)) else {
                fadeIn(
                    animationSpec = tween(durationMillis = 150, delayMillis = 20)
                ) + expandHorizontally(
                    animationSpec = tween(
                        durationMillis = com.sonicmusic.app.theme.MotionTokens.DurationStandard,
                        easing = com.sonicmusic.app.theme.MotionTokens.EmphasizedDecelerate
                    ),
                    expandFrom = Alignment.Start,
                    clip = true
                )
            },
            exit = if (reduceMotion) fadeOut(tween(80)) else {
                fadeOut(
                    animationSpec = tween(durationMillis = 80)
                ) + shrinkHorizontally(
                    animationSpec = tween(
                        durationMillis = com.sonicmusic.app.theme.MotionTokens.DurationFast,
                        easing = com.sonicmusic.app.theme.MotionTokens.EmphasizedAccelerate
                    ),
                    shrinkTowards = Alignment.Start,
                    clip = true
                )
            }
        ) {
            Text(
                text = screen.title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp
                ),
                color = animatedContentColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}
