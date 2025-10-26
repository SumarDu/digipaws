package nethical.digipaws.ui.activity

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import nethical.digipaws.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PhoneLockScheduleEditorActivity : AppCompatActivity() {

    private lateinit var etName: EditText
    private lateinit var rgMode: RadioGroup
    private lateinit var rbDuration: RadioButton
    private lateinit var rbInterval: RadioButton
    private lateinit var durationContainer: LinearLayout
    private lateinit var intervalContainer: LinearLayout
    private lateinit var spDuration: Spinner
    private lateinit var btnStartTime: Button
    private lateinit var btnEndTime: Button
    private lateinit var cbInducible: CheckBox
    private lateinit var btnSave: Button

    private var startMin: Int = 0
    private var endMin: Int = 0
    private var editingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_lock_schedule_editor)

        etName = findViewById(R.id.etName)
        rgMode = findViewById(R.id.rgMode)
        rbDuration = findViewById(R.id.rbDuration)
        rbInterval = findViewById(R.id.rbInterval)
        durationContainer = findViewById(R.id.durationContainer)
        intervalContainer = findViewById(R.id.intervalContainer)
        spDuration = findViewById(R.id.spDuration)
        btnStartTime = findViewById(R.id.btnStartTime)
        btnEndTime = findViewById(R.id.btnEndTime)
        cbInducible = findViewById(R.id.cbInducible)
        btnSave = findViewById(R.id.btnSave)

        setupDurationSpinner()
        toggleModeContainers()
        rgMode.setOnCheckedChangeListener { _, _ -> toggleModeContainers() }

        btnStartTime.setOnClickListener { showTimePicker(true) }
        btnEndTime.setOnClickListener { showTimePicker(false) }

        btnSave.setOnClickListener { saveSchedule() }

        // If editing existing schedule
        editingId = intent.getStringExtra("schedule_id")
        if (!editingId.isNullOrEmpty()) {
            prefillFromExisting(editingId!!)
        }
    }

    private fun setupDurationSpinner() {
        // Fixed durations in minutes plus a "Custom" option signaled by -1
        val entries = listOf(5, 10, 15, 20, 30, 40, 60, 120)
        val labels = entries.map { "$it ${getString(R.string.minutes_suffix)}" } + getString(R.string.custom)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spDuration.adapter = adapter
    }

    private fun toggleModeContainers() {
        val isDuration = rbDuration.isChecked
        durationContainer.visibility = if (isDuration) LinearLayout.VISIBLE else LinearLayout.GONE
        intervalContainer.visibility = if (isDuration) LinearLayout.GONE else LinearLayout.VISIBLE
    }

    private fun showTimePicker(isStart: Boolean) {
        val hour = 0
        val minute = 0
        val dlg = TimePickerDialog(this, { _, h, m ->
            val total = h * 60 + m
            if (isStart) {
                startMin = total
                btnStartTime.text = String.format("%02d:%02d", h, m)
            } else {
                endMin = total
                btnEndTime.text = String.format("%02d:%02d", h, m)
            }
        }, hour, minute, true)
        dlg.show()
    }

    private fun daysMaskFromChecks(): Int {
        var mask = 0
        val ids = listOf(
            R.id.cbMon to 0,
            R.id.cbTue to 1,
            R.id.cbWed to 2,
            R.id.cbThu to 3,
            R.id.cbFri to 4,
            R.id.cbSat to 5,
            R.id.cbSun to 6,
        )
        ids.forEach { (id, bit) ->
            val cb = findViewById<CheckBox>(id)
            if (cb.isChecked) mask = mask or (1 shl bit)
        }
        return mask
    }

    private fun saveSchedule() {
        val name = etName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.please_type_a_title, Toast.LENGTH_SHORT).show()
            return
        }
        val mode = if (rbDuration.isChecked) "duration" else "interval"
        val daysMask = daysMaskFromChecks()

        val obj = JSONObject()
        obj.put("id", editingId ?: UUID.randomUUID().toString())
        obj.put("name", name)
        obj.put("mode", mode)
        obj.put("enabled", true)
        obj.put("daysMask", daysMask)
        if (mode == "duration") {
            val sel = spDuration.selectedItemPosition
            val durations = intArrayOf(5, 10, 15, 20, 30, 40, 60, 120)
            var minutes = if (sel in durations.indices) durations[sel] else 15
            // If Custom was selected (last index), prompt for minutes
            if (sel == durations.size) {
                // Simple prompt using NumberPicker dialog alternative
                minutes = 15 // default; could be extended with a custom dialog later
            }
            obj.put("durationMin", minutes)
        } else {
            obj.put("startMin", startMin)
            obj.put("endMin", endMin)
            obj.put("inducible", cbInducible.isChecked)
        }

        val prefs = getSharedPreferences("phone_lock", MODE_PRIVATE)
        val current = prefs.getString("schedules", "[]")
        val arr = try { JSONArray(current) } catch (_: Exception) { JSONArray() }
        var replaced = false
        if (!editingId.isNullOrEmpty()) {
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                if (it.optString("id") == editingId) { arr.put(i, obj); replaced = true; break }
            }
        }
        if (!replaced) arr.put(obj)
        prefs.edit().putString("schedules", arr.toString()).apply()

        // notify service
        sendBroadcast(Intent(nethical.digipaws.services.GeneralFeaturesService.INTENT_ACTION_REFRESH_PHONE_LOCK))
        Toast.makeText(this, R.string.save_schedule, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun prefillFromExisting(id: String) {
        val prefs = getSharedPreferences("phone_lock", MODE_PRIVATE)
        val current = prefs.getString("schedules", "[]")
        val arr = try { JSONArray(current) } catch (_: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (it.optString("id") == id) {
                etName.setText(it.optString("name"))
                val mode = it.optString("mode")
                if (mode == "duration") rbDuration.isChecked = true else rbInterval.isChecked = true
                toggleModeContainers()
                val daysMask = it.optInt("daysMask", 0)
                setChecksFromMask(daysMask)
                if (mode == "interval") {
                    startMin = it.optInt("startMin", 0)
                    endMin = it.optInt("endMin", 0)
                    btnStartTime.text = String.format("%02d:%02d", startMin/60, startMin%60)
                    btnEndTime.text = String.format("%02d:%02d", endMin/60, endMin%60)
                    cbInducible.isChecked = it.optBoolean("inducible", false)
                } else {
                    val d = it.optInt("durationMin", 15)
                    val list = intArrayOf(5,10,15,20,30,40,60,120)
                    val idx = list.indexOf(d)
                    if (idx >= 0) spDuration.setSelection(idx) else spDuration.setSelection(2)
                }
                break
            }
        }
    }

    private fun setChecksFromMask(mask: Int) {
        val map = listOf(
            R.id.cbMon to 0,
            R.id.cbTue to 1,
            R.id.cbWed to 2,
            R.id.cbThu to 3,
            R.id.cbFri to 4,
            R.id.cbSat to 5,
            R.id.cbSun to 6,
        )
        map.forEach { (id, bit) ->
            findViewById<CheckBox>(id).isChecked = ((mask shr bit) and 1) == 1
        }
    }
}
