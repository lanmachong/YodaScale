package com.example.yodascale

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import android.annotation.SuppressLint
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var db: WeightDb
    private lateinit var tvWeight: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvProfile: TextView
    private lateinit var tvCompare: TextView
    private lateinit var metricsGrid: LinearLayout
    private lateinit var listHistory: LinearLayout
    private lateinit var btnScan: Button

    private var adapter: BluetoothAdapter? = null
    private var scanning = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    private val prefs by lazy { getSharedPreferences("profile", Context.MODE_PRIVATE) }
    private lateinit var profile: UserProfile
    private var latestKg: Double? = null
    /** 本次扫描暂存的最后一次锁定读数；停扫时才合并入库，避免重复记录 */
    private var pendingReading: ScaleReading? = null

    // 稳定读数后自动停扫的延时（省电），与扫描总超时兜底
    private val autoStopDelayMs = 3000L
    private val scanTimeoutMs = 90_000L
    private val stopScanRunnable = Runnable {
        if (scanning) stopScanning()
    }

    private fun scheduleAutoStop() {
        mainHandler.removeCallbacks(stopScanRunnable)
        mainHandler.postDelayed(stopScanRunnable, autoStopDelayMs)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val manufacturerData = record.manufacturerSpecificData ?: return
            for (i in 0 until manufacturerData.size()) {
                val bytes = manufacturerData.valueAt(i) ?: continue
                if (!ScaleParser.looksLikeScale(bytes)) continue
                val reading = ScaleParser.parse(bytes) ?: continue
                if (!reading.locked) continue
                mainHandler.post { onReading(reading) }
                return
            }
        }

        override fun onScanFailed(errorCode: Int) {
            mainHandler.post {
                tvStatus.text = "扫描失败（错误码 $errorCode）"
                stopScanning()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = WeightDb(this)
        profile = loadProfile()

        tvWeight = findViewById(R.id.tvWeight)
        tvMeta = findViewById(R.id.tvMeta)
        tvStatus = findViewById(R.id.tvStatus)
        tvProfile = findViewById(R.id.tvProfile)
        tvCompare = findViewById(R.id.tvCompare)
        metricsGrid = findViewById(R.id.metricsGrid)
        listHistory = findViewById(R.id.listHistory)
        btnScan = findViewById(R.id.btnScan)

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = btManager.adapter

        btnScan.setOnClickListener { if (scanning) stopScanning() else startScanning() }
        findViewById<View>(R.id.btnProfile).setOnClickListener { showProfileDialog() }
        tvProfile.setOnClickListener { showProfileDialog() }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            db.clear()
            pendingReading = null
            latestKg = null
            renderAll()
            tvStatus.text = "历史已清空"
        }
        findViewById<View>(R.id.btnExport).setOnClickListener { exportCsv() }
        findViewById<View>(R.id.btnUpdate).setOnClickListener { UpdateManager.checkForUpdate(this) }

        renderAll()
        if (!hasPermissions()) requestPermissions()
        else tvStatus.text = "就绪 · 点「开始扫描」后站上秤"
    }

    private fun loadProfile(): UserProfile = UserProfile(
        heightCm = prefs.getFloat("height", 170f).toDouble(),
        age = prefs.getInt("age", 30),
        male = prefs.getBoolean("male", true),
    )

    private fun showProfileDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
        }
        val etHeight = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(profile.heightCm.toString())
            hint = "身高（厘米）"
        }
        val etAge = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(profile.age.toString())
            hint = "年龄"
        }
        val spSex = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("男", "女"),
            )
            setSelection(if (profile.male) 0 else 1)
        }
        box.addView(TextView(this).apply { text = "身高（cm）"; textSize = 13f })
        box.addView(etHeight)
        box.addView(TextView(this).apply {
            text = "年龄"; textSize = 13f; setPadding(0, 16, 0, 0)
        })
        box.addView(etAge)
        box.addView(TextView(this).apply {
            text = "性别"; textSize = 13f; setPadding(0, 16, 0, 0)
        })
        box.addView(spSex)

        AlertDialog.Builder(this)
            .setTitle("身体资料")
            .setMessage("用于计算体脂率、基础代谢等估算值")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val h = etHeight.text.toString().toDoubleOrNull() ?: 170.0
                val a = etAge.text.toString().toIntOrNull() ?: 30
                profile = UserProfile(
                    heightCm = h.coerceIn(80.0, 250.0),
                    age = a.coerceIn(3, 120),
                    male = spSex.selectedItemPosition == 0,
                )
                prefs.edit()
                    .putFloat("height", profile.heightCm.toFloat())
                    .putInt("age", profile.age)
                    .putBoolean("male", profile.male)
                    .apply()
                renderAll()
                tvStatus.text = "资料已更新"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 广播中会连续收到多帧相同读数：先只更新暂存值和界面，停扫时统一写入最后一次。 */
    private fun onReading(r: ScaleReading) {
        pendingReading = r
        latestKg = r.weightKg
        tvWeight.text = String.format("%.1f", r.weightKg)
        tvMeta.text = "原始值 ${r.rawWeight} · 电阻 ${r.resistance} · ${r.mac}"
        renderMetrics()
        tvStatus.text = "读数已锁定 · 即将合并记录并停止扫描"
        // 收到稳定锁定读数后，延时自动停扫，避免蓝牙一直开着耗电
        scheduleAutoStop()
    }

    /** 合并本次扫描的全部读数：只把最后一次写入数据库，并刷新历史与对比。 */
    private fun commitPendingReading() {
        val r = pendingReading ?: return
        pendingReading = null
        if (db.insert(r) > 0) {
            tvStatus.text = "已记录 ${dateFmt.format(Date())} · 扫描已停止（省电）"
            renderCompare()
            renderHistory()
        }
    }

    private fun renderAll() {
        tvProfile.text = "资料：${profile.heightCm.toInt()}cm · ${profile.age}岁 · ${if (profile.male) "男" else "女"}（点击修改）"
        val last = db.all().firstOrNull()
        if (latestKg == null && last != null) {
            latestKg = last.weightKg
            tvMeta.text = "最近一次 ${dateFmt.format(Date(last.timestamp))}"
        }
        tvWeight.text = if (latestKg == null) "--.-" else String.format("%.1f", latestKg!!)
        renderMetrics()
        renderCompare()
        renderHistory()
    }

    private fun renderMetrics() {
        metricsGrid.removeAllViews()
        val kg = latestKg
        if (kg == null) {
            metricsGrid.addView(TextView(this).apply {
                text = "站上秤后显示体脂率、肌肉量等估算值"
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
                setPadding(0, 16, 0, 16)
            })
            return
        }
        val m = BodyMetricsCalculator.calc(kg, profile)
        val cards = listOf(
            "体脂率" to String.format("%.1f%%", m.bodyFat),
            "BMI" to String.format("%.1f", m.bmi),
            "肌肉量" to String.format("%.1f kg", m.muscleKg),
            "身体水分" to String.format("%.1f%%", m.water),
            "骨量" to String.format("%.2f kg", m.boneKg),
            "基础代谢" to "${m.bmr} kcal",
            "内脏脂肪" to "${m.visceralFat} 级",
            "代谢年龄" to "${m.metabolicAge} 岁",
        )
        cards.chunked(2).forEach { row ->
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, 5, 0, 5) }
            }
            row.forEach { (label, value) ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.card_bg))
                    setPadding(20, 16, 20, 16)
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                    ).apply { setMargins(5, 0, 5, 0) }
                }
                card.addView(TextView(this).apply {
                    text = label
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
                })
                card.addView(TextView(this).apply {
                    text = value
                    textSize = 19f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                    setPadding(0, 4, 0, 0)
                })
                rowLayout.addView(card)
            }
            metricsGrid.addView(rowLayout)
        }
        metricsGrid.addView(TextView(this).apply {
            text = "以上为公式估算值，仅供参考（本机阻抗读数为固定值，非真实测量）"
            textSize = 10f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
            setPadding(6, 10, 6, 0)
        })
    }

    private fun renderCompare() {
        val records = db.all()
        fun monthAvg(year: Int, month: Int): Pair<Double, Double>? {
            val list = records.filter {
                val c = Calendar.getInstance()
                c.timeInMillis = it.timestamp
                c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) == month
            }
            if (list.isEmpty()) return null
            return list.map { it.weightKg }.average() to
                list.map { BodyMetricsCalculator.calc(it.weightKg, profile).bodyFat }.average()
        }
        val now = Calendar.getInstance()
        val cur = monthAvg(now.get(Calendar.YEAR), now.get(Calendar.MONTH))
        val prevCal = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }
        val prev = monthAvg(prevCal.get(Calendar.YEAR), prevCal.get(Calendar.MONTH))

        tvCompare.text = when {
            prev == null -> "上月暂无数据，记录一段时间后即可查看月度对比"
            else -> {
                val cKg = cur?.first ?: prev.first
                val cBf = cur?.second ?: prev.second
                val dKg = cKg - prev.first
                val dBf = cBf - prev.second
                buildString {
                    append("本月平均：${String.format("%.1f", cKg)} kg · 体脂 ${String.format("%.1f%%", cBf)}\n")
                    append("上月平均：${String.format("%.1f", prev.first)} kg · 体脂 ${String.format("%.1f%%", prev.second)}\n")
                    append("变化：体重 ${String.format("%+.1f", dKg)} kg · 体脂 ${String.format("%+.1f", dBf)} 个百分点")
                }
            }
        }
    }

    private fun renderHistory() {
        listHistory.removeAllViews()
        val records = db.all()
        if (records.isEmpty()) {
            listHistory.addView(TextView(this).apply {
                text = "暂无记录"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
                setPadding(0, 24, 0, 24)
            })
            return
        }
        records.forEach { rec ->
            val m = BodyMetricsCalculator.calc(rec.weightKg, profile)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 18, 0, 18)
            }
            val left = TextView(this).apply {
                text = dateFmt.format(Date(rec.timestamp))
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val right = TextView(this).apply {
                text = String.format("%.1f kg  ·  体脂 %.1f%%", rec.weightKg, m.bodyFat)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
            }
            row.addView(left)
            row.addView(right)
            listHistory.addView(row)
        }
    }

    private fun exportCsv() {
        val csv = db.exportCsv(profile)
        val file = File(cacheDir, "weight_export.csv")
        file.writeText(csv, Charsets.UTF_8)
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "导出体重与体成分记录"))
    }

    private fun hasPermissions(): Boolean {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            needed += Manifest.permission.BLUETOOTH
            needed += Manifest.permission.BLUETOOTH_ADMIN
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val array = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
        ActivityCompat.requestPermissions(this, array, 100)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        tvStatus.text = if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            "就绪 · 点「开始扫描」后站上秤"
        } else {
            "权限被拒绝，请在系统设置中开启蓝牙与定位权限"
        }
    }

    @SuppressLint("MissingPermission") // 已通过 hasPermissions() 校验
    private fun startScanning() {
        if (adapter == null || !adapter!!.isEnabled) {
            tvStatus.text = "请先打开手机蓝牙"
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            return
        }
        if (!hasPermissions()) {
            requestPermissions()
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        pendingReading = null
        adapter!!.bluetoothLeScanner.startScan(null, settings, scanCallback)
        scanning = true
        btnScan.text = "停止扫描"
        tvStatus.text = "扫描中 · 请站上秤，保持赤脚"
        // 兜底：90 秒内没有任何锁定读数则自动停扫，防止忘记关
        mainHandler.removeCallbacks(stopScanRunnable)
        mainHandler.postDelayed(stopScanRunnable, scanTimeoutMs)
    }

    @SuppressLint("MissingPermission") // 扫描期权限已授予；stopScan 不抛权限异常
    private fun stopScanning() {
        mainHandler.removeCallbacks(stopScanRunnable)
        commitPendingReading()
        if (!scanning) return
        try {
            adapter!!.bluetoothLeScanner.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        scanning = false
        btnScan.text = "开始扫描"
    }

    override fun onDestroy() {
        stopScanning()
        db.close()
        super.onDestroy()
    }
}
