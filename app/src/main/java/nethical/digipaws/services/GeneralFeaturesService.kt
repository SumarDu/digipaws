package nethical.digipaws.services

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import nethical.digipaws.Constants
import nethical.digipaws.utils.GrayscaleControl
import java.util.Locale

class GeneralFeaturesService : BaseBlockingService() {

    companion object {
        const val INTENT_ACTION_REFRESH_ANTI_UNINSTALL = "nethical.digipaws.refresh.anti_uninstall"
        const val INTENT_ACTION_REFRESH_GRAYSCALE = "nethical.digipaws.refresh.grayscale"
        const val INTENT_ACTION_REFRESH_PHONE_LOCK = "nethical.digipaws.refresh.phone_lock"
    }

    private fun shouldBlockAppsScreen(root: AccessibilityNodeInfo?): Boolean {
        if (!blockAppsSettings || root == null) return false
        // 1) Strong signal: Settings activities for Apps
        val cls = lastClassName ?: ""
        if (cls.contains("ManageApplications", ignoreCase = true)) return true
        if (cls.contains("AppInfo", ignoreCase = true)) return true
        if (cls.contains("Applications", ignoreCase = true)) return true

        // 2) Heuristic: inside a sub-screen (has back arrow) and title/contents indicate Apps section
        if (hasNavigateUp(root) && findAnyText(root, arrayOf(
                "app info", "all apps", "see all apps", "installed apps", "manage apps"
            ))
        ) {
            return true
        }
        return false
    }

    private fun shouldBlockLanguageScreen(root: AccessibilityNodeInfo?): Boolean {
        if (!blockLanguageSettings || root == null) return false
        val cls = lastClassName ?: ""
        // Some common Settings class names for language/input
        if (cls.contains("Language", ignoreCase = true)) return true
        if (cls.contains("Locale", ignoreCase = true)) return true
        if (cls.contains("InputMethod", ignoreCase = true)) return true
        if (cls.contains("TextToSpeech", ignoreCase = true)) return true

        // Heuristic: inside a sub-screen with back arrow and strong language-related keywords
        if (hasNavigateUp(root) && findAnyText(root, arrayOf(
                "language", "languages", "language & input", "system languages",
                "add a language", "preferred language", "region", "locale"
            ))
        ) {
            return true
        }
        return false
    }

    private fun hasNavigateUp(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val desc = (node.contentDescription ?: "").toString().lowercase(Locale.getDefault())
        val cls = node.className?.toString() ?: ""
        if (desc.contains("navigate up") || desc.contains("back")) return true
        // Check children
        for (i in 0 until node.childCount) {
            if (hasNavigateUp(node.getChild(i))) return true
        }
        return false
    }

    private fun findAnyText(node: AccessibilityNodeInfo?, keywords: Array<String>): Boolean {
        if (node == null) return false
        val text = (node.text ?: "").toString().lowercase(Locale.getDefault())
        val desc = (node.contentDescription ?: "").toString().lowercase(Locale.getDefault())
        for (kw in keywords) {
            val k = kw.lowercase(Locale.getDefault())
            if (text.contains(k) || desc.contains(k)) return true
        }
        for (i in 0 until node.childCount) {
            if (findAnyText(node.getChild(i), keywords)) return true
        }
        return false
    }

    private var lastPackageName: String? = null // Store the last active app's package name
    private var lastClassName: String? = null   // Store the last window class name

    private var selectedGrayScaleApps: HashSet<String> = hashSetOf()
    private var grayScaleMode = Constants.GRAYSCALE_MODE_ONLY_SELECTED

    private var isAntiUninstallOn = true
    private var blockAppsSettings = false
    private var blockDnsSettings = false
    private var blockLanguageSettings = false
    private val grayscaleControl = GrayscaleControl()

    // Phone Lock (multi-schedule)
    private var phoneLockEnabled = false
    private var schedulesJson: org.json.JSONArray = org.json.JSONArray()
    private var activeSessions: org.json.JSONObject = org.json.JSONObject()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        super.onAccessibilityEvent(event)

