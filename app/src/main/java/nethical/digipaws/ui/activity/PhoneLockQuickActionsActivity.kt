package nethical.digipaws.ui.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import nethical.digipaws.R
import nethical.digipaws.services.GeneralFeaturesService

class PhoneLockQuickActionsActivity : AppCompatActivity() {

    private var actionPerformed: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_lock_quick_actions)

        // Provide a very short bypass window so the UI isn't immediately re-locked
        setBypassMinutes(0) // initialize
        setBypassMillis(15_000L) // 15 seconds to interact

        // Resize dialog to reasonable width to ensure all buttons are visible
        try {
            val dm = resources.displayMetrics
            val desiredW = (dm.widthPixels * 0.92f).toInt()
            window?.setLayout(desiredW, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            window?.setGravity(android.view.Gravity.CENTER)
        } catch (_: Exception) { }

        val btnDialer: Button = findViewById(R.id.btnOpenDialer)
        val btnEmergency: Button = findViewById(R.id.btnEmergencyUnlock)
        val btnUltra: Button = findViewById(R.id.btnUltraUnlock)
        val etPassword: EditText = findViewById(R.id.etPassword)

        btnDialer.setOnClickListener {
            // Allow only a very short bypass to let Dialer launch
            // Further protection: while Dialer is foreground, service will not lock due to dialer exception.
            // As soon as user leaves Dialer -> lock resumes.
            setBypassMillis(3_000L)
            try {
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    data = Uri.parse("tel:")
                }
                startActivity(dialIntent)
                actionPerformed = true
            } catch (_: Exception) {
                Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show()
            }
            finish()
        }

        btnEmergency.setOnClickListener {
            val input = etPassword.text?.toString() ?: ""
            if (input.isBlank()) {
                Toast.makeText(this, R.string.password, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Prefer Phone Lock's own emergency password if set, otherwise fallback to anti_uninstall for compatibility
            val pl = getSharedPreferences("phone_lock", Context.MODE_PRIVATE)
            val plPwd = pl.getString("emergency_password", null)
            val au = getSharedPreferences("anti_uninstall", Context.MODE_PRIVATE)
            val auPwd = au.getString("password", null)
            val saved = plPwd ?: auPwd
            if (!saved.isNullOrEmpty() && saved == input) {
                // Bypass for the rest of the day
                val endOfDay = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 23)
                    set(java.util.Calendar.MINUTE, 59)
                    set(java.util.Calendar.SECOND, 59)
                    set(java.util.Calendar.MILLISECOND, 900)
                }.timeInMillis
                setBypassUntil(endOfDay)
                Toast.makeText(this, R.string.unlocked_temporarily, Toast.LENGTH_SHORT).show()
                actionPerformed = true
                finish()
            } else {
                Toast.makeText(this, R.string.incorrect_password_please_try_again, Toast.LENGTH_SHORT).show()
            }
        }

        btnUltra.setOnClickListener {
            val sp = getSharedPreferences("phone_lock", Context.MODE_PRIVATE)
            val last = sp.getLong("ultra_last_ts", 0L)
            if (isSameCalendarMonth(last, System.currentTimeMillis())) {
                Toast.makeText(this, R.string.ultra_unlock_not_available, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            // Allow 30 minutes bypass without password
            setBypassMinutes(30)
            sp.edit().putLong("ultra_last_ts", System.currentTimeMillis()).apply()
            Toast.makeText(this, R.string.unlocked_temporarily, Toast.LENGTH_SHORT).show()
            actionPerformed = true
            finish()
        }
    }

    private fun setBypassMinutes(minutes: Int) {
        val sp = getSharedPreferences("phone_lock", Context.MODE_PRIVATE)
        val until = System.currentTimeMillis() + minutes * 60_000L
        sp.edit().putLong("temp_bypass_until", until).apply()
    }

    private fun setBypassMillis(millis: Long) {
        val sp = getSharedPreferences("phone_lock", Context.MODE_PRIVATE)
        val until = System.currentTimeMillis() + millis
        sp.edit().putLong("temp_bypass_until", until).apply()
    }

    private fun setBypassUntil(timestamp: Long) {
        val sp = getSharedPreferences("phone_lock", Context.MODE_PRIVATE)
        sp.edit().putLong("temp_bypass_until", timestamp).apply()
    }

    private fun isSameCalendarMonth(a: Long, b: Long): Boolean {
        if (a <= 0L) return false
        val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
        val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
                ca.get(java.util.Calendar.MONTH) == cb.get(java.util.Calendar.MONTH)
    }

    override fun onDestroy() {
        super.onDestroy()
        // If user exited without a successful action, remove bypass and re-lock if required
        if (!actionPerformed) {
            clearBypass()
            // Ask service to re-evaluate immediately
            val i = Intent(GeneralFeaturesService.INTENT_ACTION_REFRESH_PHONE_LOCK)
            sendBroadcast(i)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // User left via Home/Recents — if no action taken, restore lock
        if (!actionPerformed) {
            clearBypass()
            sendBroadcast(Intent(GeneralFeaturesService.INTENT_ACTION_REFRESH_PHONE_LOCK))
        }
    }

    override fun onStop() {
        super.onStop()
        // As a safety net: if the activity is no longer visible and no action was taken, restore lock
        if (!actionPerformed) {
            clearBypass()
            sendBroadcast(Intent(GeneralFeaturesService.INTENT_ACTION_REFRESH_PHONE_LOCK))
        }
    }

    private fun clearBypass() {
        val sp = getSharedPreferences("phone_lock", Context.MODE_PRIVATE)
        sp.edit().putLong("temp_bypass_until", 0L).apply()
    }
}
