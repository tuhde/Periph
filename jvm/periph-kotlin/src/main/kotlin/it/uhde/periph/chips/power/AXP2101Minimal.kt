package it.uhde.periph.chips.power

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * AXP2101 — single-cell Li-ion NVDC power-management IC (X-Powers) — minimal interface.
 *
 * Battery and power-source monitoring. Communicates over I²C at address 0x34. At construction
 * the ADC channels (VBAT, TS, VBUS, VSYS, TDIE) and the fuel gauge are enabled; charger, input
 * limits, rails, power key, interrupts and watchdog keep their power-on (eFuse) configuration.
 *
 * @param connection register connection bound to I²C address 0x34
 * @throws IOException if the bus check (read of STATUS1) fails
 */
open class AXP2101Minimal(protected val connection: RegisterConnection) {
    companion object {
        const val REG_STATUS1       = 0x00
        const val REG_STATUS2       = 0x01
        const val REG_DATA_BUFFER0  = 0x04
        const val REG_COMMON_CONFIG = 0x10
        const val REG_BATFET_CTRL   = 0x12
        const val REG_DIE_TEMP_CTRL = 0x13
        const val REG_MIN_VSYS      = 0x14
        const val REG_VINDPM        = 0x15
        const val REG_IINLIM        = 0x16
        const val REG_GAUGE_RESET   = 0x17
        const val REG_MODULE_ENABLE = 0x18
        const val REG_WATCHDOG_CTRL = 0x19
        const val REG_LOWBAT_WARN   = 0x1A
        const val REG_GPIO1_CTRL    = 0x1B
        const val REG_PWRON_STATUS  = 0x20
        const val REG_PWROFF_STATUS = 0x21
        const val REG_PWROFF_EN     = 0x22
        const val REG_DCDC_PROT     = 0x23
        const val REG_VOFF          = 0x24
        const val REG_PWROK_CTRL    = 0x25
        const val REG_SLEEP_CTRL    = 0x26
        const val REG_PWRKEY_LEVEL  = 0x27
        const val REG_FAST_PWRON0   = 0x28
        const val REG_ADC_ENABLE    = 0x30
        const val REG_VBAT_H        = 0x34
        const val REG_TS_H          = 0x36
        const val REG_VBUS_H        = 0x38
        const val REG_VSYS_H        = 0x3A
        const val REG_TDIE_H        = 0x3C
        const val REG_IRQ_EN0       = 0x40
        const val REG_IRQ_STATUS0   = 0x48
        const val REG_TS_CTRL       = 0x50
        const val REG_TS_HYS_L2H    = 0x52
        const val REG_TS_HYS_H2L    = 0x53
        const val REG_VLTF_CHG      = 0x54
        const val REG_VHTF_CHG      = 0x55
        const val REG_VLTF_WORK     = 0x56
        const val REG_VHTF_WORK     = 0x57
        const val REG_JEITA_EN      = 0x58
        const val REG_JEITA_CV      = 0x59
        const val REG_JEITA_COOL    = 0x5A
        const val REG_JEITA_WARM    = 0x5B
        const val REG_IPRECHG       = 0x61
        const val REG_ICC           = 0x62
        const val REG_ITERM         = 0x63
        const val REG_CV            = 0x64
        const val REG_THERMAL_REG   = 0x65
        const val REG_CHG_TIMER     = 0x67
        const val REG_BAT_DETECT    = 0x68
        const val REG_CHGLED_CTRL   = 0x69
        const val REG_BTN_BAT_VTERM = 0x6A
        const val REG_DCDC_EN       = 0x80
        const val REG_DCDC_PWM      = 0x81
        const val REG_BAT_PERCENT   = 0xA4

        /** Charge state 0: trickle charge. */
        const val CHARGE_TRICKLE = 0
        /** Charge state 1: pre-charge. */
        const val CHARGE_PRE = 1
        /** Charge state 2: constant current. */
        const val CHARGE_CC = 2
        /** Charge state 3: constant voltage. */
        const val CHARGE_CV = 3
        /** Charge state 4: charge done. */
        const val CHARGE_DONE = 4
        /** Charge state 5: not charging. */
        const val CHARGE_NONE = 5
    }

    init {
        try {
            readReg(REG_STATUS1)
        } catch (e: IOException) {
            throw IOException("AXP2101 not responding at I2C 0x34: ${e.message}", e)
        }
        rmw(REG_ADC_ENABLE, 0x1F, 0x1F)
        rmw(REG_MODULE_ENABLE, 0x08, 0x08)
        try {
            Thread.sleep(20)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Read one register, retrying once on a bus error. */
    protected fun readReg(reg: Int): Int =
        try {
            connection.read(reg, 1)[0].toInt() and 0xFF
        } catch (e: IOException) {
            connection.read(reg, 1)[0].toInt() and 0xFF
        }

    /** Write one register, retrying once on a bus error. */
    protected fun writeReg(reg: Int, value: Int) {
        val data = byteArrayOf(value.toByte())
        try {
            connection.write(reg, data)
        } catch (e: IOException) {
            connection.write(reg, data)
        }
    }

    /** Replace only the bits in [mask]; every other bit is preserved. */
    protected fun rmw(reg: Int, mask: Int, value: Int) {
        val cur = readReg(reg)
        writeReg(reg, (cur and mask.inv()) or (value and mask))
    }

    /** Self-clearing command: write the current value with the command bit set. */
    protected fun command(reg: Int, bit: Int) {
        writeReg(reg, readReg(reg) or bit)
    }

    /** Read a 14-bit ADC pair, high byte first. */
    protected fun adc14(reg: Int): Int {
        val hi = readReg(reg)
        val lo = readReg(reg + 1)
        return ((hi and 0x3F) shl 8) or lo
    }

    /**
     * Read the battery voltage.
     *
     * @return battery voltage in V (1 mV / LSB)
     */
    fun batteryVoltage(): Double = adc14(REG_VBAT_H) * 0.001

    /**
     * Read the fuel-gauge state of charge.
     *
     * @return state of charge, 0–100 %
     */
    fun batteryPercent(): Int = readReg(REG_BAT_PERCENT) and 0x7F

    /**
     * Read the charger state (STATUS2 bits 2:0).
     *
     * @return one of the `CHARGE_*` constants
     */
    fun chargeState(): Int = readReg(REG_STATUS2) and 0x07

    /**
     * Check whether a good input source is connected (STATUS1.VBUS_GOOD).
     *
     * @return `true` when VBUS is good
     */
    fun vbusPresent(): Boolean = (readReg(REG_STATUS1) and 0x20) != 0
}
