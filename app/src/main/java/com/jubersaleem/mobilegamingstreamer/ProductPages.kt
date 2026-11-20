package com.jubersaleem.mobilegamingstreamer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsPage(
    themeMode: String,
    onThemeChange: (String) -> Unit,
    onTerms: () -> Unit,
    onPrivacy: () -> Unit
) {
    val p = LocalMgsPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("SETTINGS", "Personalise your streaming studio")
        MgsCard(
            shape = RoundedCornerShape(if (p.isClay) 32.dp else 26.dp),
            border = BorderStroke(1.dp, p.edge),
            elevation = CardDefaults.cardElevation(defaultElevation = if (p.isClay) 24.dp else 12.dp),
            colors = CardDefaults.cardColors(containerColor = p.surface)
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("APPEARANCE", color = p.primary, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
                Text("Choose the surface style used throughout the app.", color = p.muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    FilterChip(
                        selected = themeMode == "CLASSIC",
                        onClick = { onThemeChange("CLASSIC") },
                        label = { Text("Classic", fontWeight = FontWeight.SemiBold) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = themeMode == "CLAY",
                        onClick = { onThemeChange("CLAY") },
                        label = { Text("Claymorphism", fontWeight = FontWeight.SemiBold) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (themeMode == "CLAY") "Original Claymorphism is active" else "Classic is active", color = p.text, fontWeight = FontWeight.Bold)
                        Text(if (themeMode == "CLAY") "Sculpted 3D surfaces, deep soft shadows, raised highlights and sculpted rounded controls." else "The original dark studio with neon lime and cyan accents.", color = p.muted, fontSize = 11.sp)
                    }
                    MgsCard(
                        modifier = Modifier.size(62.dp),
                        shape = RoundedCornerShape(if (p.isClay) 24.dp else 19.dp),
                        border = BorderStroke(1.dp, p.edge),
                        elevation = CardDefaults.cardElevation(defaultElevation = if (p.isClay) 18.dp else 9.dp),
                        colors = CardDefaults.cardColors(containerColor = p.highlight)
                    ) { }
                }
            }
        }
        MgsCard(
            shape = RoundedCornerShape(if (p.isClay) 30.dp else 24.dp),
            border = BorderStroke(1.dp, p.edge),
            elevation = CardDefaults.cardElevation(defaultElevation = 9.dp),
            colors = CardDefaults.cardColors(containerColor = p.surface)
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LEGAL & PRIVACY", color = p.primary, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.3.sp)
                Text("Read how streaming, microphone access, screen capture and app permissions work.", color = p.muted, fontSize = 12.sp)
                OutlinedButton(onClick = onPrivacy, modifier = Modifier.fillMaxWidth()) { Text("PRIVACY POLICY") }
                OutlinedButton(onClick = onTerms, modifier = Modifier.fillMaxWidth()) { Text("TERMS & CONDITIONS") }
            }
        }
        Text("Theme preference is saved on this device. No account is required.", color = p.muted, fontSize = 10.sp)
    }
}

