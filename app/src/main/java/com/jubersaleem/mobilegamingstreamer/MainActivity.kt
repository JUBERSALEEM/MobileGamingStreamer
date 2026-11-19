package com.jubersaleem.mobilegamingstreamer

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jubersaleem.mobilegamingstreamer.capture.ScreenCaptureService

class MainActivity : ComponentActivity() {
    private var captureStatus by mutableStateOf("Not capturing")

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                startForegroundService(serviceIntent)
                captureStatus = "Capture session starting"
            } else {
                captureStatus = "Screen capture permission was not granted"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFB8F56A),
                    background = Color(0xFF090D12),
                    surface = Color(0xFF141B23),
                    onPrimary = Color(0xFF10150A),
                    onBackground = Color(0xFFF2F5F7),
                    onSurface = Color(0xFFF2F5F7)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Text(
                            "MOBILE GAMING",
                            color = Color(0xFFB8F56A),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                        Text(
                            "Streamer",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Your Android streaming studio",
                            color = Color(0xFF9AA7B4)
                        )

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF141B23)
                            ),
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(18.dp)) {
                                Text("CAPTURE ENGINE", color = Color(0xFF9AA7B4), fontSize = 11.sp)
                                Spacer(Modifier.height(10.dp))
                                Text(captureStatus, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Milestone 1 · Android screen-capture foundation",
                                    color = Color(0xFF9AA7B4),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { requestScreenCapture() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFB8F56A),
                                    contentColor = Color(0xFF10150A)
                                )
                            ) {
                                Text("Start capture")
                            }
                            Button(
                                onClick = {
                                    startService(
                                        Intent(this@MainActivity, ScreenCaptureService::class.java)
                                            .setAction(ScreenCaptureService.ACTION_STOP)
                                    )
                                    captureStatus = "Capture stop requested"
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Stop")
                            }
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF10161D)),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("UP NEXT", color = Color(0xFFB8F56A), fontSize = 11.sp)
                                Spacer(Modifier.height(8.dp))
                                Text("Video encoder · Audio mixer · RTMP output")
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    "These modules will be added and tested in separate milestones.",
                                    color = Color(0xFF9AA7B4),
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "EARLY DEVELOPMENT BUILD · NOT FOR LIVE BROADCASTING",
                            color = Color(0xFF74808C),
                            fontSize = 10.sp
                        )
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
