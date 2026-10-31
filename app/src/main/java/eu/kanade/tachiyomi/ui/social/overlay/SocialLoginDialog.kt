package eu.kanade.tachiyomi.ui.social.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.social.matrix.SocialError

/**
 * P2-10c: debug-grade Matrix login screen.
 *
 * The dialog lives inside the landscape player overlay, so the keyboard can
 * cover most of the available height. The content is scrollable and respects
 * the IME insets, so every field stays reachable on a phone in landscape.
 *
 * TODO(P2-10e): remove this dialog and add a proper Settings > Social > Matrix
 * account screen with logout, MXID display and homeserver change.
 */
@Composable
fun SocialLoginDialog(
    isBusy: Boolean,
    error: SocialError?,
    onLogin: (homeserverUrl: String, userId: String, password: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var homeserver by remember { mutableStateOf("https://matrix.org") }
    var userId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val canSubmit = !isBusy &&
        homeserver.isNotBlank() &&
        userId.isNotBlank() &&
        password.isNotBlank()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F11))
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .padding(horizontal = 24.dp)
                .background(Color(0xFF131A1E), RoundedCornerShape(20.dp))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Connexion Matrix",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Text(
                text = "Compte personnel. Les messages restent chiffres de bout en bout.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB0BEC5),
            )

            Field("Homeserver", homeserver) { homeserver = it }
            Field("Utilisateur", userId) { userId = it }
            Field("Mot de passe", password, isPassword = true) { password = it }

            if (error != null) {
                Text(
                    text = "Erreur : " + error.javaClass.simpleName,
                    color = Color(0xFFEF5350),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = { onLogin(homeserver.trim(), userId.trim(), password) },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.widthIn(max = 18.dp))
                } else {
                    Text("Se connecter")
                }
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Plus tard", color = Color(0xFF90A4AE)) }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    isPassword: Boolean = false,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Color(0xFFB0BEC5), style = MaterialTheme.typography.labelSmall)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B0F11), RoundedCornerShape(8.dp))
                .padding(12.dp),
        )
    }
}
