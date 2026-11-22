package com.example.yodascale

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

    fun insert(r: ScaleReading): Long {
        val db = writableDatabase
        // 3 秒内同一体重不重复入库（广播会连续发多帧）
        val since = System.currentTimeMillis() - 3000
        val cursor = db.rawQuery(
            "SELECT id FROM records WHERE ts>? AND raw=? LIMIT 1",
            arrayOf(since.toString(), r.rawWeight.toString())
        )
        val duplicated = cursor.count > 0
        cursor.close()
        if (duplicated) return -1

        val cv = android.content.ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("kg", r.weightKg)
            put("resistance", r.resistance)
            put("raw", r.rawWeight)
            put("unit", r.displayUnit)
            put("mac", r.mac)
        }
        return db.insert("records", null, cv)
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
