package com.aiclipcreator.core.common.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SoftSurface(
    modifier: Modifier = Modifier,
    corner: Int = 22,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val c = appColors
    var box = Modifier
        .clip(RoundedCornerShape(corner.dp))
        .background(
            Brush.verticalGradient(
                listOf(
                    c.surface.copy(alpha = 0.94f),
                    c.surface.copy(alpha = 0.78f)
                )
            )
        )
        .border(1.dp, c.outline.copy(alpha = 0.55f), RoundedCornerShape(corner.dp))
    if (onClick != null) box = box.clickable { onClick() }
    Box(box.then(modifier)) { content() }
}

@Composable
fun ScreenScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable () -> Unit
) {
    val c = appColors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.paper)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(c.surfaceAlt.copy(alpha = 0.75f))
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.ink, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.inkMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (actions != null) actions()
        }
        val body: @Composable () -> Unit = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) { content() }
        }
        if (scrollable) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) { body() }
        } else {
            body()
        }
    }
}

@Composable
fun SectionHeader(title: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val c = appColors
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.inkMuted, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            Row(
                modifier = Modifier.clickable { onAction() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = c.primary)
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = c.primary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val c = appColors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) c.primary else c.outline)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = c.onPrimary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val c = appColors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(c.surfaceAlt.copy(alpha = if (enabled) 0.9f else 0.4f))
            .border(1.dp, c.outline.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, color = c.ink, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun StatTile(label: String, value: String, hint: String? = null, modifier: Modifier = Modifier) {
    val c = appColors
    SoftSurface(modifier = modifier, corner = 18) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, color = c.ink)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, maxLines = 2)
            }
        }
    }
}

@Composable
fun ProgressBlock(
    label: String,
    progress: Float,
    detail: String? = null,
    modifier: Modifier = Modifier,
    indeterminate: Boolean = false
) {
    val c = appColors
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(450),
        label = "progress"
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = c.ink)
            Spacer(Modifier.weight(1f))
            Text("${(animated * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = c.inkMuted)
        }
        Spacer(Modifier.height(8.dp))
        if (indeterminate) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = c.primary,
                trackColor = c.surfaceAlt
            )
        } else {
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = c.primary,
                trackColor = c.surfaceAlt
            )
        }
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, maxLines = 2)
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = appColors
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) c.onPrimary else c.ink,
        modifier = modifier
            .clip(CircleShape)
            .background(if (selected) c.primary else c.surfaceAlt.copy(alpha = 0.8f))
            .border(1.dp, if (selected) Color.Transparent else c.outline.copy(alpha = 0.6f), CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    val c = appColors
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor ?: c.ink, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun EmptyState(title: String, message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val c = appColors
    SoftSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(22.dp), horizontalAlignment = Alignment.Start) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(14.dp))
                SecondaryButton(text = actionLabel, onClick = onAction)
            }
        }
    }
}

@Composable
fun InlineLoading(text: String = "Working…") {
    val c = appColors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = c.primary)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
    }
}

@Composable
fun StatusDot(active: Boolean, modifier: Modifier = Modifier) {
    val c = appColors
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (active) c.success else c.inkMuted.copy(alpha = 0.5f))
    )
}
