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
                // Per-schedule emergency unlock until end of day.
                val activeId = getActiveScheduleIdNow()
                if (activeId == null) {
                    Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show()
                } else {
                    // Mark skip_today for this schedule id and clear any activeSessions entry
                    val sp = getSharedPreferences("phone_lock", Context.MODE_PRIVATE)
                    val today = todayKey()
                    val skipTodayStr = sp.getString("skip_today", "{}")
                    val skipToday = try { org.json.JSONObject(skipTodayStr) } catch (_: Exception) { org.json.JSONObject() }
                    skipToday.put(activeId, today)
                    // Also clear any global temp bypass to avoid suppressing other schedules
                    sp.edit()
                        .putString("skip_today", skipToday.toString())
                        .putLong("temp_bypass_until", 0L)
                        .apply()

                    // Clear duration session if present
                    try {
                        val actStr = sp.getString("active_sessions", "{}")
                        val act = try { org.json.JSONObject(actStr) } catch (_: Exception) { org.json.JSONObject() }
                        if (act.has(activeId)) {
                            act.put(activeId, 0L)
                            sp.edit().putString("active_sessions", act.toString()).apply()
                        }
                    } catch (_: Exception) { }

                    // Ask service to re-evaluate immediately
                    sendBroadcast(Intent(GeneralFeaturesService.INTENT_ACTION_REFRESH_PHONE_LOCK))

                    Toast.makeText(this, R.string.unlocked_temporarily, Toast.LENGTH_SHORT).show()
                    actionPerformed = true
                    finish()
                }
            } else {
                Toast.makeText(this, R.string.incorrect_password_please_try_again, Toast.LENGTH_SHORT).show()
            }
        }

        btnUltra.setOnClickListener {
            val sp = getSharedPreferences("phone_lock", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val monthFmt = java.text.SimpleDateFormat("yyyyMM", java.util.Locale.US)
            val curMonth = monthFmt.format(java.util.Date(now))
            val storedMonth = sp.getString("ultra_month", "")
            var used = sp.getInt("ultra_used", 0)
            val quota = sp.getInt("ultra_quota_per_month", 1)
            if (storedMonth != curMonth) {
                used = 0
            }
            if (used >= quota) {
                Toast.makeText(this, R.string.ultra_unlock_quota_exhausted, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val activeId = getActiveScheduleIdNow()
            if (activeId == null) {
                Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val today = todayKey()
            val skipTodayStr = sp.getString("skip_today", "{}")
            val skipToday = try { org.json.JSONObject(skipTodayStr) } catch (_: Exception) { org.json.JSONObject() }
            skipToday.put(activeId, today)
            // Clear any global bypass and any active session for this id
            val actStr = sp.getString("active_sessions", "{}")
            val act = try { org.json.JSONObject(actStr) } catch (_: Exception) { org.json.JSONObject() }
            if (act.has(activeId)) {
                act.put(activeId, 0L)
            }
            sp.edit()
                .putString("skip_today", skipToday.toString())
                .putString("ultra_month", curMonth)
                .putInt("ultra_used", used + 1)
                .putString("active_sessions", act.toString())
                .putLong("temp_bypass_until", 0L)
                .apply()

            // Trigger immediate re-evaluation
            sendBroadcast(Intent(GeneralFeaturesService.INTENT_ACTION_REFRESH_PHONE_LOCK))

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

    private fun todayKey(): String {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        return fmt.format(java.util.Date())
    }

    private fun dayOfWeekBit(cal: java.util.Calendar): Int {
        return when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.SUNDAY -> 0
            java.util.Calendar.MONDAY -> 1
            java.util.Calendar.TUESDAY -> 2
            java.util.Calendar.WEDNESDAY -> 3
            java.util.Calendar.THURSDAY -> 4
            java.util.Calendar.FRIDAY -> 5
            java.util.Calendar.SATURDAY -> 6
            else -> 0
        }
    }

    private fun isTimeInRange(now: Int, start: Int, end: Int): Boolean {
        return if (start <= end) {
            now in start..end
        } else {
            now >= start || now <= end
        }
    }

    private fun getActiveScheduleIdNow(): String? {
        val sp = getSharedPreferences("phone_lock", Context.MODE_PRIVATE)
        val schedulesStr = sp.getString("schedules", "[]")
        val schedules = try { org.json.JSONArray(schedulesStr) } catch (_: Exception) { org.json.JSONArray() }
        val actStr = sp.getString("active_sessions", "{}")
        val activeSessions = try { org.json.JSONObject(actStr) } catch (_: Exception) { org.json.JSONObject() }
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val dowBit = dayOfWeekBit(cal)
        val today = todayKey()

        // 1) Active duration sessions first
        try {
            val keys = activeSessions.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val endAt = activeSessions.optLong(id, 0L)
                if (endAt > System.currentTimeMillis()) {
                    // Confirm schedule constraints if it exists
                    for (i in 0 until schedules.length()) {
                        val obj = schedules.optJSONObject(i) ?: continue
                        if (obj.optString("id") == id) {
                            if (!obj.optBoolean("enabled", true)) break
                            val daysMask = obj.optInt("daysMask", 0)
                            if (daysMask == 0 || ((daysMask shr dowBit) and 1) == 1) {
                                return id
                            }
                            break
                        }
                    }
                    // If schedule not found, still return id (be conservative)
                    return id
                }
            }
        } catch (_: Exception) { }

        // 2) Interval schedules currently active
        // Respect skip_today: if already skipped today, do not treat as active
        val skipTodayStr = sp.getString("skip_today", "{}")
        val skipToday = try { org.json.JSONObject(skipTodayStr) } catch (_: Exception) { org.json.JSONObject() }
        val forceTodayStr = sp.getString("force_today", "{}")
        val forceToday = try { org.json.JSONObject(forceTodayStr) } catch (_: Exception) { org.json.JSONObject() }
        for (i in 0 until schedules.length()) {
            val obj = schedules.optJSONObject(i) ?: continue
            if (!obj.optBoolean("enabled", true)) continue
            if (obj.optString("mode") != "interval") continue
            val id = obj.optString("id")
            if (today == skipToday.optString(id, "")) continue
            // If inducible, only treat as active if forced today
            if (obj.optBoolean("inducible", false)) {
                if (today != forceToday.optString(id, "")) continue
            }
            val daysMask = obj.optInt("daysMask", 0)
            if (daysMask != 0 && ((daysMask shr dowBit) and 1) == 0) continue
            val start = obj.optInt("startMin", 0)
            val end = obj.optInt("endMin", 0)
            if (isTimeInRange(nowMin, start, end)) return id
        }
        return null
    }
}
