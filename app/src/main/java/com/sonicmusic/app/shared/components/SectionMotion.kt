package com.sonicmusic.app.shared.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sonicmusic.app.theme.MotionTokens
import com.sonicmusic.app.theme.shouldReduceMotion

@Composable
internal fun LazyItemScope.animatedSection(modifier: Modifier = Modifier): Modifier = if (shouldReduceMotion()) modifier else {
    modifier.animateItem(
        fadeInSpec = MotionTokens.effects(),
        fadeOutSpec = MotionTokens.quickEffects(),
        placementSpec = MotionTokens.spatial()
    )
}

internal fun LazyListScope.animatedSection(
    key: Any,
    contentType: Any? = null,
    content: @Composable () -> Unit
) = item(key = key, contentType = contentType) {
    Box(animatedSection().fillMaxWidth()) { content() }
}