@Composable
fun AboutPage(onTerms: () -> Unit, onPrivacy: () -> Unit) {
    val p = LocalMgsPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("ABOUT THE APP", "Built for mobile gamers who stream")
        MgsCard(
            shape = RoundedCornerShape(if (p.isClay) 34.dp else 28.dp),
            border = BorderStroke(1.dp, p.edge),
            elevation = CardDefaults.cardElevation(defaultElevation = if (p.isClay) 26.dp else 13.dp),
            colors = CardDefaults.cardColors(containerColor = p.surface)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("MOBILE GAMING", color = p.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("STREAMER", color = p.text, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Text("A mobile-first RTMP streaming studio for sharing gameplay with your community.", color = p.muted, fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(3.dp))
                Text("DEVELOPED BY", color = p.primary, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
                Text("Juber Saleem", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text("Independent Android app developer • BGMI / PUBG Mobile streamer", color = p.muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        MgsCard(shape = RoundedCornerShape(if (p.isClay) 30.dp else 24.dp), border = BorderStroke(1.dp, p.edge), elevation = CardDefaults.cardElevation(defaultElevation = if (p.isClay) 18.dp else 1.dp), colors = CardDefaults.cardColors(containerColor = p.surface)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("OUR APPROACH", color = p.primary, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
                Text("Simple controls, clear capture consent and transparent streaming status. The app is under active development; compatibility and audio capture depend on Android, the device, the game and the streaming platform.", color = p.text, fontSize = 12.sp, lineHeight = 18.sp)
                Text("Mobile Gaming Streamer is an independent product and is not affiliated with, endorsed by or sponsored by Krafton, Tencent, BGMI, PUBG MOBILE or Google. All third-party names and marks belong to their respective owners.", color = p.muted, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
        OutlinedButton(onClick = onPrivacy, modifier = Modifier.fillMaxWidth()) { Text("PRIVACY POLICY") }
        OutlinedButton(onClick = onTerms, modifier = Modifier.fillMaxWidth()) { Text("TERMS & CONDITIONS") }
        Text("Developer project: github.com/JUBERSALEEM/MobileGamingStreamer", color = p.muted, fontSize = 10.sp)
    }
}

@Composable
fun LegalPage(title: String, body: String, onBack: () -> Unit) {
    val p = LocalMgsPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onBack) { Text("← SETTINGS") }
        PageHeading(title.uppercase(), "Mobile Gaming Streamer • Version 0.5.10")
        MgsCard(
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, p.edge),
            elevation = CardDefaults.cardElevation(defaultElevation = 9.dp),
            colors = CardDefaults.cardColors(containerColor = p.surface)
        ) {
            Text(body, modifier = Modifier.fillMaxWidth().padding(18.dp), color = p.text, fontSize = 12.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun PageHeading(eyebrow: String, subtitle: String) {
    val p = LocalMgsPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(eyebrow, color = p.primary, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
        Text(subtitle, color = p.muted, fontSize = 12.sp)
    }
}

const val TermsText = """
Last updated: 20 November 2026

1. About the app
Mobile Gaming Streamer is an independent Android application developed by Juber Saleem. It provides tools to capture a user-selected screen or app and send a live audiovisual stream to an RTMP destination chosen by the user.

2. Your responsibility
You are responsible for the stream URL, stream key, content, gameplay, audio, chat and destination account that you use. Keep your stream key private. Do not stream content unless you have the rights and permissions required to do so. Obtain appropriate consent before broadcasting another person's voice or other personal information.

3. Platform and game rules
You must follow the terms, community rules, copyright requirements and applicable laws of the game, streaming service and any other service you use. This app does not grant rights to game content, music, chat, logos or other third-party material.

4. Permissions and user control
Screen capture starts only after Android's system confirmation. Microphone access requires Android permission. The optional floating control requires Display over other apps permission. The Accessibility service is used for microphone concurrency during gameplay; it is not intended to inspect screen content or automate gameplay. You can disable permissions or stop a stream at any time in Android settings or in the app.

5. Availability and limitations
The app is provided on an evolving development basis. Compatibility, video quality, audio capture, network delivery and uptime are not guaranteed. Android, device manufacturers, games and streaming platforms may restrict capture or change their behaviour.

6. Acceptable use
Do not use the app for unlawful streaming, harassment, privacy violations, copyright infringement, cheating, circumvention of platform restrictions or any activity that harms others.

7. Third-party services
RTMP destinations, YouTube chat and other third-party services are governed by their own terms and privacy policies. We do not control their availability, moderation, retention or processing.

8. Disclaimer and liability
To the maximum extent permitted by applicable law, the app is provided “as is” and “as available”, without warranties of uninterrupted service or fitness for a particular purpose. Nothing in these terms excludes liability that cannot legally be excluded.

9. Changes
These terms may be updated as the app develops. The latest version will be shown in the app and on the project's published policy page.

10. Contact
For app support or policy questions, use the project's issue tracker:
https://github.com/JUBERSALEEM/MobileGamingStreamer/issues
"""

const val PrivacyText = """
Last updated: 20 November 2026

Developer: Juber Saleem
App: Mobile Gaming Streamer

This policy describes the current development build. It must be reviewed and kept accurate whenever app code, SDKs, destinations or data practices change.

1. Information handled by the app
• Screen video: Android MediaProjection captures the screen or app window you explicitly select after Android displays its system consent prompt.
• Microphone audio: if you choose a microphone or mixed audio mode, the app uses the microphone after Android permission is granted. Audio may include your voice and nearby sounds.
• Internal playback audio: if you choose game or mixed audio, the app requests Android playback capture. Android and the game can restrict which audio is capturable; teammate voice chat is not guaranteed to be included.
• Stream destination and key: the RTMP/RTMPS server address and stream key you enter are used to establish the stream. The key is held in app memory for the active app session and is not intentionally saved to app storage in this build.
• Optional overlay: if enabled, the floating control displays stream status and controls over other apps.
• Accessibility service: the service supports microphone capture while a game uses voice chat. It does not retrieve window content, read text on screen, inspect UI nodes, click controls or automate gameplay. It remains enabled until you turn it off in Android Settings, but has no active accessibility event subscriptions and does not read screen content, inspect UI controls or automate gameplay.
• Optional YouTube chat: if you enter a video ID and open chat, a WebView loads YouTube. YouTube may process information under Google's own terms and privacy policy.

2. How streaming works
When you start a broadcast, selected screen video and selected audio are sent over your network to the RTMP/RTMPS endpoint you entered. That endpoint and the streaming platform may process, relay, record or make the stream public according to their own settings and policies. Use RTMPS where your provider supports it. Plain RTMP does not encrypt the stream in transit.

3. Local processing and developer collection
The current development build does not require an account and does not include developer-operated analytics, advertising or a developer cloud account service. Theme choice and temporary interface state are stored locally on the device. The developer does not receive a copy of your stream through a separate Mobile Gaming Streamer server. Your chosen streaming provider receives the stream.

4. Permissions
• RECORD_AUDIO: microphone audio when the selected audio mode needs it.
• MediaProjection consent: screen/app video and eligible internal playback audio for a broadcast you initiate.
• Accessibility service: microphone concurrency support during gameplay as described above.
• Display over other apps: optional floating controls.
• Notifications: persistent broadcast status and stop control.
• Internet: connect to your selected RTMP/RTMPS destination and load optional YouTube chat.

5. Sharing and retention
Audiovisual content is sent to the destination you choose. The destination provider's collection, retention, access and deletion practices are controlled by that provider. The app does not operate a separate stream archive or developer-operated analytics backend in this build. Android may retain system diagnostics according to device settings.

6. Security
Keep your stream key confidential and use a secure RTMPS endpoint where available. Anyone with access to your stream key may be able to broadcast to your channel. Do not share sensitive information on screen or in voice chat.

7. Children
This app is not designed specifically for children. Parents and guardians should supervise minors' streaming, microphone use and sharing of personal information.

8. Your choices
You can stop capture from the app, floating control or Android notification. You can revoke microphone, notification, overlay or Accessibility permissions in Android settings. Uninstalling the app removes its local app data, subject to Android behaviour.

9. Changes to this policy
This policy will be updated if app functionality, SDKs or data practices change. The Play Store Data safety form must describe the exact release build and every included SDK.

10. Contact
For privacy questions or support, use:
https://github.com/JUBERSALEEM/MobileGamingStreamer/issues
"""
