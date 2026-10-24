package com.openjarvis.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.openjarvis.R
import com.openjarvis.agent.AgentCore
import com.openjarvis.agent.AgentState
import com.openjarvis.bridge.SocketServer
import com.openjarvis.graphify.GraphifyRepository
import com.openjarvis.graphify.nodes.TaskNode
import com.openjarvis.ui.overlay.FloatingOverlayWidget
import com.openjarvis.voice.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlayService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    private val myViewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    
    override val viewModelStore: ViewModelStore get() = myViewModelStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    
    private lateinit var agentCore: AgentCore
    private lateinit var voiceManager: VoiceManager
    private lateinit var graphifyRepo: GraphifyRepository
    private lateinit var socketServer: SocketServer
    private var stateCollectJob: Job? = null
    private var recentTasks = emptyList<TaskNode>()
    
    private var isInitialized = false

    override fun onCreate() {
        savedStateRegistryController.performRestore(null)
        super.onCreate()
        
        agentCore = AgentCore(this)
        voiceManager = VoiceManager(this)
        graphifyRepo = GraphifyRepository(this)
        socketServer = SocketServer(this, agentCore, graphifyRepo)
        
        startForeground(NOTIFICATION_ID, createNotification())
        
        CoroutineScope(Dispatchers.Main).launch {
            delay(0)
            initializeServices()
        }
        
        socketServer.start()
        
        // Load recent tasks
        CoroutineScope(Dispatchers.IO).launch {
            recentTasks = graphifyRepo.getRecentTasks(10)
        }
        
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showOverlay()
    }
    
    private fun showOverlay() {
        try {
            if (!android.provider.Settings.canDrawOverlays(this)) return
            
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 200
            }

            val view = ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(this@OverlayService)
                setViewTreeViewModelStoreOwner(this@OverlayService)
                setViewTreeSavedStateRegistryOwner(this@OverlayService)
                setContent {
                    com.openjarvis.ui.theme.OpenJarvisTheme {
                        val agentState by agentCore.state.collectAsState()
                        FloatingOverlayWidget(
                            agentState = agentState,
                            voiceManager = voiceManager,
                            recentTasks = recentTasks,
                            onCommand = { command -> executeCommand(command) },
                            onCollapse = { /* collapse */ }
                        )
                    }
                }
            }
            overlayView = view
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private suspend fun initializeServices() {
        if (isInitialized) return
        isInitialized = true
        
        // Heavy initializations deferred here
        withContext(Dispatchers.IO) {
            try {
                graphifyRepo.getRecentTasks(10)
            } catch (e: Exception) {
                // Graphify initialization
            }
        }
        
        try {
            voiceManager.initialize()
        } catch (e: Exception) {
            // Voice initialization
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        overlayView = null
        myViewModelStore.clear()
        super.onDestroy()
        stateCollectJob?.cancel()
        socketServer.stop()
        voiceManager.release()
    }

    private fun createNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    fun getAgentCore() = agentCore
    fun getVoiceManager() = voiceManager
    fun getRecentTasks() = recentTasks

    fun executeCommand(command: String) {
        agentCore.executeTask(command)
        
        // Refresh tasks after execution
        CoroutineScope(Dispatchers.IO).launch {
            delay(1000)
            recentTasks = graphifyRepo.getRecentTasks(10)
        }
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_overlay_channel"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            context.startForegroundService(intent)
        }
    }
}