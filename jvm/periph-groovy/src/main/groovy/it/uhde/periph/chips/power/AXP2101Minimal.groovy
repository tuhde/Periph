package it.uhde.periph.chips.power

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * AXP2101 — single-cell Li-ion NVDC power-management IC (X-Powers) — minimal interface.
 *
 * Battery and power-source monitoring. Communicates over I²C at address 0x34. At construction
 * the ADC channels (VBAT, TS, VBUS, VSYS, TDIE) and the fuel gauge are enabled; charger, input
 * limits, rails, power key, interrupts and watchdog keep their power-on (eFuse) configuration.
 */
@CompileStatic
class AXP2101Minimal {

    static final int REG_STATUS1       = 0x00
    static final int REG_STATUS2       = 0x01
    static final int REG_DATA_BUFFER0  = 0x04
    static final int REG_COMMON_CONFIG = 0x10
    static final int REG_BATFET_CTRL   = 0x12
    static final int REG_DIE_TEMP_CTRL = 0x13
    static final int REG_MIN_VSYS      = 0x14
    static final int REG_VINDPM        = 0x15
    static final int REG_IINLIM        = 0x16
    static final int REG_GAUGE_RESET   = 0x17
    static final int REG_MODULE_ENABLE = 0x18
    static final int REG_WATCHDOG_CTRL = 0x19
    static final int REG_LOWBAT_WARN   = 0x1A
    static final int REG_GPIO1_CTRL    = 0x1B
    static final int REG_PWRON_STATUS  = 0x20
    static final int REG_PWROFF_STATUS = 0x21
    static final int REG_PWROFF_EN     = 0x22
    static final int REG_DCDC_PROT     = 0x23
    static final int REG_VOFF          = 0x24
    static final int REG_PWROK_CTRL    = 0x25
    static final int REG_SLEEP_CTRL    = 0x26
    static final int REG_PWRKEY_LEVEL  = 0x27
    static final int REG_FAST_PWRON0   = 0x28
    static final int REG_ADC_ENABLE    = 0x30
    static final int REG_VBAT_H        = 0x34
    static final int REG_TS_H          = 0x36
    static final int REG_VBUS_H        = 0x38
    static final int REG_VSYS_H        = 0x3A
    static final int REG_TDIE_H        = 0x3C
    static final int REG_IRQ_EN0       = 0x40
    static final int REG_IRQ_STATUS0   = 0x48
    static final int REG_TS_CTRL       = 0x50
    static final int REG_TS_HYS_L2H    = 0x52
    static final int REG_TS_HYS_H2L    = 0x53
    static final int REG_VLTF_CHG      = 0x54
    static final int REG_VHTF_CHG      = 0x55
    static final int REG_VLTF_WORK     = 0x56
    static final int REG_VHTF_WORK     = 0x57
    static final int REG_JEITA_EN      = 0x58
    static final int REG_JEITA_CV      = 0x59
    static final int REG_JEITA_COOL    = 0x5A
    static final int REG_JEITA_WARM    = 0x5B
    static final int REG_IPRECHG       = 0x61
    static final int REG_ICC           = 0x62
    static final int REG_ITERM         = 0x63
    static final int REG_CV            = 0x64
    static final int REG_THERMAL_REG   = 0x65
    static final int REG_CHG_TIMER     = 0x67
    static final int REG_BAT_DETECT    = 0x68
    static final int REG_CHGLED_CTRL   = 0x69
    static final int REG_BTN_BAT_VTERM = 0x6A
    static final int REG_DCDC_EN       = 0x80
    static final int REG_DCDC_PWM      = 0x81
    static final int REG_BAT_PERCENT   = 0xA4

    /** Charge state 0: trickle charge. */
    static final int CHARGE_TRICKLE = 0
    /** Charge state 1: pre-charge. */
    static final int CHARGE_PRE = 1
    /** Charge state 2: constant current. */
    static final int CHARGE_CC = 2
    /** Charge state 3: constant voltage. */
    static final int CHARGE_CV = 3
    /** Charge state 4: charge done. */
    static final int CHARGE_DONE = 4
    /** Charge state 5: not charging. */
    static final int CHARGE_NONE = 5

    protected final RegisterConnection connection

    /**
     * Construct the driver and run the initialisation sequence: bus check (read of STATUS1), ADC
     * channels on, fuel gauge on, 20 ms wait for the first conversions.
     *
     * @param connection register connection bound to I²C address 0x34
     * @throws IOException if the bus check fails
     */
    AXP2101Minimal(RegisterConnection connection) {
        this.connection = connection
        try {
            readReg(REG_STATUS1)
        } catch (IOException e) {
            throw new IOException("AXP2101 not responding at I2C 0x34: " + e.getMessage(), e)
        }
        rmw(REG_ADC_ENABLE, 0x1F, 0x1F)
        rmw(REG_MODULE_ENABLE, 0x08, 0x08)
        try {
            Thread.sleep(20)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }
    }

    /** Read one register, retrying once on a bus error. */
    protected int readReg(int reg) {
        try {
            return connection.read(reg, 1)[0] & 0xFF
        } catch (IOException ignored) {
            return connection.read(reg, 1)[0] & 0xFF
        }
    }

    /** Write one register, retrying once on a bus error. */
    protected void writeReg(int reg, int value) {
        byte[] data = [(byte) value] as byte[]
        try {
            connection.write(reg, data)
        } catch (IOException ignored) {
            connection.write(reg, data)
        }
    }

    /** Replace only the bits in {@code mask}; every other bit is preserved. */
    protected void rmw(int reg, int mask, int value) {
        int cur = readReg(reg)
        writeReg(reg, (cur & ~mask) | (value & mask))
    }

    /** Self-clearing command: write the current value with the command bit set. */
    protected void command(int reg, int bit) {
        writeReg(reg, readReg(reg) | bit)
    }

    /** Read a 14-bit ADC pair, high byte first. */
    protected int adc14(int reg) {
        int hi = readReg(reg)
        int lo = readReg(reg + 1)
        return ((hi & 0x3F) << 8) | lo
    }

    /**
     * Read the battery voltage.
     *
     * @return battery voltage in V (1 mV / LSB)
     */
    double batteryVoltage() {
        return adc14(REG_VBAT_H) * 0.001d
    }

    /**
     * Read the fuel-gauge state of charge.
     *
     * @return state of charge, 0–100 %
     */
    int batteryPercent() {
        return readReg(REG_BAT_PERCENT) & 0x7F
    }

    /**
     * Read the charger state (STATUS2 bits 2:0).
     *
     * @return one of the {@code CHARGE_*} constants
     */
    int chargeState() {
        return readReg(REG_STATUS2) & 0x07
    }

    /**
     * Check whether a good input source is connected (STATUS1.VBUS_GOOD).
     *
     * @return {@code true} when VBUS is good
     */
    boolean vbusPresent() {
        return (readReg(REG_STATUS1) & 0x20) != 0
    }
}
