package com.pakomo.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pakomo.core.model.TargetScope
import com.pakomo.ui.theme.Accent
import com.pakomo.ui.theme.AccentTint
import com.pakomo.ui.theme.Border
import com.pakomo.ui.theme.Muted
import com.pakomo.ui.theme.OnSurface
import com.pakomo.ui.theme.OnSurfaceVariant

@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = OnSurface,
                )
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Box(modifier = Modifier.weight(1f)) {
            if (titleContent != null) {
                titleContent()
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
        action?.invoke()
        if (action == null) Spacer(Modifier.width(8.dp))
    }
}

@Composable
fun ScopeSelector(
    selected: TargetScope,
    onSelected: (TargetScope) -> Unit,
    modifier: Modifier = Modifier,
    disabledScopes: Set<TargetScope> = emptySet(),
    onDisabledScopeClick: (TargetScope) -> Unit = {},
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFF0F2F5), RoundedCornerShape(10.dp))
            .padding(3.dp),
    ) {
        val gap = 3.dp
        val segmentWidth = (maxWidth - gap * 2f) / TargetScope.entries.size.toFloat()
        val targetOffset =
            (segmentWidth + gap) * TargetScope.entries.indexOf(selected).toFloat()
        val indicatorOffset by animateDpAsState(
            targetValue = targetOffset,
            animationSpec = tween(180),
            label = "scopeIndicator",
        )
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segmentWidth)
                .height(40.dp)
                .background(
                    if (selected in disabledScopes) Color(0xFFE2E5E9) else Color.White,
                    RoundedCornerShape(8.dp),
                ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            TargetScope.entries.forEach { scope ->
                val active = scope == selected
                val enabled = scope !in disabledScopes
                val textColor by animateColorAsState(
                    targetValue = when {
                        !enabled -> Muted
                        active -> Accent
                        else -> OnSurfaceVariant
                    },
                    animationSpec = tween(120),
                    label = "scopeText",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.RadioButton,
                        ) {
                            if (enabled) {
                                onSelected(scope)
                            } else {
                                onDisabledScopeClick(scope)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = scope.label,
                        color = textColor,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 13.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun NavigationRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = OnSurfaceVariant,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = Muted,
        )
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = 52.dp, end = 16.dp),
        color = Border,
        thickness = 1.dp,
    )
}

@Composable
fun AppIcon(
    bitmap: Bitmap?,
    fallbackLabel: String,
    modifier: Modifier = Modifier,
) {
    val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }
    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            modifier = modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        return
    }
    val initial = remember(fallbackLabel) { fallbackLabel.take(1).uppercase() }
    Box(
        modifier = modifier
            .size(40.dp)
            .background(AccentTint, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = Accent,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = OnSurfaceVariant,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = OnSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
