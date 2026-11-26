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

/** Tonal initials avatar; colour chosen from the name so a person keeps the same colour everywhere. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Int = 44) {
    val c = MaterialTheme.colorScheme
    val palette = listOf(c.primaryContainer to c.onPrimaryContainer, c.secondaryContainer to c.onSecondaryContainer,
        c.tertiaryContainer to c.onTertiaryContainer)
    val (bg, fg) = palette[(name.hashCode() and 0x7fffffff) % palette.size]
    Box(modifier.size(size.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(Format.initials(name), color = fg, style = MaterialTheme.typography.titleMedium)
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
    Row(Modifier.fillMaxWidth().semantics { contentDescription = spoken }, horizontalArrangement = Arrangement.Center) {
        Text(Format.ltr(Format.digits(sas.take(3))), fontSize = 40.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(16.dp))
        Text(Format.ltr(Format.digits(sas.drop(3))), fontSize = 40.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

/** Studio and project links, shown in the app's About section. Opening them is the user's choice, in their browser. */
object About {
    const val STUDIO_URL = "https://virgule.studio"
    const val SOURCE_URL = "https://github.com/amirhosseintowfighi/tel-bridge"
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
