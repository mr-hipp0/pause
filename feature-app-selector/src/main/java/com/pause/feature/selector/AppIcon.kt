package com.pause.feature.selector

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun AppIcon(packageName: String, appName: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = appName
            }
        },
        update = { view ->
            view.contentDescription = appName
            view.setImageDrawable(runCatching { view.context.packageManager.getApplicationIcon(packageName) }.getOrNull())
        },
    )
}
