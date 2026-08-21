package pro.id4drive.alert

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        ContextCompat.startForegroundService(this, Intent(this, AlertService::class.java))

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AlertScreen(onRequestIgnoreBatteryOptimizations = { requestIgnoreBatteryOptimizations() })
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                },
            )
        }
    }
}

@Composable
private fun AlertScreen(onRequestIgnoreBatteryOptimizations: () -> Unit) {
    val context = LocalContext.current
    val state by AlertState.state.collectAsState()

    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF123C8C), Color(0xFF0A1A3D), Color.Black),
    )

    Box(modifier = Modifier.fillMaxSize().background(backgroundGradient)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            StatusCard(active = state.kyivActive)
            Spacer(Modifier.height(12.dp))
            ConnectionBadge(state.connectionState)
            Spacer(Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { AlertSound.playAlertLoop(context) }) {
                    Text(stringResource(R.string.button_test_alert))
                }
                Button(onClick = { AlertSound.playClearOnce(context) }) {
                    Text(stringResource(R.string.button_test_clear))
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { AlertSound.stop(context) }) {
                Text(stringResource(R.string.button_stop_sound))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRequestIgnoreBatteryOptimizations) {
                Text(stringResource(R.string.button_disable_battery_optimization))
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.disclaimer_unofficial),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.areas_list_title),
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.areas.sortedByDescending { it.active }) { area -> AreaRow(area) }
            }
        }
    }
}

@Composable
private fun StatusCard(active: Boolean) {
    val bg = if (active) Color(0xFFB3261E) else Color(0xFF2E7D32)
    Surface(color = bg, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (active) stringResource(R.string.status_alert) else stringResource(R.string.status_clear),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ConnectionBadge(state: ConnectionState) {
    val text = when (state) {
        ConnectionState.LIVE -> stringResource(R.string.connection_live)
        ConnectionState.CONNECTING -> stringResource(R.string.connection_connecting)
        ConnectionState.RECONNECTING -> stringResource(R.string.connection_reconnecting)
        ConnectionState.FALLBACK_REST -> stringResource(R.string.connection_fallback)
        ConnectionState.FAILED -> stringResource(R.string.connection_failed)
    }
    Text(text = text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AreaRow(area: AlertArea) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = area.title,
                fontWeight = if (area.active) FontWeight.Bold else FontWeight.Normal,
                color = Color.White,
            )
            val subtitle = if (area.type != null) "${area.key} · ${area.type}" else area.key
            Text(text = subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = if (area.active) stringResource(R.string.area_active) else stringResource(R.string.area_inactive),
            color = if (area.active) Color(0xFFFF6B5B) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
