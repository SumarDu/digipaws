package nethical.digipaws.ui.activity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import nethical.digipaws.R
import nethical.digipaws.services.GeneralFeaturesService
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ApiDeepLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent?.data)
        finish()
    }

    private fun todayKey(): String {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        return fmt.format(Date())
    }

    private fun handleDeepLink(uri: Uri?) {
        if (uri == null) { finish(); return }
        if (uri.host != "api") { finish(); return }
        val path = uri.path ?: ""
        if (!path.equals("/phone_lock", ignoreCase = true)) { finish(); return }
        val action = uri.getQueryParameter("action")?.lowercase(Locale.getDefault())
        val id = uri.getQueryParameter("id")
        if (action.isNullOrEmpty() || id.isNullOrEmpty()) { finish(); return }

        val sp = getSharedPreferences("phone_lock", MODE_PRIVATE)
        // Validate schedule exists
        val arr = try { JSONArray(sp.getString("schedules", "[]")) } catch (_: Exception) { JSONArray() }
        var exists = false
        var target: JSONObject? = null
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (it.optString("id") == id) { exists = true; target = it; break }
        }
        if (!exists) { finish(); return }

        val key = todayKey()
        when (action) {
            "skip" -> {
                val obj = try { JSONObject(sp.getString("skip_today", "{}")) } catch (_: Exception) { JSONObject() }
                obj.put(id, key)
                sp.edit().putString("skip_today", obj.toString()).apply()
                sendBroadcast(Intent(GeneralFeaturesService.INTENT_ACTION_REFRESH_PHONE_LOCK))
                Toast.makeText(this, R.string.api_success_skipped_today, Toast.LENGTH_SHORT).show()
            }
            "induce" -> {
                val sch = target
                if (sch != null && sch.optString("mode") == "duration") {
                    // Start a manual duration session now
                    val durationMin = sch.optInt("durationMin", 15)
                    val endAt = System.currentTimeMillis() + durationMin * 60_000L
                    val activeStr = sp.getString("active_sessions", "{}")
                    val active = try { JSONObject(activeStr) } catch (_: Exception) { JSONObject() }
                    active.put(id, endAt)
                    sp.edit().putString("active_sessions", active.toString()).apply()
                } else {
                    // Fallback: mark as forced for today (for interval mode)
                    val obj = try { JSONObject(sp.getString("force_today", "{}")) } catch (_: Exception) { JSONObject() }
                    obj.put(id, key)
                    sp.edit().putString("force_today", obj.toString()).apply()
                }
                sendBroadcast(Intent(GeneralFeaturesService.INTENT_ACTION_REFRESH_PHONE_LOCK))
                Toast.makeText(this, R.string.api_success_induced_today, Toast.LENGTH_SHORT).show()
            }
            else -> { }
        }
    }
}
