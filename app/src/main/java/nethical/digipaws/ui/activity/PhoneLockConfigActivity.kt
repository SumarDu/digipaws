package nethical.digipaws.ui.activity

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import nethical.digipaws.databinding.ActivityPhoneLockConfigBinding
import nethical.digipaws.services.GeneralFeaturesService
import java.util.Calendar

class PhoneLockConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhoneLockConfigBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhoneLockConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
    }

    private fun setupUi() {
        val sp = getSharedPreferences("phone_lock", Context.MODE_PRIVATE)
        val repeatMode = sp.getInt("repeat_mode", 0)
        val startMin = sp.getInt("start_min", 0)
        val endMin = sp.getInt("end_min", 0)
        val daysMask = sp.getInt("days_mask", 0)

        // Pre-fill
        if (repeatMode == 0) binding.radioDaily.isChecked = true else binding.radioWeekly.isChecked = true
        setTimeButtonText(binding.btnStartTime, startMin)
        setTimeButtonText(binding.btnEndTime, endMin)
        applyDaysMask(daysMask)

        toggleDaysVisibility(repeatMode == 1)

        binding.radioDaily.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) toggleDaysVisibility(false)
        }
        binding.radioWeekly.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) toggleDaysVisibility(true)
        }

        binding.btnStartTime.setOnClickListener { pickTime(startMin) { minutes ->
            setTimeButtonText(binding.btnStartTime, minutes)
            binding.btnStartTime.tag = minutes
        } }
        binding.btnEndTime.setOnClickListener { pickTime(endMin) { minutes ->
            setTimeButtonText(binding.btnEndTime, minutes)
            binding.btnEndTime.tag = minutes
        } }

        binding.btnSave.setOnClickListener {
            val rm = if (binding.radioWeekly.isChecked) 1 else 0
            val sm = (binding.btnStartTime.tag as? Int) ?: startMin
            val em = (binding.btnEndTime.tag as? Int) ?: endMin
            val mask = buildDaysMask()

            sp.edit()
                .putInt("repeat_mode", rm)
                .putInt("start_min", sm)
                .putInt("end_min", em)
                .putInt("days_mask", mask)
                .apply()

            // Notify service to refresh
            sendBroadcast(Intent(GeneralFeaturesService.INTENT_ACTION_REFRESH_PHONE_LOCK))
            finish()
        }
    }

    private fun toggleDaysVisibility(visible: Boolean) {
        binding.daysContainer.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun pickTime(initialMin: Int, onPicked: (Int) -> Unit) {
        val h = initialMin / 60
        val m = initialMin % 60
        TimePickerDialog(this, { _, hourOfDay, minute ->
            onPicked(hourOfDay * 60 + minute)
        }, h, m, true).show()
    }

    private fun setTimeButtonText(btn: android.widget.Button, minutesFromMidnight: Int) {
        val h = minutesFromMidnight / 60
        val m = minutesFromMidnight % 60
        btn.text = String.format("%02d:%02d", h, m)
        btn.tag = minutesFromMidnight
    }

    private fun applyDaysMask(mask: Int) {
        binding.cbSun.isChecked = mask and (1 shl 0) != 0
        binding.cbMon.isChecked = mask and (1 shl 1) != 0
        binding.cbTue.isChecked = mask and (1 shl 2) != 0
        binding.cbWed.isChecked = mask and (1 shl 3) != 0
        binding.cbThu.isChecked = mask and (1 shl 4) != 0
        binding.cbFri.isChecked = mask and (1 shl 5) != 0
        binding.cbSat.isChecked = mask and (1 shl 6) != 0
    }

    private fun buildDaysMask(): Int {
        var mask = 0
        if (binding.radioWeekly.isChecked) {
            if (binding.cbSun.isChecked) mask = mask or (1 shl 0)
            if (binding.cbMon.isChecked) mask = mask or (1 shl 1)
            if (binding.cbTue.isChecked) mask = mask or (1 shl 2)
            if (binding.cbWed.isChecked) mask = mask or (1 shl 3)
            if (binding.cbThu.isChecked) mask = mask or (1 shl 4)
            if (binding.cbFri.isChecked) mask = mask or (1 shl 5)
            if (binding.cbSat.isChecked) mask = mask or (1 shl 6)
        }
        return mask
    }
}
