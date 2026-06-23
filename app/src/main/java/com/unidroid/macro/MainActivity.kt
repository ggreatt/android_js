package com.unidroid.macro

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private val overlayRequestCode = 1001
    private val projectionRequestCode = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF12131A) // Premium Dark Mode background
                ) {
                    MacroConfigScreen(
                        onRequestOverlay = { requestOverlayPermission() },
                        onRequestAccessibility = { requestAccessibilityPermission() },
                        onRequestCapture = { requestScreenCapturePermission() },
                        onStartOverlayService = { interval, clickEnabled, compareEnabled, alertType ->
                            startOverlayService(interval, clickEnabled, compareEnabled, alertType)
                        }
                    )
                }
            }
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, overlayRequestCode)
        }
    }

    private fun requestAccessibilityPermission() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun requestScreenCapturePermission() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), projectionRequestCode)
    }

    private fun startOverlayService(
        intervalMs: Long,
        clickEnabled: Boolean,
        compareEnabled: Boolean,
        alertType: String
    ) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "다른 앱 위에 그리기 권한을 허용해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (clickEnabled && !isAccessibilityServiceEnabled(this, MacroAccessibilityService::class.java)) {
            Toast.makeText(this, "자동 클릭 기능을 사용하려면 접근성 서비스를 켜주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (MacroOverlayService.mediaProjectionResultIntent == null) {
            Toast.makeText(this, "화면 캡처 연동(Media Projection) 권한을 먼저 허용해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        // Apply settings to service companion
        MacroOverlayService.clickIntervalMs = intervalMs
        MacroOverlayService.isClickEnabled = clickEnabled
        MacroOverlayService.isImageCompareEnabled = compareEnabled
        MacroOverlayService.alertType = alertType

        val intent = Intent(this, MacroOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "화면 상단에 오버레이 컨트롤러가 실행되었습니다.", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == projectionRequestCode && resultCode == Activity.RESULT_OK && data != null) {
            // Save projection intent
            MacroOverlayService.mediaProjectionResultIntent = data
            Toast.makeText(this, "화면 캡처 연동 권한 승인 완료!", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroConfigScreen(
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestCapture: () -> Unit,
    onStartOverlayService: (Long, Boolean, Boolean, String) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // State Variables
    var intervalInput by remember { mutableStateOf("1000") }
    var clickEnabled by remember { mutableStateOf(true) }
    var compareEnabled by remember { mutableStateOf(false) }
    var alertType by remember { mutableStateOf("BOTH") } // NONE, VIBRATE, SOUND, BOTH

    // Permission States updating on lifecycle focus
    var hasOverlayPermission by remember { mutableStateOf(false) }
    var hasAccessibilityPermission by remember { mutableStateOf(false) }
    var hasCapturePermission by remember { mutableStateOf(false) }

    // Custom helper to refresh permissions check
    fun checkAllPermissions() {
        hasOverlayPermission = Settings.canDrawOverlays(context)
        hasAccessibilityPermission = isAccessibilityServiceEnabled(context, MacroAccessibilityService::class.java)
        hasCapturePermission = MacroOverlayService.mediaProjectionResultIntent != null
    }

    LaunchedEffect(Unit) {
        checkAllPermissions()
    }

    // A simple hack to trigger check when window resumes focus
    // Under ordinary conditions, we can query on click or timer. Let's run it.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "UniDroid Macro Manager",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Android 16 Universal Automation",
            color = Color(0xFFA0A5C0),
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(24.dp))

        // Card 1: Configuration Fields
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1F28)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "1. 매크로 동작 설정 (Configuration)",
                    color = Color(0xFF7F39FB),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Click Interval Input
                OutlinedTextField(
                    value = intervalInput,
                    onValueChange = { intervalInput = it.filter { char -> char.isDigit() } },
                    label = { Text("매크로 실행 간격 (밀리초 / ms)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = Color(0xFF7F39FB),
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor = Color(0xFF7F39FB)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(onClick = { intervalInput = "200" }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2D3B))) {
                        Text("0.2초", color = Color.White)
                    }
                    Button(onClick = { intervalInput = "1000" }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2D3B))) {
                        Text("1초", color = Color.White)
                    }
                    Button(onClick = { intervalInput = "5000" }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2D3B))) {
                        Text("5초", color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = Color(0xFF2E303F))
                Spacer(modifier = Modifier.height(16.dp))

                // Toggle: Enable Clicker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("주기적 자동 클릭 활성화", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("설정한 시간마다 터치 이벤트를 발생시킵니다.", color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(
                        checked = clickEnabled,
                        onCheckedChange = { clickEnabled = it }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Toggle: Enable Compare Screen
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("화면 이미지 상태 비교 감시", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("지정한 이미지 영역 감시 및 경보 기능", color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(
                        checked = compareEnabled,
                        onCheckedChange = { compareEnabled = it }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Card 2: Alarm Sound Selection
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1F28)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "2. 이미지가 없을 때 알람 방식 선택",
                    color = Color(0xFF7F39FB),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                val options = listOf(
                    "BOTH" to "진동 + 경보음 알림",
                    "VIBRATE" to "진동만 알림",
                    "SOUND" to "경보음만 알림",
                    "NONE" to "알림 없음"
                )

                options.forEach { (type, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = alertType == type,
                            onClick = { alertType = type }
                        )
                        Text(text = label, color = Color.White, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Card 3: Permissions Status Check
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1F28)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "3. 앱 권한 및 접근성 활성화",
                    color = Color(0xFF7F39FB),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Permission Item 1: Overlay
                PermissionRow(
                    title = "다른 앱 위에 그리기 권한",
                    status = if (hasOverlayPermission) "허용됨" else "미허용",
                    statusColor = if (hasOverlayPermission) Color.Green else Color.Red,
                    onGrantClick = {
                        onRequestOverlay()
                        // Run permission check after click
                        checkAllPermissions()
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Permission Item 2: Media Projection
                PermissionRow(
                    title = "화면 캡처 연동 권한",
                    status = if (hasCapturePermission) "승인됨" else "미승인",
                    statusColor = if (hasCapturePermission) Color.Green else Color.Red,
                    onGrantClick = {
                        onRequestCapture()
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Permission Item 3: Accessibility
                PermissionRow(
                    title = "매크로 접근성 서비스 등록",
                    status = if (hasAccessibilityPermission) "활성화됨" else "비활성화",
                    statusColor = if (hasAccessibilityPermission) Color.Green else Color.Red,
                    onGrantClick = {
                        onRequestAccessibility()
                    }
                )

                // Restricted Settings Alert for Android 13+
                if (!hasAccessibilityPermission) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .background(Color(0xFF2C2D3B), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFFEA80FC), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "💡 안드로이드 13~16 접근성 우회 안내",
                            color = Color(0xFFEA80FC),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "앱을 직접 설치(Sideload)한 경우 안드로이드 보안 정책상 접근성 서비스 켜기 메뉴가 잠겨있을 수 있습니다.\n\n" +
                                    "해결법: 휴대폰 [설정] -> [애플리케이션] -> [UniDroid Macro] 앱 검색 -> 우측 상단 [점 3개] 아이콘 클릭 -> [제한된 설정 허용] 터치 후, 다시 위 승인 버튼을 눌러 활성화해 주세요.",
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Action: Start Macro Service Overlay Button
        Button(
            onClick = {
                checkAllPermissions()
                val interval = intervalInput.toLongOrNull() ?: 1000L
                onStartOverlayService(interval, clickEnabled, compareEnabled, alertType)
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F39FB)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("오버레이 제어기 시작 (Start Controller)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                checkAllPermissions()
                Toast.makeText(context, "권한 상태가 새로고침되었습니다.", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2D3B)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("권한 확인 새로고침", color = Color.White)
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
fun PermissionRow(
    title: String,
    status: String,
    statusColor: Color,
    onGrantClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF252631), RoundedCornerShape(10.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(text = status, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onGrantClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F39FB)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.height(36.dp)
        ) {
            Text("허용/승인", color = Color.White, fontSize = 12.sp)
        }
    }
}

// Global utility helper to check active accessibility service ComponentName registry
fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService>): Boolean {
    val expectedComponentName = ComponentName(context, service)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledService = ComponentName.unflattenFromString(componentNameString)
        if (enabledService != null && enabledService == expectedComponentName) {
            return true
        }
    }
    return false
}
