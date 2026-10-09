package info.bigwizardmedia.firecast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import info.bigwizardmedia.firecast.discovery.DiscoverySource
import info.bigwizardmedia.firecast.discovery.ReceiverDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    vm: CastViewModel,
    onDeviceChosen: () -> Unit,
    onPickFile: () -> Unit,
) {
    val devices by vm.devices.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val notice by vm.notice.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("8090") }
    var urlField by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.startScan() }
    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it); vm.clearNotice() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FireCast — Receivers") },
                actions = {
                    if (scanning) {
                        CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                    } else {
                        IconButton(onClick = { vm.startScan() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Rescan")
                        }
                    }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Pick a TV on your Wi-Fi, then cast. Receivers appear automatically via DIAL/mDNS.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (devices.isEmpty() && !scanning) {
                Text(
                    "No receivers found yet. Make sure the TV app is installed and both devices share the same Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(devices, key = { it.id }) { device ->
                    ReceiverRow(device) {
                        vm.connect(device)
                        onDeviceChosen()
                    }
                }
            }

            // Manual IP fallback (PROTOCOL.md §1c)
            Text("Add receiver manually", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = manualHost,
                    onValueChange = { manualHost = it },
                    label = { Text("IP / hostname") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = manualPort,
                    onValueChange = { manualPort = it },
                    label = { Text("Port") },
                    modifier = Modifier.width(96.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    vm.addManualDevice(manualHost, manualPort)
                    onDeviceChosen()
                }) { Text("Add") }
            }

            // Quick actions: file picker + paste URL
            Text("Cast without picking first", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickFile) { Text("Pick video file") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = urlField,
                    onValueChange = { urlField = it },
                    label = { Text("Paste video URL") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val url = urlField.trim()
                    if (url.isNotEmpty()) {
                        vm.playRemoteUrl(url)
                        urlField = ""
                    }
                }) { Text("Cast") }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ReceiverRow(device: ReceiverDevice, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when (device.source) {
                    DiscoverySource.MANUAL -> Icons.Filled.Cast
                    else -> Icons.Filled.Tv
                },
                contentDescription = null,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${device.host}:${device.wsPort} · ${device.source.name.lowercase()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
