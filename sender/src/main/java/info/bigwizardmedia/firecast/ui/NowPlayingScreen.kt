package info.bigwizardmedia.firecast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import info.bigwizardmedia.firecast.protocol.FirecastSession
import info.bigwizardmedia.firecast.protocol.PlaybackState
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    vm: CastViewModel,
    onBack: () -> Unit,
) {
    val device by vm.connectedDevice.collectAsState()
    val conn by vm.connectionState.collectAsState()
    val status by vm.status.collectAsState()
    val pinRequired by vm.pinRequired.collectAsState()
    val notice by vm.notice.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var pin by remember { mutableStateOf("") }
    var seekUi by remember { mutableFloatStateOf(-1f) } // -1 = follow receiver

    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it); vm.clearNotice() }
    }
    // Release the seek slider back to the receiver position after a seek.
    LaunchedEffect(status.position) {
        if (seekUi >= 0f) seekUi = -1f
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device?.name ?: "Now Playing") },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.disconnect()
                        onBack()
                    }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                pinRequired -> {
                    Text(
                        "The TV is showing a 4-digit PIN. Enter it to pair this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(4) },
                        label = { Text("PIN") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    Button(
                        onClick = { vm.submitPin(pin) },
                        enabled = pin.length == 4,
                    ) { Text("Pair") }
                }

                conn != FirecastSession.ConnectionState.CONNECTED -> {
                    Text(
                        "Connecting to ${device?.name ?: "receiver"}…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                else -> {
                    Text(
                        status.title ?: "Nothing playing",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "State: ${status.state.name.lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    status.error?.let {
                        Text("Error: $it", color = MaterialTheme.colorScheme.error)
                    }

                    // Seek bar
                    val duration = status.duration.takeIf { it > 0 } ?: 1.0
                    Slider(
                        value = if (seekUi >= 0f) seekUi else status.position.toFloat()
                            .coerceIn(0f, duration.toFloat()),
                        onValueChange = { seekUi = it },
                        onValueChangeFinished = {
                            if (seekUi >= 0f) vm.seekTo(seekUi.toDouble())
                        },
                        valueRange = 0f..duration.toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(formatTime(status.position))
                        Text(formatTime(status.duration))
                    }

                    // Transport controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { vm.stop() }) {
                            Icon(Icons.Filled.Stop, contentDescription = "Stop")
                        }
                        Spacer(Modifier.width(16.dp))
                        IconButton(onClick = { vm.togglePlayPause() }) {
                            Icon(
                                if (status.state == PlaybackState.PLAYING) Icons.Filled.Pause
                                else Icons.Filled.PlayArrow,
                                contentDescription = "Play/Pause",
                            )
                        }
                    }

                    // Volume
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { vm.toggleMute() }) {
                            Icon(
                                if (status.muted) Icons.Filled.VolumeMute else Icons.Filled.VolumeUp,
                                contentDescription = "Mute",
                            )
                        }
                        Slider(
                            value = status.volume,
                            onValueChange = { vm.setVolume(it) },
                            valueRange = 0f..1f,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            vm.disconnect()
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Disconnect") }
                }
            }
        }
    }
}

private fun formatTime(seconds: Double): String {
    if (seconds < 0) return "--:--"
    val total = seconds.roundToLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
