package info.bigwizardmedia.firecast

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import info.bigwizardmedia.firecast.ui.CastViewModel
import info.bigwizardmedia.firecast.ui.DeviceListScreen
import info.bigwizardmedia.firecast.ui.NowPlayingScreen
import info.bigwizardmedia.firecast.ui.PendingMedia
import info.bigwizardmedia.firecast.ui.theme.FirecastTheme

class MainActivity : ComponentActivity() {

    private val vm: CastViewModel by viewModels()

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { vm.playLocalUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            FirecastTheme {
                AppNav(
                    vm = vm,
                    onPickFile = { pickVideo.launch("video/*") },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Share-sheet / open-with entry points:
     * - ACTION_SEND with video/* → the shared file/stream Uri
     * - ACTION_VIEW with video/* or http(s) → file Uri or remote URL
     * The media is stashed as pending and auto-plays once a receiver connects.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                val text: String? = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                when {
                    uri != null -> vm.pendingMedia = PendingMedia.LocalFile(uri, null, intent.type)
                    text != null && looksLikeUrl(text) ->
                        vm.pendingMedia = PendingMedia.RemoteUrl(text.trim(), null)
                }
            }
            Intent.ACTION_VIEW -> {
                val uri: Uri = intent.data ?: return
                if (uri.scheme == "http" || uri.scheme == "https") {
                    vm.pendingMedia = PendingMedia.RemoteUrl(uri.toString(), null)
                } else {
                    vm.pendingMedia = PendingMedia.LocalFile(uri, null, intent.type)
                }
            }
        }
    }

    private fun looksLikeUrl(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("http://") || t.startsWith("https://")
    }
}

@Composable
private fun AppNav(
    vm: CastViewModel,
    onPickFile: () -> Unit,
) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "devices") {
        composable("devices") {
            DeviceListScreen(
                vm = vm,
                onDeviceChosen = { nav.navigate("nowplaying") },
                onPickFile = onPickFile,
            )
        }
        composable("nowplaying") {
            NowPlayingScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
