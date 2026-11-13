package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.Connection;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * MCP3422 full interface — extends {@link MCP3422Minimal} with resolution, gain
 * and channel selection, continuous conversion, status reads, scans and general
 * calls. The driver tracks resolution, gain, channel and mode.
 */
public class MCP3422Full extends MCP3422Minimal {

    /**
     * Construct the driver and write the default configuration.
     *
     * @param connection I²C connection bound to the device (0x68–0x6F)
     * @throws IOException on bus error
     */
    public MCP3422Full(Connection connection) throws IOException {
        super(connection);
    }

    private void apply() throws IOException {
        if (continuous) {
            connection.write(new byte[]{(byte) config(false)});
            discard = true;
        }
    }

    /**
     * Select the resolution (and with it the data rate).
     *
     * @param value 12 (240 SPS), 14 (60 SPS), 16 (15 SPS) or 18 (3.75 SPS)
     * @throws IllegalArgumentException for any other value
     * @throws IOException on bus error
     */
    public void setResolution(int value) throws IOException {
        if (value != 12 && value != 14 && value != 16 && value != 18) {
            throw new IllegalArgumentException("bits must be 12, 14, 16 or 18");
        }
        bits = value;
        apply();
    }

    /**
     * The selected resolution.
     *
     * @return 12, 14, 16 or 18
     */
    public int getResolution() {
        return bits;
    }

    /**
     * Select the PGA gain.
     *
     * @param value 1, 2, 4 or 8
     * @throws IllegalArgumentException for any other value
     * @throws IOException on bus error
     */
    public void setGain(int value) throws IOException {
        if (value != 1 && value != 2 && value != 4 && value != 8) {
            throw new IllegalArgumentException("gain must be 1, 2, 4 or 8");
        }
        gain = value;
        apply();
    }

    /**
     * The selected PGA gain.
     *
     * @return 1, 2, 4 or 8
     */
    public int getGain() {
        return gain;
    }

    /**
     * Select the channel used by continuous conversion.
     *
     * @param ch 0 (CH1) or 1 (CH2)
     * @throws IOException on bus error
     */
    public void setChannel(int ch) throws IOException {
        channel = ch < 1 ? 0 : 1;
        apply();
    }

    /**
     * Switch to continuous conversion at the current rate and gain.
     *
     * @param ch channel 0 or 1 to select first, or a negative value to keep the current channel
     * @throws IOException on bus error
     */
    public void startContinuous(int ch) throws IOException {
        if (ch >= 0) channel = ch < 1 ? 0 : 1;
        continuous = true;
        discard = true;
        connection.write(new byte[]{(byte) config(false)});
    }

    /**
     * Switch to continuous conversion keeping the current channel.
     *
     * @throws IOException on bus error
     */
    public void startContinuous() throws IOException {
        startContinuous(-1);
    }

    /**
     * Return to one-shot mode / standby (writes O/C = 0, RDY = 0).
     *
     * @throws IOException on bus error
     */
    public void stopContinuous() throws IOException {
        continuous = false;
        discard = false;
        connection.write(new byte[]{(byte) config(false)});
    }

    /**
     * Report the conversion mode.
     *
     * @return true in continuous mode
     */
    public boolean isContinuous() {
        return continuous;
    }

    /**
     * Wait for a fresh result in continuous mode and return the signed code. The
     * first sample after a configuration change is discarded.
     *
     * @return signed code
     * @throws IllegalStateException if the driver is not in continuous mode
     * @throws IOException on bus error
     * @throws TimeoutException if no result arrives in time
     */
    public int readContinuousRaw() throws IOException, TimeoutException {
        if (!continuous) throw new IllegalStateException("not in continuous mode; call startContinuous()");
        if (discard) {
            waitReady();
            discard = false;
        }
        return waitReady();
    }

    /**
     * Voltage form of {@link #readContinuousRaw()}.
     *
     * @return differential voltage in volts
     * @throws IOException on bus error
     * @throws TimeoutException if no result arrives in time
     */
    public double readContinuous() throws IOException, TimeoutException {
        return readContinuousRaw() * lsb() / gain;
    }

    /**
     * Read the configuration byte (the last byte of a read).
     *
     * @return configuration byte; bit 7 is the live RDY flag
     * @throws IOException on bus error
     */
    public int readConfig() throws IOException {
        return connection.read(nbytes() + 1)[nbytes()] & 0xFF;
    }

    /**
     * Report whether a fresh result is available (RDY = 0).
     *
     * @return true if the output register holds a new result
     * @throws IOException on bus error
     */
    public boolean isReady() throws IOException {
        return (readConfig() & CFG_RDY) == 0;
    }

    /**
     * Run one one-shot conversion per entry, in order.
     *
     * @param channels channels (0 or 1)
     * @return voltages in volts, one per entry
     * @throws IOException on bus error
     * @throws TimeoutException if a conversion does not finish in time
     */
    public double[] readScan(int[] channels) throws IOException, TimeoutException {
        double[] out = new double[channels.length];
        for (int i = 0; i < channels.length; i++) out[i] = readVoltage(channels[i]);
        return out;
    }

    /**
     * Send the general call reset (0x00 0x06): power-on defaults on the chip
     * (continuous, 12 bit, gain x1, CH1); the driver state follows. Needs a
     * Connection that transmits to the general-call address.
     *
     * @throws IOException on bus error
     */
    public void generalCallReset() throws IOException {
        connection.write(new byte[]{0x00, 0x06});
        bits = 12;
        gain = 1;
        channel = 0;
        continuous = true;
        discard = true;
    }

    /**
     * Send the general call conversion (0x00 0x08): a one-shot conversion on every
     * device on the bus; read the result with {@link #readPendingRaw()}.
     *
     * @throws IOException on bus error
     */
    public void generalCallConvert() throws IOException {
        connection.write(new byte[]{0x00, 0x08});
        continuous = false;
    }

    /**
     * Wait for RDY = 0 and return the code of a conversion started elsewhere.
     *
     * @return signed code
     * @throws IOException on bus error
     * @throws TimeoutException if no result arrives in time
     */
    public int readPendingRaw() throws IOException, TimeoutException {
        return waitReady();
    }

    /**
     * Full-scale differential input range at the current gain.
     *
     * @return {@code 2.048 / gain} in volts
     */
    public double fullScale() {
        return VREF / gain;
    }
}
