package it.uhde.periph.chips.light

import it.uhde.periph.transport.Transport

/**
 * APDS-9960 — full driver. Extends [Apds9960Minimal] with proximity, gesture,
 * wait engine, threshold and interrupt configuration, status queries, and device identification.
 */
class Apds9960Full(
    transport: Transport
) : Apds9960Minimal(transport) {

    /**
     * Enable or disable the proximity engine.
     *
     * @param enabled true to enable PEN, false to disable
     */
    fun enableProximity(enabled: Boolean) {
        var v = readReg(REG_ENABLE)
        v = if (enabled) v or 0x04 else v and 0x04.inv()
        writeReg(REG_ENABLE, v)
    }

    /**
     * Read the proximity count.
     *
     * @return proximity count 0-255; higher means closer
     */
    fun proximity(): Int = readReg(REG_PDATA)

    /**
     * Enable or disable the wait engine.
     *
     * @param enabled true to enable WEN, false to disable
     */
    fun enableWait(enabled: Boolean) {
        var v = readReg(REG_ENABLE)
        v = if (enabled) v or 0x08 else v and 0x08.inv()
        writeReg(REG_ENABLE, v)
    }

    /**
     * Configure the wait time between ALS/proximity cycles.
     *
     * @param wtime WTIME register value 0-255
     * @param wlong true to enable WLONG 12x multiplier
     */
    fun configureWait(wtime: Int, wlong: Boolean = false) {
        writeReg(REG_WTIME, wtime and 0xFF)
        var c1 = readReg(REG_CONFIG1)
        c1 = if (wlong) c1 or 0x02 else c1 and 0x02.inv()
        c1 = (c1 and 0x03) or 0x60
        writeReg(REG_CONFIG1, c1)
    }

    /**
     * Configure ALS integration time and gain.
     *
     * @param atime ATIME register value 0-255
     * @param again ALS gain 0-3 (0=1x, 1=4x, 2=16x, 3=64x)
     */
    fun configureAls(atime: Int, again: Int) {
        writeReg(REG_ATIME, atime and 0xFF)
        var ctrl = readReg(REG_CONTROL)
        ctrl = (ctrl and 0xFC) or (again and 0x03)
        writeReg(REG_CONTROL, ctrl)
    }

    /**
     * Configure proximity LED drive, gain, pulse count and length.
     */
    fun configureProximityLed(ldrive: Int, pgain: Int, ppulse: Int, pplen: Int) {
        var ctrl = readReg(REG_CONTROL)
        ctrl = ((ldrive and 0x03) shl 6) or ((pgain and 0x03) shl 2) or (ctrl and 0x03)
        writeReg(REG_CONTROL, ctrl)
        writeReg(REG_PPULSE, ((pplen and 0x03) shl 6) or (ppulse and 0x3F))
    }

    /**
     * Set additional LED current boost.
     *
     * @param boost LED_BOOST 0-3 (0=100%, 1=150%, 2=200%, 3=300%)
     */
    fun setLedBoost(boost: Int) {
        var c2 = readReg(REG_CONFIG2)
        c2 = (c2 and 0xCF) or ((boost and 0x03) shl 4) or 0x01
        writeReg(REG_CONFIG2, c2)
    }

    /**
     * Set ALS interrupt thresholds.
     */
    fun alsThreshold(low: Int, high: Int) {
        writeReg(REG_AILTL, low and 0xFF)
        writeReg(REG_AILTH, (low shr 8) and 0xFF)
        writeReg(REG_AIHTL, high and 0xFF)
        writeReg(REG_AIHTH, (high shr 8) and 0xFF)
    }

    /**
     * Set proximity interrupt thresholds.
     */
    fun proximityThreshold(low: Int, high: Int) {
        writeReg(REG_PILT, low and 0xFF)
        writeReg(REG_PIHT, high and 0xFF)
    }

    /**
     * Set interrupt persistence filters.
     */
    fun setPersistence(ppers: Int, apers: Int) {
        writeReg(REG_PERS, ((ppers and 0x0F) shl 4) or (apers and 0x0F))
    }

    /**
     * Enable or disable ALS interrupt.
     */
    fun enableAlsInterrupt(enabled: Boolean) {
        var v = readReg(REG_ENABLE)
        v = if (enabled) v or 0x10 else v and 0x10.inv()
        writeReg(REG_ENABLE, v)
    }

    /**
     * Enable or disable proximity interrupt.
     */
    fun enableProximityInterrupt(enabled: Boolean) {
        var v = readReg(REG_ENABLE)
        v = if (enabled) v or 0x20 else v and 0x20.inv()
        writeReg(REG_ENABLE, v)
    }

    /**
     * Clear the proximity interrupt via address-only write to PICLEAR.
     */
    fun clearProximityInterrupt() {
        transport.write(byteArrayOf(REG_PICLEAR.toByte()))
    }

    /**
     * Clear the ALS/color interrupt via address-only write to CICLEAR.
     */
    fun clearAlsInterrupt() {
        transport.write(byteArrayOf(REG_CICLEAR.toByte()))
    }

    /**
     * Clear all non-gesture interrupts via address-only write to AICLEAR.
     */
    fun clearAllInterrupts() {
        transport.write(byteArrayOf(REG_AICLEAR.toByte()))
    }

    /**
     * Set proximity offset for UP/RIGHT and DOWN/LEFT photodiodes (sign-magnitude).
     */
    fun setProximityOffset(ur: Int, dl: Int) {
        writeReg(REG_POFFSET_UR, encodeOffset(ur))
        writeReg(REG_POFFSET_DL, encodeOffset(dl))
    }

    /**
     * Mask individual photodiodes in proximity detection.
     */
    fun setProximityMask(u: Boolean, d: Boolean, l: Boolean, r: Boolean) {
        var c3 = readReg(REG_CONFIG3) and 0xF0
        if (u) c3 = c3 or 0x08
        if (d) c3 = c3 or 0x04
        if (l) c3 = c3 or 0x02
        if (r) c3 = c3 or 0x01
        writeReg(REG_CONFIG3, c3)
    }

    /**
     * Enable or disable the gesture engine.
     */
    fun enableGesture(enabled: Boolean) {
        var v = readReg(REG_ENABLE)
        if (enabled) {
            v = v or 0x40
            writeReg(REG_ENABLE, v)
            var g4 = readReg(REG_GCONF4)
            g4 = g4 or 0x01
            writeReg(REG_GCONF4, g4)
        } else {
            v = v and 0x40.inv()
            writeReg(REG_ENABLE, v)
            var g4 = readReg(REG_GCONF4)
            g4 = g4 and 0x01.inv()
            writeReg(REG_GCONF4, g4)
        }
    }

    /**
     * Configure gesture engine parameters.
     */
    fun configureGesture(ggain: Int, gldrive: Int, gpulse: Int, gplen: Int, gwtime: Int, gpenth: Int, gexth: Int) {
        writeReg(REG_GPENTH, gpenth and 0xFF)
        writeReg(REG_GEXTH, gexth and 0xFF)
        val g2 = ((ggain and 0x03) shl 5) or ((gldrive and 0x03) shl 3) or (gwtime and 0x07)
        writeReg(REG_GCONF2, g2)
        writeReg(REG_GPULSE, ((gplen and 0x03) shl 6) or (gpulse and 0x3F))
    }

    /**
     * Check if gesture data is available in the FIFO.
     *
     * @return true if GSTATUS.GVALID is set
     */
    fun gestureAvailable(): Boolean = (readReg(REG_GSTATUS) and 0x01) != 0

    /**
     * Read gesture datasets from the FIFO.
     *
     * @return list of [U, D, L, R] arrays, one per dataset
     */
    fun readGestureFifo(): List<IntArray> {
        val level = readReg(REG_GFLVL)
        if (level == 0) return emptyList()
        val result = mutableListOf<IntArray>()
        for (i in 0 until level) {
            val raw = transport.writeRead(byteArrayOf(REG_GFIFO_U.toByte()), 4)
            result.add(intArrayOf(raw[0].toInt() and 0xFF, raw[1].toInt() and 0xFF, raw[2].toInt() and 0xFF, raw[3].toInt() and 0xFF))
        }
        return result
    }

    /**
     * Read the number of datasets in the gesture FIFO.
     */
    fun gestureFifoLevel(): Int = readReg(REG_GFLVL)

    /**
     * Clear the gesture FIFO by setting GFIFO_CLR in GCONF4.
     */
    fun clearGestureFifo() {
        var g4 = readReg(REG_GCONF4)
        g4 = g4 or 0x04
        writeReg(REG_GCONF4, g4)
    }

    /**
     * Enable or disable gesture interrupt.
     */
    fun enableGestureInterrupt(enabled: Boolean) {
        var g4 = readReg(REG_GCONF4)
        g4 = if (enabled) g4 or 0x02 else g4 and 0x02.inv()
        writeReg(REG_GCONF4, g4)
    }

    /**
     * Read the raw STATUS register.
     */
    fun status(): Int = readReg(REG_STATUS)

    /**
     * Check if ALS/color data is valid.
     */
    fun isAlsValid(): Boolean = (readReg(REG_STATUS) and 0x01) != 0

    /**
     * Check if proximity data is valid.
     */
    fun isProximityValid(): Boolean = (readReg(REG_STATUS) and 0x02) != 0

    /**
     * Check if the clear photodiode is saturated.
     */
    fun isAlsSaturated(): Boolean = (readReg(REG_STATUS) and 0x80) != 0

    /**
     * Check if analog saturation occurred during proximity.
     */
    fun isProximitySaturated(): Boolean = (readReg(REG_STATUS) and 0x40) != 0

    /**
     * Read the device ID register.
     *
     * @return ID register value (expect 0xAB)
     */
    fun chipId(): Int = readReg(REG_ID)

    private fun encodeOffset(value: Int): Int {
        return if (value < 0) 0x80 or ((-value) and 0x7F) else value and 0x7F
    }
}
