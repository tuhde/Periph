package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * ADS7828 — 12-bit, 8-channel sampling ADC with internal 2.5 V reference
 * (TI / Burr-Brown) — minimal interface.
 *
 * <p>The chip has no registers: one command byte selects the input mode
 * (single-ended or differential), the channel and the power-down state and
 * starts a conversion; the 12-bit result is read back as two bytes.
 * {@code connection.read(command, 2)} issues exactly the datasheet's
 * write-command / repeated-START / read-two-bytes sequence. The I²C address
 * is 0x48–0x4B (A1/A0 tied to GND or VDD).
 *
 * <p>Reads the eight single-ended inputs (CHn against COM) as raw codes or
 * voltages. Defaults to the internal 2.5 V reference (LSB 610 µV), kept on
 * between conversions. Construction performs no bus traffic; {@link #init()}
 * (or the first conversion) enables the internal reference and waits 1 ms for
 * it to settle.
 */
public class ADS7828Minimal {

    /** Default 7-bit I²C address (A1 = A0 = GND). */
    public static final int DEFAULT_ADDRESS = 0x48;

    protected static final double INTERNAL_VREF = 2.5;
    protected static final int PD_ALL_DOWN = 0;
    protected static final int PD_REF_ON_ADC_ON = 3;
    /** Lone command: SD = 0, C = 0, PD = 10 (reference on, ADC off). */
    protected static final int CMD_REF_ON = 0x08;
    /** Lone command: SD = 0, C = 0, PD = 00 (full power-down). */
    protected static final int CMD_POWER_DOWN = 0x00;
    protected static final long REF_SETTLE_MS = 1;

    protected final RegisterConnection connection;
    protected boolean internal;
    protected boolean refOn;
    protected double vref;
    protected int pd;

    /**
     * Construct the driver with the internal 2.5 V reference. Performs no bus
     * traffic.
     *
     * @param connection configured I²C connection bound to the device (0x48–0x4B)
     */
    public ADS7828Minimal(RegisterConnection connection) {
        this.connection = connection;
        selectReference(null);
    }

    /**
     * Construct the driver with an external reference. Performs no bus
     * traffic.
     *
     * @param connection configured I²C connection bound to the device (0x48–0x4B)
     * @param vref       external reference voltage in volts (0.05 V … VDD); the internal reference stays off
     */
    public ADS7828Minimal(RegisterConnection connection, double vref) {
        this.connection = connection;
        selectReference(vref);
    }

    /** Select the reference; {@code null} = internal 2.5 V. Does not touch the bus. */
    protected void selectReference(Double reference) {
        if (reference == null) {
            internal = true;
            vref = INTERNAL_VREF;
            pd = PD_REF_ON_ADC_ON;
        } else {
            internal = false;
            vref = reference;
            pd = PD_ALL_DOWN;
        }
        refOn = false;
    }

    protected void enableReference() throws IOException {
        connection.write(new byte[]{(byte) CMD_REF_ON});
        try {
            Thread.sleep(REF_SETTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        refOn = true;
    }

    /**
     * Enable the internal reference now (no-op with an external one): sends
     * the lone command 0x08 and waits 1 ms. Optional — the first conversion
     * does this automatically.
     *
     * @throws IOException on bus failure
     */
    public void init() throws IOException {
        if (internal) enableReference();
    }

    protected int convert(boolean singleEnded, int code) throws IOException {
        if (internal && !refOn) enableReference();
        int command = ((singleEnded ? 1 : 0) << 7) | (code << 4) | (pd << 2);
        byte[] data = connection.read(command, 2);
        return ((data[0] & 0x0F) << 8) | (data[1] & 0xFF);
    }

    /**
     * Read a single-ended channel (CHn against COM) as a 12-bit code.
     *
     * @param channel channel number 0–7 (clamped)
     * @return raw code 0–4095
     * @throws IOException on bus failure
     */
    public int readRaw(int channel) throws IOException {
        int ch = Math.max(0, Math.min(7, channel));
        return convert(true, ((ch & 1) << 2) | (ch >> 1));
    }

    /**
     * Read a single-ended channel as a voltage.
     *
     * @param channel channel number 0–7 (clamped)
     * @return input voltage in volts ({@code raw / 4096 × vref})
     * @throws IOException on bus failure
     */
    public double readVoltage(int channel) throws IOException {
        return readRaw(channel) / 4096.0 * vref;
    }

    /**
     * Read all eight single-ended channels, one conversion each.
     *
     * @return voltages of CH0…CH7 in volts
     * @throws IOException on bus failure
     */
    public double[] readAll() throws IOException {
        double[] out = new double[8];
        for (int ch = 0; ch < 8; ch++) out[ch] = readVoltage(ch);
        return out;
    }
}
