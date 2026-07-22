package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * PCF8591 — full driver. Extends {@link Pcf8591Minimal} with analog input
 * mode selection, auto-increment, DAC enable/disable, raw and
 * voltage-calibrated ADC reads, and signed differential reads.
 */
public class Pcf8591Full extends Pcf8591Minimal {

    /** 4 single-ended inputs (AIN0–AIN3). */
    public static final int MODE_4_SINGLE_ENDED  = 0;
    /** 3 differential inputs (vs AIN3). */
    public static final int MODE_3_DIFFERENTIAL = 1;
    /** AIN0/1 single-ended, AIN2-AIN3 differential. */
    public static final int MODE_MIXED          = 2;
    /** 2 differential inputs. */
    public static final int MODE_2_DIFFERENTIAL = 3;

    private int  control        = CONTROL_DEFAULT & 0xFF;
    private int  inputMode      = MODE_4_SINGLE_ENDED;
    private boolean dacEnabled  = false;
    private boolean autoIncrement = false;
    private int  lastChannel    = 0;

    /**
     * Construct the full driver.
     *
     * @param transport I²C transport bound to the PCF8591 device address
     */
    public Pcf8591Full(Transport transport) {
        super(transport);
    }

    /**
     * Set the analog input mode, auto-increment, and DAC enable.
     *
     * @param inputMode      analog input programming 0–3 (see MODE_* constants)
     * @param autoIncrement  if true, AI=1 — channel increments after each conversion
     * @param dacEnabled     if true, AOE=1 — AOUT is active; AOUT returns to
     *                       high-impedance when false
     * @throws IOException on I²C error
     */
    public void configure(int inputMode, boolean autoIncrement, boolean dacEnabled) throws IOException {
        int aip = inputMode & 0x03;
        int ai  = autoIncrement ? 0x04 : 0x00;
        int aoe = dacEnabled     ? 0x40 : 0x00;
        this.control = (aip << 4) | aoe | ai | (lastChannel & 0x03);
        this.inputMode     = aip;
        this.autoIncrement = autoIncrement;
        this.dacEnabled    = dacEnabled;
        transport.write(new byte[]{(byte) this.control});
    }

    /**
     * Read a single channel and convert to voltage.
     *
     * @param channel channel number 0–3
     * @param vref    reference voltage in volts
     * @param vagnd   analog ground voltage in volts
     * @return channel voltage in volts
     * @throws IOException on I²C error
     */
    public double readChannelVoltage(int channel, double vref, double vagnd) throws IOException {
        int raw = readChannel(channel);
        return vagnd + raw * (vref - vagnd) / 256.0;
    }

    /**
     * Read all four channels and convert each to voltage.
     *
     * @param vref  reference voltage in volts
     * @param vagnd analog ground voltage in volts
     * @return four channel voltages [ch0, ch1, ch2, ch3]
     * @throws IOException on I²C error
     */
    public double[] readAllVoltage(double vref, double vagnd) throws IOException {
        int[] raws = readAll();
        double vfs = vref - vagnd;
        return new double[]{
            vagnd + raws[0] * vfs / 256.0,
            vagnd + raws[1] * vfs / 256.0,
            vagnd + raws[2] * vfs / 256.0,
            vagnd + raws[3] * vfs / 256.0,
        };
    }

    /**
     * Read a differential channel as a signed value.
     *
     * <p>The chip must be configured in a differential mode (inputMode 1, 2,
     * or 3). The result is interpreted as a signed 8-bit two's complement
     * number.
     *
     * @param channel differential channel index (0–2 for 3-diff mode, 0–1
     *                for 2-diff and mixed modes)
     * @return signed 8-bit value (-128 to 127)
     * @throws IOException on I²C error
     */
    public int readDifferential(int channel) throws IOException {
        int ch = channel & 0x03;
        this.lastChannel = ch;
        int ctrl = this.control | (ch & 0x03);
        transport.write(new byte[]{(byte) ctrl});
        byte[] buf = transport.read(2);
        int raw = buf[1] & 0xFF;
        return raw >= 128 ? raw - 256 : raw;
    }

    /**
     * Enable the DAC and write a raw 8-bit value.
     *
     * <p>Sets the AOE bit so AOUT becomes active, then writes the DAC value
     * in the byte following the control byte.
     *
     * @param value raw 8-bit DAC value (0–255). Output voltage is
     *              V_AGND + value × (V_REF − V_AGND) / 256.
     * @throws IOException on I²C error
     */
    public void setDac(int value) throws IOException {
        int v = Math.max(0, Math.min(255, value));
        int ctrl = (this.control | 0x40) & ~0x04;  // AOE=1, AI=0
        this.control = ctrl;
        this.dacEnabled = true;
        transport.write(new byte[]{(byte) ctrl, (byte) v});
    }

    /**
     * Enable the DAC and set the output as a fraction of (VREF−VAGND).
     *
     * @param voltageFraction output level as a fraction of (VREF−VAGND)
     *                        (0.0 = V_AGND, 1.0 = V_REF). Clamped to [0.0, 1.0].
     * @throws IOException on I²C error
     */
    public void setDacVoltage(double voltageFraction) throws IOException {
        double f = Math.max(0.0, Math.min(1.0, voltageFraction));
        int value = (int) Math.round(f * 255);
        setDac(value);
    }

    /**
     * Disable the DAC output; AOUT returns to high-impedance.
     *
     * @throws IOException on I²C error
     */
    public void disableDac() throws IOException {
        int ctrl = this.control & ~0x40;  // AOE=0
        this.control = ctrl;
        this.dacEnabled = false;
        transport.write(new byte[]{(byte) ctrl});
    }
}
