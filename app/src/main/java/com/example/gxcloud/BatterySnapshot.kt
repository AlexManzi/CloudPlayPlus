package com.example.gxcloud

internal data class BatterySnapshot(val percentage: Int?, val milliVolts: Int?) {
    companion object {
        fun from(level: Int, scale: Int, milliVolts: Int): BatterySnapshot {
            val percentage = if (scale > 0 && level in 0..scale) {
                (level * 100L / scale).toInt()
            } else null
            return BatterySnapshot(percentage, milliVolts.takeIf { it > 0 })
        }
    }
}
