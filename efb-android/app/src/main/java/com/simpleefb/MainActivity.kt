package com.simpleefb

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.simpleefb.store.PositionSourceType
import com.simpleefb.ui.EfbRoot
import com.simpleefb.ui.EfbTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val graph get() = (application as EfbApp).graph

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { graph.position.restart() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null &&
            graph.settings.positionSourceType() == PositionSourceType.INTERNAL &&
            !graph.position.hasLocationPermission()
        ) {
            locationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.settings.keepScreenOn.flow.collect { on ->
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        setContent {
            EfbTheme {
                EfbRoot(graph)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as EfbApp).configureMapCache()
        graph.position.start()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) graph.position.stop()
    }
}
