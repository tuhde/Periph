package it.uhde.periph.chips.power;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * LM25066 — hot-swap controller / PMBus power monitor (Texas Instruments), minimal driver.
 *
 * <p>Reads the monitored rail: input voltage, output voltage, input current, input power and
 * MOSFET temperature. The constructor performs no bus access and never writes the chip
 * (OPERATION, GATE_MASK and DEVICE_SETUP are left alone). Telemetry is PMBus DIRECT; the current
 * and power coefficients depend on the sense resistor and the CL pin setting.
 *
 * <p>I²C / SMBus addresses: 0x10–0x17, 0x40–0x47, 0x50–0x5A (three-state ADR0/ADR1/ADR2 pins).
 * 16-bit values are little-endian.
 */
public class LM25066Minimal {

    // --- PMBus command codes ---
    protected static final int CMD_OPERATION       = 0x01;
    protected static final int CMD_CLEAR_FAULTS    = 0x03;
    protected static final int CMD_CAPABILITY      = 0x19;
    protected static final int CMD_VOUT_UV_WARN    = 0x43;
    protected static final int CMD_OT_FAULT        = 0x4F;
    protected static final int CMD_OT_WARN         = 0x51;
    protected static final int CMD_VIN_OV_WARN     = 0x57;
    protected static final int CMD_VIN_UV_WARN     = 0x58;
    protected static final int CMD_STATUS_BYTE     = 0x78;
    protected static final int CMD_STATUS_WORD     = 0x79;
    protected static final int CMD_STATUS_VOUT     = 0x7A;
    protected static final int CMD_STATUS_INPUT    = 0x7C;
    protected static final int CMD_STATUS_TEMP     = 0x7D;
    protected static final int CMD_STATUS_CML      = 0x7E;
    protected static final int CMD_STATUS_MFR      = 0x80;
    protected static final int CMD_READ_VIN        = 0x88;
    protected static final int CMD_READ_VOUT       = 0x8B;
    protected static final int CMD_READ_TEMP       = 0x8D;
    protected static final int CMD_MFR_ID          = 0x99;
    protected static final int CMD_MFR_MODEL       = 0x9A;
    protected static final int CMD_MFR_REVISION    = 0x9B;
    protected static final int CMD_READ_VAUX       = 0xD0;
    protected static final int CMD_READ_IIN        = 0xD1;
    protected static final int CMD_READ_PIN        = 0xD2;
    protected static final int CMD_IIN_WARN        = 0xD3;
    protected static final int CMD_PIN_WARN        = 0xD4;
    protected static final int CMD_READ_PIN_PEAK   = 0xD5;
    protected static final int CMD_CLEAR_PIN_PEAK  = 0xD6;
    protected static final int CMD_GATE_MASK       = 0xD7;
    protected static final int CMD_ALERT_MASK      = 0xD8;
    protected static final int CMD_DEVICE_SETUP    = 0xD9;
    protected static final int CMD_BLOCK_READ      = 0xDA;
    protected static final int CMD_SAMPLES_FOR_AVG = 0xDB;
    protected static final int CMD_READ_AVG_VIN    = 0xDC;
    protected static final int CMD_READ_AVG_VOUT   = 0xDD;
    protected static final int CMD_READ_AVG_IIN    = 0xDE;
    protected static final int CMD_READ_AVG_PIN    = 0xDF;
    protected static final int CMD_BLACK_BOX       = 0xE0;
    protected static final int CMD_DIAGNOSTIC      = 0xE1;
    protected static final int CMD_AVG_BLOCK_READ  = 0xE2;

    /** PMBus DIRECT coefficients (m, b, R). */
    protected static final class Coef {
        final double m;
        final double b;
        final int r;

        Coef(double m, double b, int r) {
            this.m = m;
            this.b = b;
            this.r = r;
        }

        double fromY(int y) {
            return (y * Math.pow(10, -r) - b) / m;
        }

        int toY(double x) {
            long y = Math.round((m * x + b) * Math.pow(10, r));
            return (int) Math.max(0, Math.min(0x0FFE, y));
        }
    }

