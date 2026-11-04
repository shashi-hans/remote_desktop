package rdpmobile.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Home-screen Tailscale status, with both sign-in options while signed out. */
@Composable
fun TailnetCard(status: TailnetStatus) {
    when (status) {
        TailnetStatus.Running -> Text(
            "● Tailscale connected",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
        )
        TailnetStatus.Starting -> Text("Connecting to Tailscale…", style = MaterialTheme.typography.bodySmall)
        is TailnetStatus.Error -> Card(Modifier.fillMaxWidth()) {
            Text("Tailscale: ${status.message}", modifier = Modifier.padding(16.dp))
        }
        is TailnetStatus.NeedsLogin -> SignInCard(status)
    }
}

@Composable
private fun SignInCard(status: TailnetStatus.NeedsLogin) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sign in to Tailscale with the same account as the laptop. This is needed once.")
            if (status.error.isNotBlank()) {
                Text(status.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { Tailnet.openLogin(context, status.url) },
                enabled = status.url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (status.url.isBlank()) "Getting sign-in link…" else "Sign in to Tailscale")
            }
        }
    }
}

/** Settings-screen button that signs this phone out of the tailnet. */
@Composable
fun TailscaleSignOut() {
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Text("Tailscale", style = MaterialTheme.typography.titleMedium)
    OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Sign out of Tailscale")
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Sign out of Tailscale?") },
            text = { Text("The app can no longer reach the laptop until you sign in again, with the same or another account.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        message = runCatching { Tailnet.logout() }
                            .fold({ "Signed out. Sign in again from the home screen." }, { it.message ?: "Sign-out failed" })
                    }
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
