package eu.kanade.tachiyomi.ui.social.story

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val unseenRing = Brush.sweepGradient(
    listOf(Color(0xFFFEDA75), Color(0xFFFA7E1E), Color(0xFFD62976), Color(0xFF962FBF), Color(0xFFFEDA75)),
)

/**
 * The ring around an avatar: a gradient when there is a story not watched yet, a thin grey line
 * when everything was watched, nothing when the person has no story. The padding is the same in
 * all three cases, so avatars line up in a row.
 */
@Composable
fun StoryRing(
    ringed: Boolean,
    unseen: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val ring = when {
        ringed && unseen -> Modifier.border(3.dp, unseenRing, CircleShape)
        ringed -> Modifier.border(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape)
        else -> Modifier
    }
    Box(modifier.then(ring).padding(4.dp)) { content() }
}

/** The short status of someone, as a small bubble. [placeholder] draws the "add one" invitation. */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    placeholder: Boolean = false,
    maxWidth: Dp = Dp.Unspecified,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    val sized = if (maxWidth != Dp.Unspecified) modifier.then(Modifier.widthIn(max = maxWidth)) else modifier
    Surface(
        modifier = sized.clip(shape).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = shape,
        color = if (placeholder) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (placeholder) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = if (placeholder) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}
