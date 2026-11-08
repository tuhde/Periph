package it.uhde.periph.chips.power;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * AXP2101 — single-cell Li-ion NVDC power-management IC (X-Powers) — minimal interface.
 *
 * <p>Battery and power-source monitoring. Communicates over I²C at address 0x34. At construction
 * the ADC channels (VBAT, TS, VBUS, VSYS, TDIE) and the fuel gauge are enabled; charger, input
 * limits, rails, power key, interrupts and watchdog keep their power-on (eFuse) configuration.
 */
public class AXP2101Minimal {

    protected static final int REG_STATUS1       = 0x00;
    protected static final int REG_STATUS2       = 0x01;
    protected static final int REG_DATA_BUFFER0  = 0x04;
    protected static final int REG_COMMON_CONFIG = 0x10;
    protected static final int REG_BATFET_CTRL   = 0x12;
    protected static final int REG_DIE_TEMP_CTRL = 0x13;
    protected static final int REG_MIN_VSYS      = 0x14;
    protected static final int REG_VINDPM        = 0x15;
    protected static final int REG_IINLIM        = 0x16;
    protected static final int REG_GAUGE_RESET   = 0x17;
    protected static final int REG_MODULE_ENABLE = 0x18;
    protected static final int REG_WATCHDOG_CTRL = 0x19;
    protected static final int REG_LOWBAT_WARN   = 0x1A;
    protected static final int REG_GPIO1_CTRL    = 0x1B;
    protected static final int REG_PWRON_STATUS  = 0x20;
    protected static final int REG_PWROFF_STATUS = 0x21;
    protected static final int REG_PWROFF_EN     = 0x22;
    protected static final int REG_DCDC_PROT     = 0x23;
    protected static final int REG_VOFF          = 0x24;
    protected static final int REG_PWROK_CTRL    = 0x25;
    protected static final int REG_SLEEP_CTRL    = 0x26;
    protected static final int REG_PWRKEY_LEVEL  = 0x27;
    protected static final int REG_FAST_PWRON0   = 0x28;
    protected static final int REG_ADC_ENABLE    = 0x30;
    protected static final int REG_VBAT_H        = 0x34;
    protected static final int REG_TS_H          = 0x36;
    protected static final int REG_VBUS_H        = 0x38;
    protected static final int REG_VSYS_H        = 0x3A;
    protected static final int REG_TDIE_H        = 0x3C;
    protected static final int REG_IRQ_EN0       = 0x40;
    protected static final int REG_IRQ_STATUS0   = 0x48;
    protected static final int REG_TS_CTRL       = 0x50;
    protected static final int REG_TS_HYS_L2H    = 0x52;
    protected static final int REG_TS_HYS_H2L    = 0x53;
    protected static final int REG_VLTF_CHG      = 0x54;
    protected static final int REG_VHTF_CHG      = 0x55;
    protected static final int REG_VLTF_WORK     = 0x56;
    protected static final int REG_VHTF_WORK     = 0x57;
    protected static final int REG_JEITA_EN      = 0x58;
    protected static final int REG_JEITA_CV      = 0x59;
    protected static final int REG_JEITA_COOL    = 0x5A;
    protected static final int REG_JEITA_WARM    = 0x5B;
    protected static final int REG_IPRECHG       = 0x61;
    protected static final int REG_ICC           = 0x62;
    protected static final int REG_ITERM         = 0x63;
    protected static final int REG_CV            = 0x64;
    protected static final int REG_THERMAL_REG   = 0x65;
    protected static final int REG_CHG_TIMER     = 0x67;
    protected static final int REG_BAT_DETECT    = 0x68;
    protected static final int REG_CHGLED_CTRL   = 0x69;
    protected static final int REG_BTN_BAT_VTERM = 0x6A;
    protected static final int REG_DCDC_EN       = 0x80;
    protected static final int REG_DCDC_PWM      = 0x81;
    protected static final int REG_BAT_PERCENT   = 0xA4;

    /** Charge state 0: trickle charge. */
    public static final int CHARGE_TRICKLE = 0;
    /** Charge state 1: pre-charge. */
    public static final int CHARGE_PRE = 1;
    /** Charge state 2: constant current. */
    public static final int CHARGE_CC = 2;
    /** Charge state 3: constant voltage. */
    public static final int CHARGE_CV = 3;
    /** Charge state 4: charge done. */
    public static final int CHARGE_DONE = 4;
    /** Charge state 5: not charging. */
    public static final int CHARGE_NONE = 5;

    protected final RegisterConnection connection;

    /**
     * Construct the driver and run the initialisation sequence: bus check (read of STATUS1), ADC
     * channels on, fuel gauge on, 20 ms wait for the first conversions.
     *
     * @param connection register connection bound to I²C address 0x34
     * @throws IOException if the bus check fails
     */
    public AXP2101Minimal(RegisterConnection connection) throws IOException {
        this.connection = connection;
        try {
            readReg(REG_STATUS1);
        } catch (IOException e) {
            throw new IOException("AXP2101 not responding at I2C 0x34: " + e.getMessage(), e);
        }
        rmw(REG_ADC_ENABLE, 0x1F, 0x1F);
        rmw(REG_MODULE_ENABLE, 0x08, 0x08);
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Read one register, retrying once on a bus error. */
    protected int readReg(int reg) throws IOException {
        try {
            return connection.read(reg, 1)[0] & 0xFF;
        } catch (IOException e) {
            return connection.read(reg, 1)[0] & 0xFF;
        }
    }

    /** Write one register, retrying once on a bus error. */
    protected void writeReg(int reg, int value) throws IOException {
        byte[] data = new byte[] {(byte) value};
        try {
            connection.write(reg, data);
        } catch (IOException e) {
            connection.write(reg, data);
        }
    }

    /** Replace only the bits in {@code mask}; every other bit is preserved. */
    protected void rmw(int reg, int mask, int value) throws IOException {
        int cur = readReg(reg);
        writeReg(reg, (cur & ~mask) | (value & mask));
    }

    /** Self-clearing command: write the current value with the command bit set. */
    protected void command(int reg, int bit) throws IOException {
        writeReg(reg, readReg(reg) | bit);
    }

    /** Read a 14-bit ADC pair, high byte first. */
    protected int adc14(int reg) throws IOException {
        int hi = readReg(reg);
        int lo = readReg(reg + 1);
        return ((hi & 0x3F) << 8) | lo;
    }

    /**
     * Read the battery voltage.
     *
     * @return battery voltage in V (1 mV / LSB)
     * @throws IOException on bus error
     */
    public double batteryVoltage() throws IOException {
        return adc14(REG_VBAT_H) * 0.001;
    }

    /**
     * Read the fuel-gauge state of charge.
     *
     * @return state of charge, 0–100 %
     * @throws IOException on bus error
     */
    public int batteryPercent() throws IOException {
        return readReg(REG_BAT_PERCENT) & 0x7F;
    }

    /**
     * Read the charger state (STATUS2 bits 2:0).
     *
     * @return one of the {@code CHARGE_*} constants
     * @throws IOException on bus error
     */
    public int chargeState() throws IOException {
        return readReg(REG_STATUS2) & 0x07;
    }

    /**
     * Check whether a good input source is connected (STATUS1.VBUS_GOOD).
     *
     * @return {@code true} when VBUS is good
     * @throws IOException on bus error
     */
    public boolean vbusPresent() throws IOException {
        return (readReg(REG_STATUS1) & 0x20) != 0;
    }
}
