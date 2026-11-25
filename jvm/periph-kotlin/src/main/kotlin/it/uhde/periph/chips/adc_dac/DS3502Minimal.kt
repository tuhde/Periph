package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import kotlin.math.roundToInt

/**
 * DS3502 — 7-bit nonvolatile high-voltage I²C digital potentiometer (Maxim) —
 * minimal interface.
 *
 * A single 128-position potentiometer (10 kΩ end to end, ±20 %) whose
 * terminals RH / RL / RW accept up to 15.5 V (V+ = 4.5–15.5 V) while the logic
 * runs from VCC = 2.5–5.5 V. The wiper position lives in the Wiper Register
 * (WR, SRAM); the Initial Value Register (IVR, EEPROM) holds the power-up
 * position (factory default 0x40). The I²C address is 0x28 (A1 = A0 = GND) to
 * 0x2B (A1 = A0 = VCC).
 *
 * Two registers exist: `0x00` is WR (and, depending on the MODE bit of CR,
 * also IVR; reads always return WR) and `0x02` is the Control Register whose
 * bit 7 selects MODE (1 = writes to `0x00` only change WR). Construction runs
 * the initialization sequence (CR = 0x80 with one retry after 3 ms, then reads
 * WR), so routine wiper writes never wear the EEPROM.
 *
 * @param connection I²C register connection bound to the device (0x28–0x2B)
 * @param vHigh voltage applied to RH, in volts
 * @param vLow voltage applied to RL, in volts
 */
open class DS3502Minimal(
    protected val connection: RegisterConnection,
    vHigh: Double = 1.0,
    vLow: Double = 0.0,
) {

    companion object {
        /** 7-bit I²C address with A1 = A0 = GND. */
        const val DEFAULT_ADDRESS = 0x28

        const val REG_WR = 0x00
        const val REG_CR = 0x02
        const val CR_MODE_SRAM = 0x80
        const val CR_MODE_EEPROM = 0x00
        const val MAX_CODE = 127

        internal fun clampCode(code: Int): Int = maxOf(0, minOf(MAX_CODE, code))

        internal fun sleepMs(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    protected var vHigh: Double = vHigh
    protected var vLow: Double = vLow
    protected var code: Int = 0

    init {
        try {
            writeReg(REG_CR, CR_MODE_SRAM)
        } catch (e: IOException) {
            sleepMs(3)   // still in the power-up recall (tD <= 3 ms): retry once
            writeReg(REG_CR, CR_MODE_SRAM)
        }
        readCode()
    }

    protected fun readReg(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    protected fun writeReg(reg: Int, value: Int) {
        connection.write(reg, byteArrayOf(value.toByte()))
    }

    protected fun writeCode(value: Int) {
        val c = clampCode(value)
        writeReg(REG_WR, c)
        code = c
    }

    protected fun readCode(): Int {
        code = readReg(REG_WR) and MAX_CODE
        return code
    }

    /**
     * Set the wiper position (volatile, SRAM only).
     *
     * @param value wiper position 0–127 (clamped); 0 = at RL, 127 = at RH
     */
    fun setWiper(value: Int) = writeCode(value)

    /**
     * Read back the wiper position (WR).
     *
     * @return wiper position 0–127
     */
    fun getWiper(): Int = readCode()

    /**
     * Set the unloaded RW output voltage to the nearest reachable code.
     *
     * @param volts target voltage in volts (clamped between vLow and vHigh)
     */
    fun setVoltage(volts: Double) {
        val span = vHigh - vLow
        if (span == 0.0) {
            writeCode(0)
            return
        }
        writeCode(((volts - vLow) / span * MAX_CODE).roundToInt())
    }

    /**
     * Read the unloaded RW voltage for the current wiper code.
     *
     * @return voltage in volts, `vLow + (vHigh - vLow) * code / 127`
     */
    fun getVoltage(): Double = vLow + (vHigh - vLow) * readCode() / MAX_CODE
}
