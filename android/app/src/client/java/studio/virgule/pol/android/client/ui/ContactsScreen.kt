package studio.virgule.pol.android.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.virgule.pol.android.R
import studio.virgule.pol.android.client.ClientRepository
import studio.virgule.pol.android.common.Format
import studio.virgule.pol.android.ui.Avatar
import studio.virgule.pol.android.ui.EmptyState
import studio.virgule.pol.core.client.ClientSnapshot
import studio.virgule.pol.core.client.numberKey
import studio.virgule.pol.core.domain.Contact
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(repo: ClientRepository, snap: ClientSnapshot, snackbar: SnackbarHostState, openThread: (Long, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<Contact?>(null) }
    val list = remember(snap.contacts, query) {
        val q = query.trim()
        if (q.isEmpty()) snap.contacts else snap.contacts.filter { c -> c.name.contains(q, true) || c.phones.any { it.number.filter(Char::isDigit).contains(q.filter(Char::isDigit).ifEmpty { "\u0000" }) } }
    }
    Column(Modifier.fillMaxSize()) {
        DockedSearchBar(
            inputField = {
                SearchBarDefaults.InputField(query = query, onQueryChange = { query = it }, onSearch = {}, expanded = false, onExpandedChange = {},
                    placeholder = { Text(stringResource(R.string.search_contacts)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
            },
            expanded = false, onExpandedChange = {}, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {}
        when {
            snap.status?.caps?.contacts != true && !snap.demo ->
                EmptyState(Icons.Outlined.People, stringResource(R.string.contacts_off_title), stringResource(R.string.contacts_off_body))
            list.isEmpty() -> EmptyState(Icons.Outlined.People, stringResource(R.string.no_results), stringResource(R.string.contacts_empty_body))
            else -> LazyColumn {
                val groups = if (query.isEmpty()) list.groupBy { it.name.take(1).uppercase() } else mapOf("" to list)
                for ((letter, group) in groups) {
                    if (letter.isNotEmpty()) item(key = "h:$letter") {
                        Text(letter, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 24.dp, top = 12.dp))
                    }
                    items(group, key = { it.id }) { c ->
                        ListItem(modifier = Modifier.clickable { open = c }, leadingContent = { Avatar(c.name) },
                            headlineContent = { Text(c.name) },
                            supportingContent = { c.phones.firstOrNull()?.let { Text(Format.ltr(it.number)) } })
                    }
                }
            }
        }
    }

    open?.let { c ->
        ModalBottomSheet(onDismissRequest = { open = null }) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Row { Avatar(c.name, size = 56); Spacer(Modifier.padding(8.dp)); Text(c.name, style = MaterialTheme.typography.headlineSmall) }
                Spacer(Modifier.height(12.dp))
                for (p in c.phones) ListItem(
                    headlineContent = { Text(Format.ltr(p.number)) },
                    supportingContent = { p.label?.let { Text(it) } },
                    trailingContent = {
                        Row {
                            IconButton(onClick = {
                                resultText(ctx, repo.dial(p.number, defaultVoiceSub(snap)))?.let { scope.launch { snackbar.showSnackbar(it) } }
                                open = null
                            }) { Icon(Icons.Outlined.Call, stringResource(R.string.call_x, c.name)) }
                            IconButton(onClick = {
                                val t = snap.messages.values.firstOrNull { numberKey(it.address) == numberKey(p.number) }?.thread ?: 0L
                                open = null; openThread(t, p.number)
                            }) { Icon(Icons.AutoMirrored.Outlined.Message, stringResource(R.string.message_x, c.name)) }
                        }
                    },
                )
            }
        }
    }
}
