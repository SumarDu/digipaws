package nethical.digipaws.tiles

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import nethical.digipaws.R
import nethical.digipaws.ui.activity.PhoneLockQuickActionsActivity

class PhoneLockQuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = getString(R.string.phone_lock)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                subtitle = getString(R.string.quick_tile_subtitle)
            }
            icon = Icon.createWithResource(this@PhoneLockQuickTileService, R.mipmap.ic_launcher)
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // Set a short bypass to survive the immediate USER_PRESENT re-lock
        try {
            val sp = getSharedPreferences("phone_lock", MODE_PRIVATE)
            val until = System.currentTimeMillis() + 20_000L // 20 seconds
            sp.edit().putLong("temp_bypass_until", until).apply()
        } catch (_: Exception) { }
        // Open quick actions activity
        val i = Intent(this, PhoneLockQuickActionsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34+
            val pi = PendingIntent.getActivity(
                this,
                0,
                i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            startActivityAndCollapse(i)
        }
    }
}
