package studio.virgule.pol.android.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Brand = Color(0xFF3A4CC0)

/** Material 3 with the wallpaper-based dynamic colour Android users expect; a calm teal fallback before Android 12. */
@Composable
fun PolTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF8BD5BF), secondary = Color(0xFFB1CCC3))
        else -> lightColorScheme(primary = Brand, secondary = Color(0xFF4B635B))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
