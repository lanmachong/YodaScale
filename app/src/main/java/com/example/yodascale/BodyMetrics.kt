package com.example.yodascale

import kotlin.math.roundToInt

/** 用户身体资料，用于派生指标计算。身高 cm，年龄 岁。 */
data class UserProfile(
    val heightCm: Double = 170.0,
    val age: Int = 30,
    val male: Boolean = true,
)

/** 一次称重对应的全部派生指标。 */
data class BodyMetrics(
    val bmi: Double,
    val bodyFat: Double,      // %
    val water: Double,        // %
    val boneKg: Double,
    val muscleKg: Double,
    val visceralFat: Int,     // 1-59
    val bmr: Int,             // kcal/day
    val metabolicAge: Int,    // 岁
    val lbmKg: Double,        // 去脂体重
    val physique: Int,        // 体型评分 1-9（粗略）
)

/**
 * 身体成分计算。
 *
 * 背景：Yoda0 广播的阻抗字段恒为 5000（原始值），不在有效 BIA 范围（150-1200 欧）内，
 * 因此无法用真阻抗算体脂。这里与 okok 一样采用"体重 + 身高 + 年龄 + 性别"的估算公式：
 * 体脂率用 Deurenberg 公式，基础代谢用 Mifflin-St Jeor，其余由去脂体重推导。
 * 所有派生值仅供趋势参考，不代表真实测量——与 okok App 的口径一致。
 */
object BodyMetricsCalculator {

    fun calc(weightKg: Double, p: UserProfile): BodyMetrics {
        val hM = p.heightCm / 100.0
        val bmi = weightKg / (hM * hM)
        val sex = if (p.male) 1.0 else 0.0

        // Deurenberg：成人 1.2*BMI + 0.23*年龄 - 10.8*性别 - 5.4；15 岁以下用青少年公式
        var bf = if (p.age <= 15) {
            1.51 * bmi - 0.70 * p.age - 3.6 * sex + 1.4
        } else {
            1.20 * bmi + 0.23 * p.age - 10.8 * sex - 5.4
        }
        bf = bf.coerceIn(3.0, 60.0)

        val lbm = weightKg * (1.0 - bf / 100.0)
        val water = (lbm * 0.73 / weightKg * 100.0).coerceIn(10.0, 85.0)
        // 骨量：家用体脂秤按「总体重」的 4% 左右估算，而非去脂体重。
        // 参考多款体脂秤说明书：女性约 3.9-4.1%，男性约 4.2-4.4%（体重越大系数略低）。
        // 旧公式 lbm×0.042 只有体重的 2.5-3%，会系统性偏小。
        val boneRatio = if (p.male) {
            if (weightKg < 75.0) 0.044 else 0.042
        } else {
            when {
                weightKg < 45.0 -> 0.039
                weightKg < 75.0 -> 0.041
                else -> 0.040
            }
        }
        val bone = (weightKg * boneRatio).coerceIn(1.0, 8.0)
        val muscle = (lbm - bone).coerceIn(0.0, weightKg)
        val visceral = (bf * 0.55 - 4.0 + p.age * 0.08).roundToInt().coerceIn(1, 59)

        // Mifflin-St Jeor：男 +5，女 -161
        val s = if (p.male) 5.0 else -161.0
        val bmr = (10.0 * weightKg + 6.25 * p.heightCm - 5.0 * p.age + s).roundToInt()
        // 代谢年龄：以同龄人平均体脂率为基准，每高出 1 个百分点约折 0.8 岁
        // （不用 BMR 差值推算——Mifflin 公式下体重越大 BMR 越高，会得出"胖人更年轻"的错误结论）
        val avgBf = if (p.male) 20.0 else 28.0
        val metabolicAge = (p.age + (bf - avgBf) * 0.8).roundToInt()
            .coerceIn(p.age - 15, p.age + 30).coerceIn(10, 90)

        // 体型评分：按体脂率分档（粗略，完整版还要结合肌肉/体重比）
        val leanLimit = if (p.male) 10 else 18
        val fitLimit = if (p.male) 15 else 23
        val normalLimit = if (p.male) 20 else 28
        val heavyLimit = if (p.male) 25 else 33
        val obeseLimit = if (p.male) 30 else 38
        val physique = when {
            bf < leanLimit -> 1
            bf < fitLimit -> 2
            bf < normalLimit -> 3
            bf < heavyLimit -> 4
            bf < obeseLimit -> 5
            else -> 6
        }

        return BodyMetrics(bmi, bf, water, bone, muscle, visceral, bmr, metabolicAge, lbm, physique)
    }
}
