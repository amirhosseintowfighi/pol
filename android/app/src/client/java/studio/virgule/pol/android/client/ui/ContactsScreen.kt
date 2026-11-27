package studio.virgule.pol.android.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.virgule.pol.android.R
import studio.virgule.pol.android.client.ClientRepository
import studio.virgule.pol.android.common.Format
import studio.virgule.pol.android.ui.Avatar
import studio.virgule.pol.android.ui.EmptyState
import studio.virgule.pol.android.ui.SegmentCard
import studio.virgule.pol.core.client.ClientSnapshot
import studio.virgule.pol.core.client.numberKey
import studio.virgule.pol.core.domain.Contact

/**
 * Contacts, or the Phone app's "Search contacts" screen when [search] is true: a search field in the top bar and
 * the results as rounded cards with a call button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(repo: ClientRepository, snap: ClientSnapshot, snackbar: SnackbarHostState, search: Boolean, onBack: () -> Unit,
                   openThread: (Long, String) -> Unit) {
    val dial = rememberDialer(repo, snap, snackbar)
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<Contact?>(null) }
    val focus = remember { FocusRequester() }
    if (search) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val q = query.trim()
    val digits = q.filter(Char::isDigit)
    val suggestedLabel = stringResource(R.string.suggested)
    val list = if (q.isEmpty()) snap.contacts
        else snap.contacts.filter { c -> c.name.contains(q, true) || (digits.isNotEmpty() && c.phones.any { it.number.filter(Char::isDigit).contains(digits) }) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = {
                    if (search) TextField(query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                        placeholder = { Text(stringResource(R.string.search_contacts)) },
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                    else Text(stringResource(R.string.tab_contacts))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (search) HorizontalDivider(Modifier.padding(pad))
        when {
            snap.status?.caps?.contacts != true && !snap.demo ->
                EmptyState(Icons.Outlined.People, stringResource(R.string.contacts_off_title), stringResource(R.string.contacts_off_body), Modifier.padding(pad))
            list.isEmpty() -> EmptyState(Icons.Outlined.People, stringResource(R.string.no_results), stringResource(R.string.contacts_empty_body), Modifier.padding(pad))
            else -> LazyColumn(Modifier.padding(pad).fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
                val groups: Map<String, List<Contact>> =
                    if (search) mapOf(suggestedLabel to list) else list.groupBy { it.name.take(1).uppercase() }.toSortedMap()
                for ((label, group) in groups) {
                    item(key = "h:$label") { GroupLabel(label) }
                    itemsIndexed(group, key = { _, c -> c.id }) { i, c ->
                        val p = c.phones.firstOrNull()
                        SegmentCard(i, group.size, onClick = { open = c }) {
                            ContactCallRow(c.name, p?.let { phoneDetail(it) }, onCall = p?.let { { dial(it.number, null) } })
                        }
                    }
                }
            }
        }
    }

    open?.let { c ->
        ModalBottomSheet(onDismissRequest = { open = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(c.name, size = 80)
                Text(c.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp, bottom = 16.dp))
                c.phones.forEachIndexed { i, p ->
                    SegmentCard(i, c.phones.size) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(Format.ltr(p.number), style = MaterialTheme.typography.titleMedium)
                                p.label?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            IconButton(onClick = { open = null; dial(p.number, null) }) { Icon(Icons.Outlined.Call, stringResource(R.string.call_x, c.name)) }
                            IconButton(onClick = {
                                val t = snap.messages.values.firstOrNull { numberKey(it.address) == numberKey(p.number) }?.thread ?: 0L
                                open = null; openThread(t, p.number)
                            }) { Icon(Icons.AutoMirrored.Outlined.Message, stringResource(R.string.message_x, c.name)) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
