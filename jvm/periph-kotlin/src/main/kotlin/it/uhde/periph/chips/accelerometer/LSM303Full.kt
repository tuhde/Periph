package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.RegisterConnection

/** LSM303 full interface — extends [LSM303Minimal]. */
class LSM303Full(
    accelConnection: RegisterConnection,
    magConnection: RegisterConnection
) : LSM303Minimal(accelConnection, magConnection) {

    companion object {
        const val FIFO_BYPASS = 0
        const val FIFO_FIFO = 1
        const val FIFO_STREAM = 2
        const val FIFO_STREAM_TO_FIFO = 3
    }

    private var enabled1 = 0
    private var enabled2 = 0

    /**
     * Set the accelerometer full-scale range.
     *
     * @param rangeG one of 2, 4, 8, 16 g
     */
    fun setRange(rangeG: Int) {
        val index = when (rangeG) {
            RANGE_2G -> 0
            RANGE_4G -> 1
            RANGE_8G -> 2
            RANGE_16G -> 3
            else -> throw IllegalArgumentException("rangeG must be one of 2, 4, 8, 16")
        }
        val ctrl4 = readReg(REG_CTRL_REG4)
        writeReg(REG_CTRL_REG4, (ctrl4 and 0x30.inv()) or (index shl 4))
        this.rangeG = rangeG
    }

    /** Set the accelerometer output data rate (0 powers down). */
    fun setDataRate(rateHz: Int) {
        val bit = rateBit(rateHz)
        if (bit < 0) throw IllegalArgumentException("unsupported rate")
        if (isLowPowerOnly(rateHz) && operatingMode != MODE_LOW_POWER) throw IllegalArgumentException("low-power only rate")
        if (isNormalHighResOnly(rateHz) && operatingMode == MODE_LOW_POWER) throw IllegalArgumentException("rate unavailable in low-power mode")
        val ctrl1 = readReg(REG_CTRL_REG1)
        writeReg(REG_CTRL_REG1, (ctrl1 and 0x0F) or bit)
        this.rateHz = rateHz
        sleepMs(settleMs())
    }

    /** Set the accelerometer operating mode (0, 1 or 2). */
    fun setMode(mode: Int) {
        if (mode < 0 || mode > MODE_HIGH_RESOLUTION) throw IllegalArgumentException("mode must be 0, 1 or 2")
        if (mode == MODE_LOW_POWER && isNormalHighResOnly(rateHz)) throw IllegalArgumentException("rate unavailable in low-power mode")
        if (mode != MODE_LOW_POWER && isLowPowerOnly(rateHz)) throw IllegalArgumentException("low-power only rate")
        var ctrl1 = readReg(REG_CTRL_REG1)
        var ctrl4 = readReg(REG_CTRL_REG4)
        if (mode == MODE_LOW_POWER) {
            ctrl1 = ctrl1 or 0x08
            ctrl4 = ctrl4 and 0x08.inv()
        } else {
            ctrl1 = ctrl1 and 0x08.inv()
            ctrl4 = if (mode == MODE_HIGH_RESOLUTION) ctrl4 or 0x08 else ctrl4 and 0x08.inv()
        }
        writeReg(REG_CTRL_REG1, ctrl1)
        writeReg(REG_CTRL_REG4, ctrl4)
        this.operatingMode = mode
        sleepMs(settleMs())
    }

    /** Enable or disable the accelerometer axes. */
    fun setAxes(x: Boolean, y: Boolean, z: Boolean) {
        val ctrl1 = readReg(REG_CTRL_REG1)
        val axes = (if (x) 1 else 0) or (if (y) 2 else 0) or (if (z) 4 else 0)
        writeReg(REG_CTRL_REG1, (ctrl1 and 0xF8) or axes)
    }

    /** Stop accelerometer conversions. */
    fun powerDown() {
        val ctrl1 = readReg(REG_CTRL_REG1)
        writeReg(REG_CTRL_REG1, ctrl1 and 0x0F)
        rateHz = 0
    }

    /** Read raw accelerometer counts. */
    fun readAccelerationRaw(): IntArray {
        val raw = readBurst(REG_OUT_X_L, 6)
        return intArrayOf(decodeAxis(raw[0], raw[1]), decodeAxis(raw[2], raw[3]), decodeAxis(raw[4], raw[5]))
    }

    /** @return true when STATUS_REG_A.ZYXDA is set */
    fun newDataAvailable(): Boolean = (readReg(REG_STATUS) and 0x08) != 0

    /** @return the STATUS_REG_A byte */
    fun readStatus(): Int = readReg(REG_STATUS)

    /** Configure the accelerometer high-pass filter. */
    fun setHighPass(mode: Int, cutoff: Int, filterOutput: Boolean, onIa1: Boolean, onIa2: Boolean, onClick: Boolean) {
        if (mode < 0 || mode > 3 || cutoff < 0 || cutoff > 3) throw IllegalArgumentException("mode and cutoff must be 0-3")
        var value = (mode shl 6) or (cutoff shl 4)
        if (filterOutput) value = value or 0x08
        if (onClick) value = value or 0x04
        if (onIa2) value = value or 0x02
        if (onIa1) value = value or 0x01
        writeReg(REG_CTRL_REG2, value)
    }

    /** Reset the high-pass filter by reading REFERENCE_A. */
    fun resetHighPass(): Int = readReg(REG_REFERENCE)

    /** Write the high-pass reference value. */
    fun setReference(value: Int) = writeReg(REG_REFERENCE, value and 0xFF)

    /** Configure the FIFO. */
    fun setFifo(mode: Int, watermark: Int, triggerInt2: Boolean) {
        if (mode < 0 || mode > 3 || watermark < 0 || watermark > 31) throw IllegalArgumentException("invalid FIFO mode or watermark")
        writeReg(REG_FIFO_CTRL, (mode shl 6) or (if (triggerInt2) 0x20 else 0) or watermark)
        val ctrl5 = readReg(REG_CTRL_REG5)
        writeReg(REG_CTRL_REG5, if (mode == 0) ctrl5 and 0x40.inv() else ctrl5 or 0x40)
    }

    /** @return the FIFO level (0-32) */
    fun fifoLevel(): Int = readReg(REG_FIFO_SRC) and 0x1F

    /** Drain up to [count] sample sets; a negative [count] reads all. */
    fun readFifo(count: Int): Array<DoubleArray> {
        val level = fifoLevel()
        val n = if (count < 0) level else minOf(count, level)
        val scale = sensitivityMg() / 1000.0
        return Array(n) {
            val raw = readBurst(REG_OUT_X_L, 6)
            doubleArrayOf(
                decodeAxis(raw[0], raw[1]) * scale,
                decodeAxis(raw[2], raw[3]) * scale,
                decodeAxis(raw[4], raw[5]) * scale
            )
        }
    }

    /** @return {watermark, overrun, empty, level} */
    fun readFifoStatus(): Array<Any> {
        val raw = readReg(REG_FIFO_SRC)
        return arrayOf((raw and 0x80) != 0, (raw and 0x40) != 0, (raw and 0x20) != 0, raw and 0x1F)
    }

    private fun generatorRegs(generator: Int): IntArray = when (generator) {
        1 -> intArrayOf(REG_INT1_CFG, REG_INT1_THS, REG_INT1_DUR)
        2 -> intArrayOf(REG_INT2_CFG, REG_INT2_THS, REG_INT2_DUR)
        else -> throw IllegalArgumentException("generator must be 1 or 2")
    }

    private fun thresholdCode(thresholdG: Double): Int =
        roundHalfAway(thresholdG / (rangeG.toDouble() / 128.0)).coerceIn(0, 127)

    private fun durationCode(durationMs: Double, maximum: Int): Int {
        if (durationMs <= 0 || rateHz <= 0) return 0
        return roundHalfAway(durationMs * rateHz / 1000.0).coerceIn(0, maximum)
    }

    /** Configure an inertial interrupt generator. */
    fun setMotionInterrupt(generator: Int, events: Int, thresholdG: Double, durationMs: Double, andMode: Boolean) {
        val regs = generatorRegs(generator)
        if (events < 0 || events > 0x3F) throw IllegalArgumentException("events must be a 6-bit mask")
        writeReg(regs[0], (if (andMode) 0x80 else 0) or (events and 0x3F))
        writeReg(regs[1], thresholdCode(thresholdG))
        writeReg(regs[2], durationCode(durationMs, 127))
    }

    /** Configure the free-fall detector. */
    fun setFreeFall(generator: Int, thresholdG: Double, durationMs: Double) {
        val regs = generatorRegs(generator)
        writeReg(regs[0], 0x95)
        writeReg(regs[1], thresholdCode(thresholdG))
        writeReg(regs[2], durationCode(durationMs, 127))
    }

    /** Configure 6D / 4D orientation detection. */
    fun setOrientation(generator: Int, position: Boolean, fourD: Boolean) {
        val regs = generatorRegs(generator)
        writeReg(regs[0], (if (position) 0xC0 else 0x40) or (if (fourD) 0x0F else 0x3F))
        val ctrl5 = readReg(REG_CTRL_REG5)
        val bit = if (generator == 1) 0x04 else 0x01
        writeReg(REG_CTRL_REG5, if (fourD) ctrl5 or bit else ctrl5 and bit.inv())
    }

    /** Latch a generator's interrupt. */
    fun setLatch(generator: Int, enabled: Boolean) {
        val bit = when (generator) {
            1 -> 0x08
            2 -> 0x02
            else -> throw IllegalArgumentException("generator must be 1 or 2")
        }
        val ctrl5 = readReg(REG_CTRL_REG5)
        writeReg(REG_CTRL_REG5, if (enabled) ctrl5 or bit else ctrl5 and bit.inv())
    }

    /** Read a generator's source register — clears the interrupt. */
    fun readInterruptSource(generator: Int): Int = when (generator) {
        1 -> readReg(REG_INT1_SRC)
        2 -> readReg(REG_INT2_SRC)
        else -> throw IllegalArgumentException("generator must be 1 or 2")
    }

    /** Configure single / double click detection. */
    fun setClick(single: Int, doubleClick: Int, thresholdG: Double, timeLimitMs: Double, latencyMs: Double, windowMs: Double) {
        if (single < 0 || single > 7 || doubleClick < 0 || doubleClick > 7) throw IllegalArgumentException("axis masks must be 0-7")
        val cfg = (single and 0x01) or ((single and 0x02) shl 1) or ((single and 0x04) shl 2) or
            ((doubleClick and 0x01) shl 1) or ((doubleClick and 0x02) shl 2) or ((doubleClick and 0x04) shl 3)
        writeReg(REG_CLICK_CFG, cfg)
        writeReg(REG_CLICK_THS, thresholdCode(thresholdG))
        writeReg(REG_TIME_LIMIT, durationCode(timeLimitMs, 127))
        writeReg(REG_TIME_LATENCY, durationCode(latencyMs, 255))
        writeReg(REG_TIME_WINDOW, durationCode(windowMs, 255))
    }

    /** Read CLICK_SRC_A — clears the interrupt. */
    fun readClickSource(): Int = readReg(REG_CLICK_SRC)

    /** Set the shared INT1 / INT2 polarity. */
    fun setInterruptPolarity(activeLow: Boolean) {
        val ctrl6 = readReg(REG_CTRL_REG6)
        writeReg(REG_CTRL_REG6, if (activeLow) ctrl6 or 0x02 else ctrl6 and 0x02.inv())
    }

    /** Route one interrupt source to an INT pin. */
    fun enableInterrupt(source: Int, pin: Int) {
        if (source == LSM303Source.SOURCE_MAG_DATA_READY) throw IllegalArgumentException("magnetometer DRDY cannot be routed")
        val route = route(source, pin)
        writeReg(route[0], readReg(route[0]) or route[1])
        if (route[2] == 1) enabled1 = enabled1 or (1 shl sourceBit(source))
        else enabled2 = enabled2 or (1 shl sourceBit(source))
    }

    /** Remove one interrupt source from an INT pin. */
    fun disableInterrupt(source: Int, pin: Int) {
        if (source == LSM303Source.SOURCE_MAG_DATA_READY) throw IllegalArgumentException("magnetometer DRDY cannot be routed")
        val route = route(source, pin)
        writeReg(route[0], readReg(route[0]) and route[1].inv())
        if (route[2] == 1) enabled1 = enabled1 and (1 shl sourceBit(source)).inv()
        else enabled2 = enabled2 and (1 shl sourceBit(source)).inv()
    }

    private fun route(source: Int, pin: Int): IntArray {
        if (pin != 1 && pin != 2) throw IllegalArgumentException("pin must be 1 or 2")
        val both = if (pin == 2) REG_CTRL_REG6 else REG_CTRL_REG3
        return when (source) {
            LSM303Source.SOURCE_DATA_READY -> intArrayOf(REG_CTRL_REG3, 0x10, 1)
            LSM303Source.SOURCE_WATERMARK -> intArrayOf(REG_CTRL_REG3, 0x04, 1)
            LSM303Source.SOURCE_OVERRUN -> intArrayOf(REG_CTRL_REG3, 0x02, 1)
            LSM303Source.SOURCE_IA1 -> intArrayOf(both, 0x40, pin)
            LSM303Source.SOURCE_IA2 -> intArrayOf(both, 0x20, pin)
            LSM303Source.SOURCE_CLICK -> intArrayOf(both, 0x80, pin)
            LSM303Source.SOURCE_BOOT -> intArrayOf(REG_CTRL_REG6, 0x10, 2)
            else -> throw IllegalArgumentException("unknown interrupt source")
        }
    }

    private fun sourceBit(source: Int): Int {
        var s = source
        var bit = 0
        while (s > 1) {
            s = s shr 1
            bit++
        }
        return bit
    }

    /** Read every enabled source and return the combined status. */
    fun pollInterrupt(): Int {
        val enabled = enabled1 or enabled2
        var status = 0
        if ((enabled and 0x01) != 0 && (readReg(REG_STATUS) and 0x08) != 0) status = status or LSM303Source.SOURCE_DATA_READY
        if ((enabled and 0x06) != 0) {
            val fifo = readReg(REG_FIFO_SRC)
            if ((enabled and 0x02) != 0 && (fifo and 0x80) != 0) status = status or LSM303Source.SOURCE_WATERMARK
            if ((enabled and 0x04) != 0 && (fifo and 0x40) != 0) status = status or LSM303Source.SOURCE_OVERRUN
        }
        if ((enabled and 0x08) != 0 && (readReg(REG_INT1_SRC) and 0x40) != 0) status = status or LSM303Source.SOURCE_IA1
        if ((enabled and 0x10) != 0 && (readReg(REG_INT2_SRC) and 0x40) != 0) status = status or LSM303Source.SOURCE_IA2
        if ((enabled and 0x20) != 0 && (readReg(REG_CLICK_SRC) and 0x40) != 0) status = status or LSM303Source.SOURCE_CLICK
        if ((magReadReg(MAG_REG_SR) and 0x01) != 0) status = status or LSM303Source.SOURCE_MAG_DATA_READY
        return status
    }

    /** Set the magnetometer gain / full-scale range in µT. */
    fun setMagRange(rangeUt: Int) {
        val index = MAG_RANGES.indexOf(rangeUt)
        if (index < 0) throw IllegalArgumentException("rangeUt must be 130, 190, 250, 400, 470, 560 or 810")
        magGainCode = MAG_CODES[index]
        magWriteReg(MAG_REG_CRB, magGainCode)
    }

    /** Set the magnetometer output data rate in Hz. */
    fun setMagDataRate(rateHz: Double) {
        var index = -1
        for (i in MAG_RATES.indices) if (kotlin.math.abs(MAG_RATES[i] - rateHz) < 1e-9) index = i
        if (index < 0) throw IllegalArgumentException("rateHz must be 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220")
        val cra = magReadReg(MAG_REG_CRA)
        magWriteReg(MAG_REG_CRA, (cra and 0xE3) or MAG_RATE_BITS[index])
        this.magRateHz = rateHz
    }

    /** Set the magnetometer operating mode (0 continuous, 1 single, 2 sleep). */
    fun setMagMode(mode: Int) {
        val code = when (mode) {
            0 -> 0x00
            1 -> 0x01
            2 -> 0x03
            else -> throw IllegalArgumentException("mode must be 0, 1 or 2")
        }
        magWriteReg(MAG_REG_MR, code)
        if (mode == 1) sleepMs(kotlin.math.ceil(1000.0 / magRateHz).toInt())
    }

    /** Read raw magnetometer counts, reordered to X, Y, Z. */
    fun readMagneticRaw(): IntArray {
        val raw = magReadBurst(MAG_REG_OUT_X_H, 6)
        val x = (((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)).toShort().toInt()
        val z = (((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)).toShort().toInt()
        val y = (((raw[4].toInt() and 0xFF) shl 8) or (raw[5].toInt() and 0xFF)).toShort().toInt()
        return intArrayOf(x, y, z)
    }

    /** @return true when SR_REG_M.DRDY is set */
    fun magDataReady(): Boolean = (magReadReg(MAG_REG_SR) and 0x01) != 0

    /** @return {drdy, lock} */
    fun readMagStatus(): BooleanArray {
        val raw = magReadReg(MAG_REG_SR)
        return booleanArrayOf((raw and 0x01) != 0, (raw and 0x02) != 0)
    }

    /** Read the magnetometer identity bytes. */
    fun readMagIdentity(): ByteArray = magReadBurst(MAG_REG_IRA, 3)

    /** Enable or disable the magnetometer temperature sensor. */
    fun enableTemperature(enabled: Boolean) {
        val cra = magReadReg(MAG_REG_CRA)
        magWriteReg(MAG_REG_CRA, if (enabled) cra or 0x80 else cra and 0x80.inv())
    }

    /** Read the relative magnetometer temperature in °C. */
    fun readTemperatureDelta(): Double {
        if ((magReadReg(MAG_REG_CRA) and 0x80) == 0) {
            enableTemperature(true)
            sleepMs(kotlin.math.ceil(1000.0 / magRateHz).toInt())
        }
        val raw = (((magReadReg(MAG_REG_TEMP_H) shl 8) or magReadReg(MAG_REG_TEMP_L))).toShort().toInt()
        return (raw shr 4) / 8.0
    }

    /** Compute the compass heading in degrees (0-360). */
    fun heading(tiltCompensate: Boolean): Double {
        val m = readMagnetic()
        val angle = if (tiltCompensate) {
            val a = readAcceleration()
            val roll = kotlin.math.atan2(a[1], a[2])
            val pitch = kotlin.math.atan2(-a[0], a[1] * kotlin.math.sin(roll) + a[2] * kotlin.math.cos(roll))
            val xh = m[0] * kotlin.math.cos(pitch) + m[2] * kotlin.math.sin(pitch)
            val yh = m[0] * kotlin.math.sin(roll) * kotlin.math.sin(pitch) + m[1] * kotlin.math.cos(roll) -
                m[2] * kotlin.math.sin(roll) * kotlin.math.cos(pitch)
            kotlin.math.atan2(yh, xh)
        } else {
            kotlin.math.atan2(m[1], m[0])
        }
        val degrees = angle * 180 / Math.PI
        return if (degrees < 0) degrees + 360 else degrees
    }

    /** Reboot the accelerometer memory and re-run the Initialization Sequence. */
    fun reboot() {
        val ctrl5 = readReg(REG_CTRL_REG5)
        writeReg(REG_CTRL_REG5, ctrl5 or 0x80)
        sleepMs(BOOT_DELAY_MS)
        rangeG = RANGE_2G
        operatingMode = MODE_HIGH_RESOLUTION
        rateHz = 100
        magGainCode = 0x20
        magRateHz = 15.0
        enabled1 = 0
        enabled2 = 0
        runInitSequence()
    }
}
