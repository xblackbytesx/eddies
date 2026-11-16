package com.eddies.app.feature.lock

import com.eddies.app.R
import androidx.compose.ui.res.stringResource
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executor

/**
 * The app-lock gate.
 *
 * Prompts immediately on arrival rather than waiting for a tap: the user opened
 * a locked app, so the intent to unlock is already established and an extra
 * button press is friction with no security value. The button is the retry path
 * for a cancelled or failed prompt.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var prompting by remember { mutableStateOf(false) }
    // Read here because authenticate() below is an ordinary function.
    val noPrompt = stringResource(R.string.lock_no_prompt)
    val promptTitle = stringResource(R.string.lock_prompt_title)
    val promptSubtitle = stringResource(R.string.lock_prompt_subtitle)

    fun authenticate() {
        val activity = context.findFragmentActivity()
        if (activity == null) {
            // Without a FragmentActivity there is no prompt to show. Failing open
            // would silently disable the lock, so it fails closed and says why.
            error = noPrompt
            return
        }
        val manager = BiometricManager.from(context)
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (manager.canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No enrolled biometric and no device credential means the lock
            // cannot be enforced; letting the user in beats bricking the app.
            onUnlocked()
            return
        }
        prompting = true
        val executor: Executor = androidx.core.content.ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(
            activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    onUnlocked()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    prompting = false
                    error = message.toString()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(promptTitle)
                .setSubtitle(promptSubtitle)
                .setAllowedAuthenticators(allowed)
                .build(),
        )
    }

    LaunchedEffect(Unit) { authenticate() }

    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(16.dp))
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleLarge)
        error?.let {
            Spacer(Modifier.size(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.size(24.dp))
        Button(onClick = { authenticate() }, enabled = !prompting) { Text(stringResource(R.string.lock_unlock)) }
    }
}

private tailrec fun android.content.Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is android.content.ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
