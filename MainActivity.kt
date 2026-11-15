package com.example.sosguardian

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.telephony.SmsManager
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class MainActivity : AppCompatActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var tvActiveContact: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvGpsStatus: TextView
    private lateinit var btnCancel: Button

    private var countDownTimer: CountDownTimer? = null
    private var isSosRunning = false
    private val permissionCode = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        tvActiveContact = findViewById(R.id.tvActiveContact)
        tvStatus = findViewById(R.id.tvStatus)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        btnCancel = findViewById(R.id.btnCancel)
        val btnSos = findViewById<Button>(R.id.btnSos)
        val btnSettings = findViewById<ImageButton>(R.id.btnSettings)

        requestPermissionsIfNeeded()
        updateHeaderDisplay()
        prefetchLocation()

        btnSettings.setOnClickListener { showSettingsDialog() }

        btnSos.setOnClickListener {
            if (isSosRunning) return@setOnClickListener
            val phone = getSavedPhone()
            if (phone.isEmpty()) {
                Toast.makeText(this, "Сначала укажите номер в настройках!", Toast.LENGTH_SHORT).show()
                showSettingsDialog()
                return@setOnClickListener
            }
            if (!hasAllPermissions()) {
                requestPermissionsIfNeeded()
                Toast.makeText(this, "Разрешите геолокацию, SMS и звонки, затем нажмите SOS ещё раз.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startSosCountdown(phone)
        }

        btnCancel.setOnClickListener { cancelSos() }
    }

    private fun startSosCountdown(phone: String) {
        isSosRunning = true
        btnCancel.visibility = View.VISIBLE
        val delaySec = getSavedDelay()
        if (delaySec <= 0) {
            executeSos(phone)
            return
        }
        countDownTimer = object : CountDownTimer(delaySec * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000) + 1
                tvStatus.text = "Отправка SMS и звонок через $sec сек..."
                tvStatus.setTextColor(0xFFEF4444.toInt())
            }
            override fun onFinish() { executeSos(phone) }
        }.start()
    }

    private fun cancelSos() {
        countDownTimer?.cancel()
        countDownTimer = null
        isSosRunning = false
        btnCancel.visibility = View.GONE
        tvStatus.text = "Вызов отменён"
        tvStatus.setTextColor(0xFF71717A.toInt())
    }

    @SuppressLint("MissingPermission")
    private fun executeSos(phone: String) {
        btnCancel.visibility = View.GONE
        tvStatus.text = "Определение координат и отправка..."
        if (!hasAllPermissions()) {
            requestPermissionsIfNeeded()
            isSosRunning = false
            return
        }

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location: Location? ->
                val baseMsg = getSavedMessage()
                val medInfo = getSavedMedical()
                val gpsPart = if (location != null) {
                    " Координаты: https://maps.google.com/?q=${location.latitude},${location.longitude}"
                } else {
                    " (GPS недоступен)"
                }
                val medPart = if (medInfo.isNotEmpty()) " | Мед. инфо: $medInfo" else ""
                sendSms(phone, "$baseMsg$gpsPart$medPart")
                makeCall(phone)
                isSosRunning = false
                tvStatus.text = "Команда отправки SMS выполнена. Проверьте доставку."
                tvStatus.setTextColor(0xFF10B981.toInt())
            }
            .addOnFailureListener {
                sendSms(phone, getSavedMessage())
                makeCall(phone)
                isSosRunning = false
                tvStatus.text = "GPS недоступен. SMS отправлено без координат."
            }
    }

    private fun sendSms(phone: String, text: String) {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(text)
            smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            Toast.makeText(this, "Команда отправки SOS SMS выполнена", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка SMS: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun makeCall(phone: String) {
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")))
    }

    private fun showSettingsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val etPhone = EditText(this).apply {
            hint = "Экстренный номер (+7999...)"
            setText(getSavedPhone())
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val etName = EditText(this).apply {
            hint = "Имя контакта (напр. Мама)"
            setText(getSavedName())
        }
        val etMsg = EditText(this).apply {
            hint = "Текст тревожного SMS"
            setText(getSavedMessage())
        }
        val etMed = EditText(this).apply {
            hint = "Мед. данные (группа крови, аллергии)"
            setText(getSavedMedical())
        }
        layout.addView(etPhone)
        layout.addView(etName)
        layout.addView(etMsg)
        layout.addView(etMed)

        AlertDialog.Builder(this)
            .setTitle("Настройки SOS")
            .setView(layout)
            .setPositiveButton("Сохранить") { _, _ ->
                getSharedPreferences("sos_prefs", Context.MODE_PRIVATE).edit().apply {
                    putString("phone", etPhone.text.toString().trim())
                    putString("name", etName.text.toString().trim())
                    putString("msg", etMsg.text.toString().trim().ifEmpty { "SOS! Мне срочно нужна помощь!" })
                    putString("med", etMed.text.toString().trim())
                    apply()
                }
                updateHeaderDisplay()
                Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun prefetchLocation() {
        if (hasLocationPermission()) {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    tvGpsStatus.text = "GPS готов: ${String.format("%.4f", loc.latitude)}, ${String.format("%.4f", loc.longitude)}"
                }
            }
        }
    }

    private fun updateHeaderDisplay() {
        val phone = getSavedPhone()
        val name = getSavedName()
        tvActiveContact.text = when {
            phone.isEmpty() -> "● Номер не задан"
            name.isNotEmpty() -> "● $name ($phone)"
            else -> "● $phone"
        }
    }

    private fun getPrefs() = getSharedPreferences("sos_prefs", Context.MODE_PRIVATE)
    private fun getSavedPhone() = getPrefs().getString("phone", "") ?: ""
    private fun getSavedName() = getPrefs().getString("name", "") ?: ""
    private fun getSavedMessage() = getPrefs().getString("msg", "SOS! Мне срочно нужна помощь!") ?: "SOS! Мне срочно нужна помощь!"
    private fun getSavedMedical() = getPrefs().getString("med", "") ?: ""
    private fun getSavedDelay() = getPrefs().getInt("delay", 3)

    private fun hasLocationPermission() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasAllPermissions() =
        hasLocationPermission() &&
        ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
        ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        val missing = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE
        ).filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), permissionCode)
        }
    }
}
