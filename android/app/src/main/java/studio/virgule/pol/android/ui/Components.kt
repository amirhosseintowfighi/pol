package studio.virgule.pol.android.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.Icons
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import studio.virgule.pol.android.R
import androidx.compose.ui.unit.sp
import studio.virgule.pol.android.common.Format

/** Contact picture as in Google's Phone and Messages: a coloured circle with the initial, or a person silhouette for
 *  numbers without a name. The colour comes from the name, so a person keeps the same colour everywhere. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Int = 44) {
    val (bg, fg) = avatarColors(name)
    val letter = name.firstOrNull { it.isLetter() }
    Box(modifier.size(size.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        if (letter == null) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.62f).dp))
        } else {
            Text(letter.uppercase(), color = fg, fontSize = (size * 0.42f).sp)
        }
    }
}

private val avatarPalette = listOf(
    Color(0xFFF6AED2) to Color(0xFF5C1340), // pink
    Color(0xFF8FDCA0) to Color(0xFF0D4A1E), // green
    Color(0xFF7CD6F2) to Color(0xFF00405A), // blue
    Color(0xFFFFCB52) to Color(0xFF4F3500), // yellow
    Color(0xFFFFA866) to Color(0xFF5A2600), // orange
    Color(0xFFC6A6FA) to Color(0xFF34156B), // purple
    Color(0xFFF28B82) to Color(0xFF5F1410), // red
)

fun avatarColors(name: String): Pair<Color, Color> = avatarPalette[(name.hashCode() and 0x7fffffff) % avatarPalette.size]

/** Shape of one row in a group of rounded cards, the list style of current Google apps: large outer corners on the
 *  first and last card, small inner corners in between. */
fun segmentShape(index: Int, count: Int): RoundedCornerShape {
    val big = 20.dp; val small = 4.dp
    return RoundedCornerShape(
        topStart = if (index == 0) big else small, topEnd = if (index == 0) big else small,
        bottomStart = if (index == count - 1) big else small, bottomEnd = if (index == count - 1) big else small,
    )
}

/** One card of such a group. */
@Composable
fun SegmentCard(index: Int, count: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = segmentShape(index, count)
    val color = MaterialTheme.colorScheme.surfaceBright
    val m = modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 3.dp)
    if (onClick != null) Surface(onClick = onClick, modifier = m, shape = shape, color = color) { content() }
    else Surface(m, shape = shape, color = color) { content() }
}

/** Small numbered SIM badge, coloured per slot like the system SIM picker. */
@Composable
fun SimBadge(slot: Int, modifier: Modifier = Modifier) {
    val color = if (slot % 2 == 0) Color(0xFFC2185B) else Color(0xFF00796B)
    Box(modifier.size(width = 18.dp, height = 22.dp).clip(RoundedCornerShape(topStart = 2.dp, topEnd = 7.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
        .background(color), contentAlignment = Alignment.Center) {
        Text((slot + 1).toString(), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

/** Centered empty / error state with an optional action. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}

/** Rounded section container, as used throughout current Google apps' settings-style screens. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(vertical = 8.dp)) { content() }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 4.dp))
}

/** 0–4 signal bars with a spoken description. */
@Composable
fun SignalBars(level: Int?, label: String) {
    val l = level ?: -1
    Row(Modifier.semantics { contentDescription = label }, verticalAlignment = Alignment.Bottom) {
        for (i in 0 until 4) {
            val on = i < l
            Box(Modifier.padding(end = 2.dp).width(4.dp).height((6 + i * 4).dp).clip(RoundedCornerShape(1.dp))
                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
        }
    }
}

/** Small label used for SIM names and states. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    Surface(modifier, shape = RoundedCornerShape(50), color = if (strong) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** The six-digit pairing code: large, monospaced, grouped 3+3, always left-to-right, read digit by digit by TalkBack. */
@Composable
fun SasCodeView(sas: String) {
    val spoken = sas.toCharArray().joinToString(" ")
    // One isolated left-to-right run, so a right-to-left layout can't swap the two groups.
    Text(Format.ltr(Format.digits(sas.take(3)) + "\u2002" + Format.digits(sas.drop(3))),
        Modifier.fillMaxWidth().semantics { contentDescription = spoken }, textAlign = TextAlign.Center,
        fontSize = 40.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
}

/** Studio and project links, shown in the app's About section. Opening them is the user's choice, in their browser. */
object About {
    const val STUDIO_URL = "https://virgule.studio"
    const val SOURCE_URL = "https://github.com/amirhosseintowfighi/pol"
}

/**
 * About: credit to Virgule with a link to virgule.studio, the licence and the source code. The app itself makes no
 * network request: tapping a link hands it to the browser.
 */
@Composable
fun AboutCard(version: String) {
    val creditPrefix = stringResource(R.string.about_credit_prefix)
    val studioName = stringResource(R.string.about_studio)
    val license = stringResource(R.string.about_license)
    val sourceLabel = stringResource(R.string.about_source)
    val appName = stringResource(R.string.app_name)
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val credit = buildAnnotatedString {
        append(creditPrefix); append(" ")
        withLink(LinkAnnotation.Url(About.STUDIO_URL, linkStyle)) { append(studioName) }
        append(" · ")
        withLink(LinkAnnotation.Url(About.STUDIO_URL, linkStyle)) { append("virgule.studio") }
    }
    val source = buildAnnotatedString {
        append(license); append(" · ")
        withLink(LinkAnnotation.Url(About.SOURCE_URL, linkStyle)) { append(sourceLabel) }
    }
    SectionCard {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(appName + if (version.isNotEmpty()) " " + Format.digits(version) else "", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(credit, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
