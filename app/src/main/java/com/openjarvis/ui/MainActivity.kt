package com.openjarvis.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.openjarvis.accessibility.JarvisAccessibilityService
import com.openjarvis.agent.AgentCore
import com.openjarvis.agent.AgentState
import com.openjarvis.graphify.GraphifyRepository
import com.openjarvis.graphify.nodes.TaskNode
import com.openjarvis.ui.dashboard.DashboardScreen
import com.openjarvis.ui.settings.SettingsScreen
import com.openjarvis.ui.theme.OpenJarvisTheme

class MainActivity : ComponentActivity() {
    
    private var graphifyRepo: GraphifyRepository? = null
    private var agentCore: AgentCore? = null
    private val accessibilityEnabledState = mutableStateOf(false)
    private val overlayEnabledState = mutableStateOf(false)
    
    fun checkPermissions() {
        accessibilityEnabledState.value = isAccessibilityServiceEnabled()
        overlayEnabledState.value = try { Settings.canDrawOverlays(this) } catch (e: Exception) { false }
    }
    
    override fun onResume() {
        super.onResume()
        checkPermissions()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        try {
            graphifyRepo = GraphifyRepository(this)
            agentCore = AgentCore(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        checkPermissions()
        
        setContent {
            OpenJarvisTheme {
                val accessibilityEnabled by accessibilityEnabledState
                val overlayEnabled by overlayEnabledState
                
                var recentTasks by remember { mutableStateOf<List<TaskNode>>(emptyList()) }
                
                LaunchedEffect(Unit) {
                    try {
                        recentTasks = graphifyRepo?.getRecentTasks(10) ?: emptyList()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                val agentState by (agentCore?.state ?: kotlinx.coroutines.flow.MutableStateFlow(AgentState.Idle)).collectAsState()
                
                if (!accessibilityEnabled || !overlayEnabled) {
                    PermissionScreen(
                        accessibilityEnabled = accessibilityEnabled,
                        overlayEnabled = overlayEnabled,
                        onEnableAccessibility = { startAccessibilitySettings() },
                        onEnableOverlay = { startOverlaySettings() },
                        onRefresh = { checkPermissions() }
                    )
                } else {
                    graphifyRepo?.let { repo ->
                        DashboardScreen(
                            onStartOverlay = { startOverlayService() },
                            onOpenSettings = { openSettings() },
                            graphifyRepo = repo,
                            modifier = Modifier.fillMaxSize()
                        )
                    } ?: run {
                        PermissionScreen(
                            accessibilityEnabled = accessibilityEnabled,
                            overlayEnabled = overlayEnabled,
                            onEnableAccessibility = { startAccessibilitySettings() },
                            onEnableOverlay = { startOverlaySettings() },
                            onRefresh = { checkPermissions() }
                        )
                    }
                }
            }
        }
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        if (JarvisAccessibilityService.instance != null) return true
        
        try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val list = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            if (list != null) {
                for (service in list) {
                    if (service.resolveInfo?.serviceInfo?.packageName == packageName) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(packageName)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return false
    }
    
    private fun startAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    
    private fun startOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, 
            android.net.Uri.parse("package:$packageName")))
    }
    
    private fun startOverlayService() {
        try {
            OverlayService.start(this)
            Toast.makeText(this, "Jarvis is active", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error starting overlay: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }
}

@Composable
fun PermissionScreen(
    accessibilityEnabled: Boolean,
    overlayEnabled: Boolean,
    onEnableAccessibility: () -> Unit,
    onEnableOverlay: () -> Unit,
    onRefresh: () -> Unit
) {
    val primaryColor = com.openjarvis.ui.theme.VoidColor.Violet
    val onSurfaceColor = com.openjarvis.ui.theme.VoidColor.TextPrimary
    val onSurfaceVariantColor = com.openjarvis.ui.theme.VoidColor.TextSecondary
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Permissions Required",
            style = MaterialTheme.typography.headlineMedium,
            color = onSurfaceColor
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Open Jarvis needs accessibility and overlay permissions to control your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = onSurfaceVariantColor,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        if (!accessibilityEnabled) {
            Button(
                onClick = onEnableAccessibility,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enable Accessibility Service")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        if (!overlayEnabled) {
            Button(
                onClick = onEnableOverlay,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enable Overlay Permission")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        if (accessibilityEnabled && overlayEnabled) {
            Text(
                text = "✓ Permissions Granted",
                style = MaterialTheme.typography.titleMedium,
                color = com.openjarvis.ui.theme.VoidColor.Green
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continue to Dashboard")
            }
        } else {
            TextButton(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Refresh Status", color = primaryColor)
            }
        }
    }
}