        // If in a locked interval, try to lock immediately when windows change (e.g., after unlock)
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            maybeLockIfScheduled()
        }

        if (isAntiUninstallOn && event?.packageName == "com.android.settings") {
            // Only block when actually inside specific sub-screens, not on Settings home
            if (shouldBlockAppsScreen(rootInActiveWindow)) { pressHome(); return }
            if (shouldBlockLanguageScreen(rootInActiveWindow)) { pressHome(); return }
            // Handle other blocks (DNS) and uninstall protection
            traverseSettingsForBlocks(rootInActiveWindow)
        }

        try {
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val currentPackageName = event.packageName?.toString()
                // Check if the app has changed
                if (currentPackageName != null && currentPackageName != lastPackageName) {
                    lastPackageName = currentPackageName // Update the last package name
                    lastClassName = event.className?.toString()

                    when (grayScaleMode) {
                        Constants.GRAYSCALE_MODE_ONLY_SELECTED -> {
                            if (selectedGrayScaleApps.contains(event.packageName)) {
                                grayscaleControl.enableGrayscale()
                            } else {
                                grayscaleControl.disableGrayscale()
                            }
                        }

                        Constants.GRAYSCALE_MODE_ALL_EXCEPT_SELECTED -> {
                            if (selectedGrayScaleApps.contains(event.packageName)) {
                                grayscaleControl.disableGrayscale()
                            } else {
                                grayscaleControl.enableGrayscale()

                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()

        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_ANTI_UNINSTALL)
            addAction(INTENT_ACTION_REFRESH_GRAYSCALE)
            addAction(INTENT_ACTION_REFRESH_PHONE_LOCK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(refreshReceiver, filter)
        }
        // Listen to unlock/screen on to re-lock if necessary
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, screenFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenReceiver, screenFilter)
        }
        setupAntiUninstall()
        setupGrayscale()
        setupPhoneLock()
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

            if (intent != null) {
                when (intent.action) {
                    INTENT_ACTION_REFRESH_ANTI_UNINSTALL -> setupAntiUninstall()
                    INTENT_ACTION_REFRESH_GRAYSCALE -> setupGrayscale()
                    INTENT_ACTION_REFRESH_PHONE_LOCK -> setupPhoneLock()
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            maybeLockIfScheduled()
        }
    }

    fun setupAntiUninstall() {
        val info = getSharedPreferences("anti_uninstall", Context.MODE_PRIVATE)
        isAntiUninstallOn = info.getBoolean("is_anti_uninstall_on", false)
        blockAppsSettings = info.getBoolean("block_apps_settings", false)
        blockDnsSettings = info.getBoolean("block_dns_settings", false)
        blockLanguageSettings = info.getBoolean("block_language_settings", false)

    }

    fun setupGrayscale() {
        selectedGrayScaleApps = savedPreferencesLoader.loadGrayScaleApps().toHashSet()
        val sp = getSharedPreferences("grayscale", MODE_PRIVATE)
        grayScaleMode = sp.getInt("mode", Constants.GRAYSCALE_MODE_ONLY_SELECTED)
    }

    fun setupPhoneLock() {
        val sp = getSharedPreferences("phone_lock", MODE_PRIVATE)
        phoneLockEnabled = sp.getBoolean("enabled", false)
        val arrStr = sp.getString("schedules", "[]")
        schedulesJson = try { org.json.JSONArray(arrStr) } catch (_: Exception) { org.json.JSONArray() }
        val actStr = sp.getString("active_sessions", "{}")
        activeSessions = try { org.json.JSONObject(actStr) } catch (_: Exception) { org.json.JSONObject() }
    }

    private fun maybeLockIfScheduled() {
        if (!phoneLockEnabled) return
        if (anyScheduleActiveNow()) {
            // Try Accessibility global lock (API 28+)
            val ok = performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            if (!ok) {
                // Fallback: DevicePolicyManager lock (requires active admin)
                try {
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    dpm.lockNow()
                } catch (_: Exception) { }
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun anyScheduleActiveNow(): Boolean {
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val dowBit = dayOfWeekBit(cal)
        val sp = getSharedPreferences("phone_lock", MODE_PRIVATE)
        val skipToday = try { org.json.JSONObject(sp.getString("skip_today", "{}")) } catch (_: Exception) { org.json.JSONObject() }
        val forceToday = try { org.json.JSONObject(sp.getString("force_today", "{}")) } catch (_: Exception) { org.json.JSONObject() }
        val today = todayKey()

        // Check duration sessions first (manual Option C)
        try {
            val keys = activeSessions.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val endAt = activeSessions.optLong(id, 0L)
                if (endAt > System.currentTimeMillis()) {
                    // Validate schedule exists and day mask matches (if defined)
                    val sch = findScheduleById(id)
                    if (sch != null && sch.optBoolean("enabled", true)) {
                        val daysMask = sch.optInt("daysMask", 0)
                        if (daysMask == 0 || ((daysMask shr dowBit) and 1) == 1) {
                            return true
                        }
                    } else if (sch == null) {
                        // If schedule missing, still respect active session to be safe
                        return true
                    }
                }
            }
        } catch (_: Exception) { }

        // Forced today overrides (any matching id forces lock today regardless of time)
        try {
            val it = forceToday.keys()
            while (it.hasNext()) {
                val id = it.next()
                if (today == forceToday.optString(id, "")) {
                    // also ensure schedule still exists and is enabled
                    val sch = findScheduleById(id)
                    if (sch == null || sch.optBoolean("enabled", true)) return true
                }
            }
        } catch (_: Exception) { }

        // Check interval schedules
        for (i in 0 until schedulesJson.length()) {
            val obj = schedulesJson.optJSONObject(i) ?: continue
            if (!obj.optBoolean("enabled", true)) continue
            if (obj.optString("mode") != "interval") continue
            // Skip if user requested skip for today
            val sid = obj.optString("id")
            if (today == skipToday.optString(sid, "")) continue
            val daysMask = obj.optInt("daysMask", 0)
            if (daysMask != 0 && ((daysMask shr dowBit) and 1) == 0) continue
            val start = obj.optInt("startMin", 0)
            val end = obj.optInt("endMin", 0)
            if (isTimeInRange(nowMin, start, end)) return true
        }

        // Cleanup expired duration sessions
        pruneExpiredSessions()
        return false
    }

    private fun pruneExpiredSessions() {
        try {
            val itr = activeSessions.keys()
            val toRemove = mutableListOf<String>()
            while (itr.hasNext()) {
                val id = itr.next()
                val endAt = activeSessions.optLong(id, 0L)
                if (endAt <= System.currentTimeMillis()) toRemove.add(id)
            }
            if (toRemove.isNotEmpty()) {
                toRemove.forEach { activeSessions.remove(it) }
                getSharedPreferences("phone_lock", MODE_PRIVATE)
                    .edit().putString("active_sessions", activeSessions.toString()).apply()
            }
        } catch (_: Exception) { }
    }

    private fun findScheduleById(id: String): org.json.JSONObject? {
        for (i in 0 until schedulesJson.length()) {
            val obj = schedulesJson.optJSONObject(i) ?: continue
            if (obj.optString("id") == id) return obj
        }
        return null
    }

    private fun isTimeInRange(now: Int, start: Int, end: Int): Boolean {
        return if (start <= end) {
            now in start..end
        } else {
            // Overnight window, e.g., 22:00-06:00
            now >= start || now <= end
        }
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

    private fun todayKey(): String {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        return fmt.format(java.util.Date())
    }

    private fun traverseSettingsForBlocks(node: AccessibilityNodeInfo?) {
        if (node == null) return

        // Check text and contentDescription of TextView, Button, etc.
        val cls = node.className?.toString() ?: ""
        if (cls.isNotEmpty()) {
            val text = (node.text ?: "").toString().lowercase(Locale.getDefault())
            val desc = (node.contentDescription ?: "").toString().lowercase(Locale.getDefault())

            // Always block if user navigates to a screen with our app name to prevent uninstall
            if (text.contains("digipaws") || desc.contains("digipaws")) {
                pressHome()
                return
            }

            // Do not block generic "Apps" mentions here; handled by shouldBlockAppsScreen()
            if (blockDnsSettings) {
                if (matchesAny(text, desc, arrayOf(
                        "private dns", "dns", "dns over tls"
                    ))
                ) {
                    pressHome(); return
                }
            }
            // Do not block generic Language/Region mentions here; handled by shouldBlockLanguageScreen()
        }

        for (i in 0 until node.childCount) {
            traverseSettingsForBlocks(node.getChild(i))
        }
    }

    private fun matchesAny(text: String, desc: String, keywords: Array<String>): Boolean {
        for (kw in keywords) {
            if (text.contains(kw) || desc.contains(kw)) return true
        }
        return false
    }
}