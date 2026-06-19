package com.sonicmusic.app.core.battery

import com.sonicmusic.app.BuildConfig

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes device battery state via broadcast receiver and exposes:
 * - [isLowBattery]: true when battery < 15% AND not charging
 * - [batteryLevel]: current battery percentage (0â€“100)
 *
 * When [isLowBattery] is true, consumers should reduce non-critical network
 * activity (e.g. queue pre-fetch, background home feed refresh).
 *
 * Registration/unregistration is handled internally. The receiver is registered
 * once at Hilt singleton construction time and lives for the app's process lifetime.
 */
@Singleton
class BatteryStateMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BatteryStateMonitor"
        private const val LOW_BATTERY_THRESHOLD = 15
    }

    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isLowBattery = MutableStateFlow(false)
    val isLowBattery: StateFlow<Boolean> = _isLowBattery.asStateFlow()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

            if (level < 0 || scale <= 0) return

            val percent = (level * 100 / scale)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL

            _batteryLevel.value = percent
            _isLowBattery.value = percent < LOW_BATTERY_THRESHOLD && !isCharging

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Battery: $percent% | charging=$isCharging | lowBattery=${_isLowBattery.value}")
            }
        }
    }

    init {
        // ACTION_BATTERY_CHANGED is a sticky broadcast â€” registering receives the current state immediately.
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val initialIntent = context.registerReceiver(batteryReceiver, filter)

        // Apply initial battery state from the sticky intent
        initialIntent?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level >= 0 && scale > 0) {
                val percent = (level * 100 / scale)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                 status == BatteryManager.BATTERY_STATUS_FULL
                _batteryLevel.value = percent
                _isLowBattery.value = percent < LOW_BATTERY_THRESHOLD && !isCharging
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Initial battery state: $percent% | charging=$isCharging")
                }
            }
        }
    }
}
