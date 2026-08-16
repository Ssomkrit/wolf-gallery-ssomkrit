package dev.encgallery

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.encgallery.gallery.ImportController
import dev.encgallery.logging.EncLog
import dev.encgallery.session.SessionState
import dev.encgallery.settings.AppSettings
import dev.encgallery.ui.theme.EncGalleryTheme

private enum class ShareOutcome { LOCKED, EMPTY, IMPORTING }

class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!AppSettings.screenshotsAllowed.value) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        val uris = extractUris(intent)

        SessionState.lockIfExpired("share arrived after the timeout")
        val unlocked = SessionState.unlocked.value

        val outcome = when {
            !unlocked -> ShareOutcome.LOCKED
            uris.isEmpty() -> ShareOutcome.EMPTY
            else -> ShareOutcome.IMPORTING
        }

        EncLog.i("Share", "received ${uris.size} uri(s), unlocked=$unlocked, outcome=$outcome")

        if (outcome == ShareOutcome.IMPORTING) {
            ImportController.start(applicationContext, uris, null, askOnConflict = false)
        }

        setContent {
            val variant by AppSettings.themeVariant
            val lightAccent by AppSettings.lightAccent
            val darkAccent by AppSettings.darkAccent
            EncGalleryTheme(
                variant = variant,
                lightAccent = lightAccent,
                darkAccent = darkAccent
            ) {
                ShareResultScreen(outcome = outcome, onClose = { finish() })
            }
        }
    }

    private fun extractUris(source: Intent?): List<Uri> {
        if (source == null) return emptyList()
        val collected = when (source.action) {
            Intent.ACTION_SEND -> listOfNotNull(parcelableUri(source, Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> parcelableUriList(source, Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        return collected.distinct()
    }

    @Suppress("DEPRECATION")
    private fun parcelableUri(source: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            source.getParcelableExtra(key, Uri::class.java)
        } else {
            source.getParcelableExtra(key)
        }

    @Suppress("DEPRECATION")
    private fun parcelableUriList(source: Intent, key: String): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            source.getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
        } else {
            source.getParcelableArrayListExtra<Uri>(key).orEmpty()
        }
}

@Composable
private fun ShareResultScreen(
    outcome: ShareOutcome,
    onClose: () -> Unit
) {
    val importing = ImportController.importing
    val hasSummary = ImportController.hasSummary
    val successful = ImportController.lastSuccessful
    val failed = ImportController.lastFailed

    LaunchedEffect(importing, hasSummary) {
        if (outcome == ShareOutcome.IMPORTING && !importing && hasSummary) {
            onClose()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(0.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.share_target_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))

                val message = when (outcome) {
                    ShareOutcome.LOCKED -> stringResource(R.string.share_target_locked)
                    ShareOutcome.EMPTY -> stringResource(R.string.share_target_empty)
                    ShareOutcome.IMPORTING -> when {
                        importing -> stringResource(R.string.share_target_importing)
                        failed > 0 -> stringResource(
                            R.string.share_target_partial,
                            successful,
                            failed
                        )
                        else -> stringResource(R.string.share_target_done, successful)
                    }
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                if (outcome == ShareOutcome.IMPORTING && importing) {
                    Spacer(Modifier.height(20.dp))
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

                Spacer(Modifier.height(20.dp))
                TextButton(onClick = onClose) {
                    Text(
                        text = stringResource(R.string.share_target_close),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
