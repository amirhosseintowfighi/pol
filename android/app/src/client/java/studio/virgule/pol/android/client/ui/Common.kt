package studio.virgule.pol.android.client.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import studio.virgule.pol.android.R
import studio.virgule.pol.core.client.ClientModel
import studio.virgule.pol.core.client.ClientSnapshot

/** Maps a model result to user-facing text, or null when there is nothing to say. */
fun resultText(ctx: Context, r: ClientModel.ActionResult): String? = when (r) {
    ClientModel.ActionResult.Ok -> null
    ClientModel.ActionResult.NotConnected -> ctx.getString(R.string.err_not_connected)
    ClientModel.ActionResult.Invalid -> ctx.getString(R.string.err_invalid_number)
    ClientModel.ActionResult.Unsupported -> ctx.getString(R.string.err_unsupported)
}

/** Error codes from the gateway (see core Err) mapped to actionable text. */
fun describe(ctx: Context, code: String): String {
    val c = code.substringAfter(':')
    return when (c) {
        "permission" -> ctx.getString(R.string.err_gateway_permission)
        "unsupported" -> ctx.getString(R.string.err_unsupported)
        "invalid" -> ctx.getString(R.string.err_invalid_number)
        "no_service" -> ctx.getString(R.string.err_no_service)
        "busy" -> ctx.getString(R.string.err_busy)
        else -> if (code.startsWith("sms:")) ctx.getString(R.string.err_sms_failed) else ctx.getString(R.string.err_failed)
    }
}

/** Line (SIM) selector shown only when the gateway has more than one usable SIM. */
@Composable
fun SimPicker(snap: ClientSnapshot, selected: Int?, onSelect: (Int?) -> Unit, modifier: Modifier = Modifier) {
    val sims = snap.status?.sims.orEmpty()
    if (sims.size < 2 || snap.status?.caps?.simChoice != true) return
    Row(modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (sim in sims) {
            FilterChip(selected = selected == sim.subId, onClick = { onSelect(sim.subId) },
                label = { Text(sim.label.ifBlank { "SIM ${sim.slot + 1}" }) })
        }
    }
}

fun defaultVoiceSub(s: ClientSnapshot): Int? = s.status?.defaultVoiceSub ?: s.status?.sims?.firstOrNull()?.subId
fun defaultSmsSub(s: ClientSnapshot): Int? = s.status?.defaultSmsSub ?: s.status?.sims?.firstOrNull()?.subId
fun simLabel(s: ClientSnapshot, subId: Int?): String? = s.status?.sims?.firstOrNull { it.subId == subId }?.let { it.label.ifBlank { "SIM ${it.slot + 1}" } }
    ?.takeIf { (s.status?.sims?.size ?: 0) > 1 }
