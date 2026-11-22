package com.example.yodascale

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class WeightRecord(
    val id: Long,
    val timestamp: Long,
    val weightKg: Double,
    val resistance: Int,
    val rawWeight: Int,
    val unit: String,
    val mac: String,
)

class WeightDb(context: Context) : SQLiteOpenHelper(context, "yoda_scale.db", null, 1) {

    companion object {
        /** 10 秒内、体重差 ≤ 0.05kg 的读数视为同一次测量，以最后一次为准 */
        private const val MERGE_WINDOW_MS = 10_000L
        /** 清理历史库里已存在的重复读数（60 秒内、差异 ≤ 0.05kg） */
        private const val LEGACY_MERGE_WINDOW_MS = 60_000L
        private const val KG_TOLERANCE = 0.05
    }

    init {
        // 旧版本可能已写入重复读数，打开数据库时自动合并，只保留最后一条
        dedupeExisting()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                kg REAL NOT NULL,
                resistance INTEGER NOT NULL,
                raw INTEGER NOT NULL,
                unit TEXT NOT NULL,
                mac TEXT NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS records")
        onCreate(db)
    }

    /**
     * 写入读数。短时间内的相同体重不新增行，而是更新原行（后到的覆盖先到的），
     * 避免一次扫描产生一堆内容相同的历史记录。返回被写入/更新的行 id。
     */
    fun insert(r: ScaleReading): Long {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val cursor = db.rawQuery(
            "SELECT id FROM records WHERE ts>=? AND ABS(kg-?)<=? ORDER BY ts DESC LIMIT 1",
            arrayOf(
                (now - MERGE_WINDOW_MS).toString(),
                r.weightKg.toString(),
                KG_TOLERANCE.toString(),
            ),
        )
        val existingId = cursor.use { if (it.moveToFirst()) it.getLong(0) else -1L }

        val cv = ContentValues().apply {
            put("ts", now)
            put("kg", r.weightKg)
            put("resistance", r.resistance)
            put("raw", r.rawWeight)
            put("unit", r.displayUnit)
            put("mac", r.mac)
        }
        if (existingId > 0) {
            db.update("records", cv, "id=?", arrayOf(existingId.toString()))
            return existingId
        }
        return db.insert("records", null, cv)
    }

    /** 合并历史重复行：同一体重在 60 秒内出现多次时，删掉较早的行，保留最后一条。 */
    fun dedupeExisting() {
        writableDatabase.execSQL(
            """DELETE FROM records WHERE id IN (
                SELECT a.id FROM records a
                JOIN records b ON a.id < b.id
                WHERE ABS(a.kg - b.kg) <= $KG_TOLERANCE
                  AND ABS(a.ts - b.ts) <= $LEGACY_MERGE_WINDOW_MS
            )"""
        )
    }

    fun all(): List<WeightRecord> {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM records ORDER BY ts DESC", null)
        val list = mutableListOf<WeightRecord>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    WeightRecord(
                        it.getLong(it.getColumnIndexOrThrow("id")),
                        it.getLong(it.getColumnIndexOrThrow("ts")),
                        it.getDouble(it.getColumnIndexOrThrow("kg")),
                        it.getInt(it.getColumnIndexOrThrow("resistance")),
                        it.getInt(it.getColumnIndexOrThrow("raw")),
                        it.getString(it.getColumnIndexOrThrow("unit")),
                        it.getString(it.getColumnIndexOrThrow("mac")),
                    )
                )
            }
        }
        return list
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM records")
    }

    fun exportCsv(p: UserProfile): String {
        val sb = StringBuilder("timestamp,weight_kg,bmi,body_fat_pct,water_pct,bone_kg,muscle_kg,visceral_fat,bmr_kcal,metabolic_age,raw,unit,resistance,mac\n")
        all().reversed().forEach {
            val m = BodyMetricsCalculator.calc(it.weightKg, p)
            sb.append("${it.timestamp},${it.weightKg},${m.bmi},${m.bodyFat},${m.water},")
            sb.append("${m.boneKg},${m.muscleKg},${m.visceralFat},${m.bmr},${m.metabolicAge},")
            sb.append("${it.rawWeight},${it.unit},${it.resistance},${it.mac}\n")
        }
        return sb.toString()
    }
}
