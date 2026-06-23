// Based on MLCChat from MLC-LLM (https://github.com/mlc-ai/mlc-llm), Apache 2.0 License
// Extended by Akshit Harsola
package ai.mlc.mobileai

import android.Manifest
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import ai.mlc.mobileai.ui.theme.MobileAITheme
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    var hasImage = false
    lateinit var chatState: AppViewModel.ChatState
    private lateinit var appViewModelRef: AppViewModel

    private var inferenceService: ForegroundInferenceService? = null
    private var clipboardMonitor: ClipboardMonitor? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as ForegroundInferenceService.LocalBinder).getService()
            inferenceService = service
            service.chatState = chatState
            service.appViewModel = appViewModelRef
            appViewModelRef.inferenceMutex = service.inferenceMutex
            clipboardMonitor = ClipboardMonitor(this@MainActivity, service)
            clipboardMonitor?.start()
            val prefs = getSharedPreferences("mobileai", MODE_PRIVATE)
            val serviceEnabled = prefs.getBoolean("service_enabled", true)
            if (serviceEnabled) {
                if (service.apiServer == null) {
                    service.startApiServer(prefs.getInt("api_port", 8080))
                }
                val token = prefs.getString("bot_token", "") ?: ""
                if (token.isNotBlank() && service.telegramPoller == null) {
                    service.startTelegramPoller(token)
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            inferenceService = null
        }
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            chatState.messages.add(MessageData(role = MessageRole.User, text = "", id = UUID.randomUUID(), imageUri = it))
        }
    }

    private var cameraImageUri: Uri? = null
    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraImageUri != null) {
            chatState.messages.add(MessageData(role = MessageRole.User, text = "", id = UUID.randomUUID(), imageUri = cameraImageUri))
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    @ExperimentalMaterial3Api
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appViewModel = androidx.lifecycle.ViewModelProvider(this)[AppViewModel::class.java]
        appViewModelRef = appViewModel
        chatState = appViewModel.chatState
        requestNeededPermissions()
        // Re-enable service auto-start for next session (cleared on deliberate shutdown)
        getSharedPreferences("mobileai", MODE_PRIVATE)
            .edit().putBoolean("service_enabled", true).apply()
        startAndBindService()
        setContent {
            MobileAITheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NavView(this, appViewModel)
                }
            }
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, ForegroundInferenceService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        clipboardMonitor?.stop()
        unbindService(serviceConnection)
        super.onDestroy()
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.CAMERA)
        if (perms.isNotEmpty()) requestPermissionLauncher.launch(perms.toTypedArray())
    }

    fun pickImageFromGallery() { pickImageLauncher.launch("image/*") }

    fun takePhoto() {
        val contentValues = ContentValues().apply {
            val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${fmt.format(Date())}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
        }
        cameraImageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        takePictureLauncher.launch(cameraImageUri)
    }

    fun getInferenceService(): ForegroundInferenceService? = inferenceService

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "unknown"
            val candidates = mutableListOf<Pair<String, String>>()  // (interfaceName, ip)
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress || addr.hostAddress?.contains(':') == true) continue
                    val ip = addr.hostAddress ?: continue
                    candidates.add(iface.name to ip)
                }
            }
            // Prefer the Wi-Fi interface (wlan0) over mobile data, tethering, or VPN interfaces
            candidates.firstOrNull { it.first == "wlan0" }?.let { return it.second }
            candidates.firstOrNull()?.let { return it.second }
        } catch (_: Exception) {}
        return "unknown"
    }

    fun shutdownAndFinish() {
        inferenceService?.shutdown()
        finish()
    }
}