    protected static final Coef VOLT = new Coef(22070, -1800, -2);
    protected static final Coef VAUX = new Coef(3546, -3, 0);
    protected static final Coef TEMP = new Coef(16000, 0, -3);

    protected final RegisterConnection connection;
    protected final double rSenseMohm;
    protected Coef amp;
    protected Coef watt;

    /**
     * Construct the driver. No bus access.
     *
     * @param connection       I²C / SMBus connection bound to the chip's address
     * @param rSenseMohm       sense resistor in milliohms
     * @param currentLimitHigh false = CL pin at GND (25 mV), true = CL pin at VDD (46 mV)
     */
    public LM25066Minimal(RegisterConnection connection, double rSenseMohm, boolean currentLimitHigh) {
        this.connection = connection;
        this.rSenseMohm = rSenseMohm;
        setCurrentLimit(currentLimitHigh);
    }

    /**
     * Construct the driver with a 1 mΩ sense resistor and the CL pin at GND.
     *
     * @param connection I²C / SMBus connection bound to the chip's address
     */
    public LM25066Minimal(RegisterConnection connection) {
        this(connection, 1.0, false);
    }

    /** Select the current / power coefficients for the CL pin setting. */
    protected final void setCurrentLimit(boolean high) {
        if (high) {
            amp = new Coef(6854 * rSenseMohm, -3100, -2);
            watt = new Coef(369 * rSenseMohm, -1900, -2);
        } else {
            amp = new Coef(13661 * rSenseMohm, -5200, -2);
            watt = new Coef(736 * rSenseMohm, -3300, -2);
        }
    }

    /**
     * Read a little-endian word.
     *
     * @param cmd PMBus command code
     * @return unsigned 16-bit value
     * @throws IOException on bus error
     */
    protected int readWord(int cmd) throws IOException {
        byte[] b = connection.read(cmd, 2);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8);
    }

    /**
     * Write a little-endian word.
     *
     * @param cmd   PMBus command code
     * @param value 16-bit value
     * @throws IOException on bus error
     */
    protected void writeWord(int cmd, int value) throws IOException {
        connection.write(cmd, new byte[]{(byte) value, (byte) (value >> 8)});
    }

    /**
     * Read a byte command.
     *
     * @param cmd PMBus command code
     * @return unsigned byte value
     * @throws IOException on bus error
     */
    protected int readByte(int cmd) throws IOException {
        return connection.read(cmd, 1)[0] & 0xFF;
    }

    /**
     * Read the 12-bit telemetry field of a word and convert it.
     *
     * @param cmd  PMBus command code
     * @param coef DIRECT coefficients
     * @return converted value
     * @throws IOException on bus error
     */
    protected double readConverted(int cmd, Coef coef) throws IOException {
        return coef.fromY(readWord(cmd) & 0x0FFF);
    }

    /**
     * Convert a 16-bit temperature word (signed range −256 … +255 °C).
     *
     * @param word raw word
     * @return temperature in °C
     */
    protected static double tempC(int word) {
        return (short) word / 16.0;
    }

    /**
     * Read the input voltage.
     *
     * @return VIN in V
     * @throws IOException on bus error
     */
    public double voltageIn() throws IOException {
        return readConverted(CMD_READ_VIN, VOLT);
    }

    /**
     * Read the output voltage.
     *
     * @return VOUT in V
     * @throws IOException on bus error
     */
    public double voltageOut() throws IOException {
        return readConverted(CMD_READ_VOUT, VOLT);
    }

    /**
     * Read the instantaneous input current.
     *
     * @return input current in A
     * @throws IOException on bus error
     */
    public double current() throws IOException {
        return readConverted(CMD_READ_IIN, amp);
    }

    /**
     * Read the instantaneous input power.
     *
     * @return input power in W
     * @throws IOException on bus error
     */
    public double power() throws IOException {
        return readConverted(CMD_READ_PIN, watt);
    }

    /**
     * Read the external MOSFET temperature.
     *
     * @return temperature in °C
     * @throws IOException on bus error
     */
    public double temperature() throws IOException {
        return tempC(readWord(CMD_READ_TEMP));
    }
}
