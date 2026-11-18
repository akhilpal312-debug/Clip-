package com.aiclipcreator.feature.clips

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aiclipcreator.core.common.Formats
import com.aiclipcreator.core.common.ui.EmptyState
import com.aiclipcreator.core.common.ui.PrimaryButton
import com.aiclipcreator.core.common.ui.ScreenScaffold
import com.aiclipcreator.core.common.ui.SecondaryButton
import com.aiclipcreator.core.common.ui.SectionHeader
import com.aiclipcreator.core.common.ui.SoftSurface
import com.aiclipcreator.core.common.ui.appColors
import com.aiclipcreator.core.data.AppContainer
import com.aiclipcreator.core.models.GalleryClip
import kotlinx.coroutines.launch

/** Gallery history: what was written to Movies/AI Clip Creator, with open/share/delete. */
@Composable
fun GalleryScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = appColors
    var clips by remember { mutableStateOf<List<GalleryClip>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        clips = container.clipRepository.galleryHistory()
    }

    ScreenScaffold(
        title = "Gallery history",
        subtitle = "Movies/AI Clip Creator",
        onBack = onBack
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Refresh") {
                scope.launch { clips = container.clipRepository.galleryHistory() }
            }
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.primary)
        }
        if (clips.isEmpty()) {
            EmptyState(
                title = "Nothing exported yet",
                message = "Clips you export appear here and in your gallery app under Movies/AI Clip Creator."
            )
        }
        clips.forEach { clip ->
            SoftSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(clip.displayName, style = MaterialTheme.typography.titleMedium, color = colors.ink)
                    Text(
                        "${clip.relativePath} · ${Formats.bytes(clip.sizeBytes)} · ${Formats.duration(clip.durationMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkMuted
                    )
                    Text(
                        Formats.dateTime(clip.addedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Play") {
                            runCatching { context.startActivity(container.exporter.playIntent(Uri.parse(clip.uri))) }
                        }
                        SecondaryButton("Share") {
                            runCatching { context.startActivity(container.exporter.shareIntent(Uri.parse(clip.uri))) }
                        }
                        SecondaryButton("Delete") {
                            scope.launch {
                                val result = container.exporter.delete(Uri.parse(clip.uri))
                                message = result.fold({ "Deleted ${clip.displayName}" }, { "Delete failed: ${it.message}" })
                                clips = container.clipRepository.galleryHistory()
                            }
                        }
                    }
                }
            }
        }
        SectionHeader("Folder layout")
        Text(
            "Movies/AI Clip Creator/Best Moments · Balanced Clips · Live Streams · Projects",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted
        )
        Spacer(Modifier.height(24.dp))
    }
}
