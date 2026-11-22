package com.sonicmusic.app.shared.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.R
import com.sonicmusic.app.theme.Dimens
import com.sonicmusic.app.theme.MotionTokens
import com.sonicmusic.app.theme.shouldReduceMotion

/**
 * CompositionLocal providing network connectivity state.
 * `true` = online, `false` = offline.
 */
val LocalIsOnline = staticCompositionLocalOf { true }

/**
 * Offline state for screens that need an active connection.
 */
@Composable
fun NoInternetView(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = Dimens.screenPadding, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        ExpressiveNotice(
            icon = Icons.Rounded.WifiOff,
            title = stringResource(R.string.network_offline),
            message = stringResource(R.string.network_reconnect_message),
            actionLabel = stringResource(R.string.network_retry),
            onAction = onRetry,
            modifier = Modifier.widthIn(max = Dimens.maxContentWidth)
        )
    }
}

/**
 * Compact "No Internet" banner that can be shown at the top/bottom of a screen
 * without replacing the content entirely. Useful for the full player which
 * should still show controls.
 */
@Composable
fun NoInternetBanner(
    isVisible: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val reduceMotion = shouldReduceMotion()
    AnimatedVisibility(
        visible = isVisible,
        enter = if (reduceMotion) EnterTransition.None else
            expandVertically(MotionTokens.quickSpatial(), expandFrom = Alignment.Top) + fadeIn(MotionTokens.effects()),
        exit = if (reduceMotion) ExitTransition.None else
            shrinkVertically(MotionTokens.quickSpatial(), shrinkTowards = Alignment.Top) + fadeOut(MotionTokens.quickEffects())
    ) {
        Box(
            modifier = modifier.fillMaxWidth()
                .padding(horizontal = Dimens.screenPadding, vertical = if (compact) 2.dp else 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.WifiOff, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.network_offline),
                        style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
