package studio.virgule.pol.android.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import studio.virgule.pol.android.client.ui.HomeScreen
import studio.virgule.pol.android.client.ui.KeypadScreen
import studio.virgule.pol.android.client.ui.NewChatScreen
import studio.virgule.pol.android.common.Format
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.virgule.pol.android.R
import studio.virgule.pol.android.client.ui.ContactsScreen
import studio.virgule.pol.android.client.ui.DeviceScreen
import studio.virgule.pol.android.client.ui.InCallScreen
import studio.virgule.pol.android.client.ui.MessagesScreen
import studio.virgule.pol.android.client.ui.OnboardingScreen
import studio.virgule.pol.android.client.ui.ThreadScreen
import studio.virgule.pol.android.client.ui.describe
import studio.virgule.pol.android.ui.PolTheme
import studio.virgule.pol.core.client.LinkState

class ClientActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Debug builds only: open one screen with demo data for the automated screenshot workflow.
        val screenshot = if (studio.virgule.pol.android.BuildConfig.DEBUG) intent?.getStringExtra("polScreenshot") else null
        setContent { PolTheme { ClientRoot(screenshot) } }
    }

    override fun onStart() { super.onStart(); AppVisibility.foreground = true }
    override fun onStop() { AppVisibility.foreground = false; super.onStop() }
}

/** Where the user is, above the three tabs. A thread is addressed by its id, or by a number for a new conversation. */
sealed interface Detail {
    data class Thread(val thread: Long, val address: String) : Detail
    data object NewChat : Detail
    data object Contacts : Detail
    data object Search : Detail
    data object Gateway : Detail
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ClientRoot(screenshot: String? = null) {
    val ctx = LocalContext.current
    val repo = remember { ClientRepository.get(ctx) }
    val snap by repo.state.collectAsState()
    var onboarding by rememberSaveable { mutableStateOf(repo.gateway == null && !repo.demoMode) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var detail by remember { mutableStateOf<Detail?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(screenshot) {
        when (screenshot) {
            null -> {}
            "pair" -> repo.debugPairingPreview()
            else -> {
                repo.enterDemo(); onboarding = false
                tab = when (screenshot) { "keypad" -> 1; "messages", "thread", "newchat" -> 2; else -> 0 }
                detail = when (screenshot) {
                    "thread" -> Detail.Thread(1, "+15555550101")
                    "contacts" -> Detail.Contacts
                    "search" -> Detail.Search
                    "gateway" -> Detail.Gateway
                    "newchat" -> Detail.NewChat
                    else -> null
                }
                if (screenshot == "incall") { kotlinx.coroutines.delay(600); repo.simulateIncomingCall() }
            }
        }
    }
    LaunchedEffect(snap.link, repo.gateway) {
        if (repo.gateway != null && !snap.demo && repo.hasBluetoothPermissions()) ClientService.start(ctx)
    }
    LaunchedEffect(snap.lastError) {
        val e = snap.lastError ?: return@LaunchedEffect
        snackbar.showSnackbar(describe(ctx, e))
        repo.model.clearError()
    }

    if (onboarding || (snap.link == LinkState.NOT_PAIRED && !snap.demo)) {
        OnboardingScreen(repo, onDone = { onboarding = false })
        return
    }

    // An active or ringing call takes over the screen, like the system dialer.
    if (snap.liveCalls.isNotEmpty()) { InCallScreen(repo, snap); return }

    val openThread: (Long, String) -> Unit = { t, a -> detail = Detail.Thread(t, a) }
    when (val d = detail) {
        is Detail.Thread -> {
            BackHandler { detail = null }
            ThreadScreen(repo, snap, d.thread, d.address, onBack = { detail = null }, snackbar = snackbar)
            return
        }
        Detail.NewChat -> {
            BackHandler { detail = null }
            NewChatScreen(snap, onBack = { detail = null }, openThread = openThread)
            return
        }
        Detail.Contacts, Detail.Search -> {
            BackHandler { detail = null }
            ContactsScreen(repo, snap, snackbar, search = d == Detail.Search, onBack = { detail = null }, openThread = openThread)
            return
        }
        Detail.Gateway -> {
            BackHandler { detail = null }
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.tab_gateway)) },
                        navigationIcon = { IconButton(onClick = { detail = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) { pad -> Column(Modifier.padding(pad)) { DeviceScreen(repo, snap, onUnpaired = { detail = null; onboarding = true }) } }
            return
        }
        null -> {}
    }

    // Phone tabs sit on the grey surface with white cards; Messages is a plain white list, as in Google's apps.
    val bg = if (tab == 2) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = bg,
        bottomBar = {
            NavigationBar(containerColor = bg) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, label = { Text(stringResource(R.string.tab_home)) },
                    icon = { BadgedBox(badge = { if (snap.missedCalls > 0) Badge { Text(Format.digits(snap.missedCalls.coerceAtMost(99).toString())) } }) {
                        Icon(if (tab == 0) Icons.Filled.Home else Icons.Outlined.Home, null) } })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, label = { Text(stringResource(R.string.keypad)) },
                    icon = { Icon(Icons.Filled.Dialpad, null) })
                val unread = snap.conversations.sumOf { it.unread }
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, label = { Text(stringResource(R.string.tab_messages)) },
                    icon = { BadgedBox(badge = { if (unread > 0) Badge { Text(Format.digits(unread.coerceAtMost(99).toString())) } }) {
                        Icon(if (tab == 2) Icons.AutoMirrored.Filled.Chat else Icons.AutoMirrored.Outlined.Chat, null) } })
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            LinkBanner(snap.link, snap.demo)
            when (tab) {
                0 -> HomeScreen(repo, snap, snackbar, openThread = openThread, openContacts = { detail = Detail.Contacts },
                    openSearch = { detail = Detail.Search }, openGateway = { detail = Detail.Gateway })
                1 -> KeypadScreen(repo, snap, snackbar, openThread = openThread)
                else -> MessagesScreen(snap, openThread = openThread, newChat = { detail = Detail.NewChat }, openGateway = { detail = Detail.Gateway })
            }
        }
    }
}

/** Calm, one-line connection notice in a rounded card; hidden while connected to a real gateway. */
@Composable
fun LinkBanner(link: LinkState, demo: Boolean) {
    val text = when {
        demo -> stringResource(R.string.banner_demo)
        link == LinkState.CONNECTED -> return
        link == LinkState.BLUETOOTH_OFF -> stringResource(R.string.banner_bt_off)
        link == LinkState.CONNECTING -> stringResource(R.string.banner_connecting)
        link == LinkState.RECONNECTING -> stringResource(R.string.banner_reconnecting)
        else -> stringResource(R.string.banner_searching)
    }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = RoundedCornerShape(16.dp),
        color = if (demo) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
    }
}
