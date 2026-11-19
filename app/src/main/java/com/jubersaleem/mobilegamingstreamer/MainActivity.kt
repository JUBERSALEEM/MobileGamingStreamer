package com.jubersaleem.mobilegamingstreamer

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jubersaleem.mobilegamingstreamer.capture.ScreenCaptureService

private val Background = Color(0xFF080C11)
private val Panel = Color(0xFF121A23)
private val PanelSoft = Color(0xFF17222D)
private val Accent = Color(0xFFC4FF5C)
private val Muted = Color(0xFF91A0AE)
private val Cyan = Color(0xFF63D8E8)

class MainActivity : ComponentActivity() {
    private var captureStatus by mutableStateOf("Ready to capture")
    private var captureActive by mutableStateOf(false)

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                startForegroundService(serviceIntent)
                captureStatus = "Capture starting"
                captureActive = true
            } else {
                captureStatus = "Permission not granted"
                captureActive = false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    onPrimary = Color(0xFF11170A),
                    background = Background,
                    surface = Panel,
                    onBackground = Color(0xFFF3F6F8),
                    onSurface = Color(0xFFF3F6F8),
                    secondary = Cyan
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Background) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Header()
                        HeroCard(
                            captureActive = captureActive,
                            captureStatus = captureStatus
                        )
                        CaptureControls(
                            captureActive = captureActive,
                            onStart = { requestScreenCapture() },
                            onStop = {
                                startService(
                                    Intent(this@MainActivity, ScreenCaptureService::class.java)
                                        .setAction(ScreenCaptureService.ACTION_STOP)
                                )
                                captureStatus = "Stop requested"
                                captureActive = false
                            }
                        )
                        SectionHeading("STUDIO MODULES", "Development status")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ModuleCard(
                                modifier = Modifier.weight(1f),
                                title = "Screen",
                                subtitle = if (captureActive) "Session active" else "Capture ready",
                                status = if (captureActive) "ACTIVE" else "READY",
                                accent = Accent
                            )
                            ModuleCard(
                                modifier = Modifier.weight(1f),
                                title = "Microphone",
                                subtitle = "Not connected",
                                status = "PLANNED",
                                accent = Cyan
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ModuleCard(
                                modifier = Modifier.weight(1f),
                                title = "Encoder",
                                subtitle = if (captureActive) "H.264 surface encoder" else "H.264 hardware",
                                status = if (captureActive) "ENCODING" else "READY",
                                accent = Cyan
                            )
                            ModuleCard(
                                modifier = Modifier.weight(1f),
                                title = "Broadcast",
                                subtitle = "RTMP not added",
                                status = "PLANNED",
                                accent = Cyan
                            )
                        }
                        NextStepCard()
                        Footer()
                    }
                }
            }
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }
}

@androidx.compose.runtime.Composable
private fun Header() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(Accent, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("M", color = Color(0xFF11170A), fontSize = 23.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text("MOBILE GAMING", fontSize = 10.sp, color = Muted, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Text("STREAMER", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            }
        }
        Box(
            modifier = Modifier
                .background(PanelSoft, CircleShape)
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text("DEV  0.2.0", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@androidx.compose.runtime.Composable
private fun HeroCard(captureActive: Boolean, captureStatus: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Panel)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1C2B32), Color(0xFF14202A), Color(0xFF111821))
                    )
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("YOUR STREAMING STUDIO", color = Muted, fontSize = 10.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .background(if (captureActive) Accent else Muted, CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (captureActive) "CAPTURING" else "STANDBY", color = if (captureActive) Accent else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    if (captureActive) "Capture\nis running." else "Ready when\nyou are.",
                    fontSize = 32.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    captureStatus,
                    color = Muted,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "SCREEN CAPTURE  /  ANDROID",
                    color = Accent,
                    fontSize = 9.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CaptureControls(
    captureActive: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onStart,
            enabled = !captureActive,
            modifier = Modifier.weight(1.35f).height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                contentColor = Color(0xFF10160A),
                disabledContainerColor = Color(0xFF46513A),
                disabledContentColor = Color(0xFFB5C0A8)
            )
        ) {
            Text("START CAPTURE", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
        }
        Button(
            onClick = onStop,
            enabled = captureActive,
            modifier = Modifier.weight(0.8f).height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PanelSoft,
                contentColor = Color.White,
                disabledContainerColor = Panel,
                disabledContentColor = Muted
            )
        ) {
            Text("STOP", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@androidx.compose.runtime.Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = Accent, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@androidx.compose.runtime.Composable
private fun ModuleCard(
    modifier: Modifier,
    title: String,
    subtitle: String,
    status: String,
    accent: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(19.dp),
        colors = CardDefaults.cardColors(containerColor = Panel)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(7.dp).background(accent, CircleShape))
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
            Text(status, color = accent, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@androidx.compose.runtime.Composable
private fun NextStepCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = PanelSoft)
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("NEXT ENGINEERING MILESTONE", color = Accent, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            Text("Hardware video encoder", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                "H.264 hardware encoding is now connected to screen capture. Encoded frames are measured for testing; RTMP broadcast is the next milestone.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun Footer() {
    Text(
        "EARLY ACCESS BUILD  •  NO RECORDING OR LIVE BROADCAST YET",
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        color = Color(0xFF65727F),
        fontSize = 9.sp,
        letterSpacing = 0.6.sp
    )
}
