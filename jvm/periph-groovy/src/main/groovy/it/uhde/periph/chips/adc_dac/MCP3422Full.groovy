package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * MCP3422 full interface — extends {@link MCP3422Minimal} with resolution, gain
 * and channel selection, continuous conversion, status reads, scans and general
 * calls. The driver tracks resolution, gain, channel and mode.
 */
@CompileStatic
class MCP3422Full extends MCP3422Minimal {

    /**
     * Construct the driver and write the default configuration.
     *
     * @param connection I²C connection bound to the device (0x68–0x6F)
     */
    MCP3422Full(Connection connection) {
        super(connection)
    }

    private void applyConfig() {
        if (continuousV) {
            connection.write([config(false)] as byte[])
            discardV = true
        }
    }

    /**
     * Select the resolution (and with it the data rate).
     *
     * @param value 12 (240 SPS), 14 (60 SPS), 16 (15 SPS) or 18 (3.75 SPS)
     * @throws IllegalArgumentException for any other value
     */
    void setResolution(int value) {
        if (value != 12 && value != 14 && value != 16 && value != 18) {
            throw new IllegalArgumentException('bits must be 12, 14, 16 or 18')
        }
        bitsV = value
        applyConfig()
    }

    /** The selected resolution: 12, 14, 16 or 18. */
    int getResolution() {
        return bitsV
    }

    /**
     * Select the PGA gain.
     *
     * @param value 1, 2, 4 or 8
     * @throws IllegalArgumentException for any other value
     */
    void setGain(int value) {
        if (value != 1 && value != 2 && value != 4 && value != 8) {
            throw new IllegalArgumentException('gain must be 1, 2, 4 or 8')
        }
        gainV = value
        applyConfig()
    }

    /** The selected PGA gain: 1, 2, 4 or 8. */
    int getGain() {
        return gainV
    }

    /**
     * Select the channel used by continuous conversion.
     *
     * @param ch 0 (CH1) or 1 (CH2)
     */
    void setChannel(int ch) {
        channelV = ch < 1 ? 0 : 1
        applyConfig()
    }

    /**
     * Switch to continuous conversion at the current rate and gain.
     *
     * @param ch channel 0 or 1 to select first, or a negative value to keep the current channel
     */
    void startContinuous(int ch) {
        if (ch >= 0) channelV = ch < 1 ? 0 : 1
        continuousV = true
        discardV = true
        connection.write([config(false)] as byte[])
    }

    /** Switch to continuous conversion keeping the current channel. */
    void startContinuous() {
        startContinuous(-1)
    }

    /** Return to one-shot mode / standby (writes O/C = 0, RDY = 0). */
    void stopContinuous() {
        continuousV = false
        discardV = false
        connection.write([config(false)] as byte[])
    }

    /** Report the conversion mode: true in continuous mode. */
    boolean isContinuous() {
        return continuousV
    }

    /**
     * Wait for a fresh result in continuous mode and return the signed code. The
     * first sample after a configuration change is discarded.
     *
     * @throws IllegalStateException if the driver is not in continuous mode
     */
    int readContinuousRaw() {
        if (!continuousV) throw new IllegalStateException('not in continuous mode; call startContinuous()')
        if (discardV) {
            waitReady()
            discardV = false
        }
        return waitReady()
    }

    /** Voltage form of {@link #readContinuousRaw()}, in volts. */
    double readContinuous() {
        return ((double) readContinuousRaw()) * lsb() / gainV
    }

    /** Read the configuration byte (the last byte of a read); bit 7 is the live RDY flag. */
    int readConfig() {
        return connection.read(nbytes() + 1)[nbytes()] & 0xFF
    }

    /** Report whether a fresh result is available (RDY = 0). */
    boolean isReady() {
        return (readConfig() & CFG_RDY) == 0
    }

    /**
     * Run one one-shot conversion per entry, in order.
     *
     * @param channels channels (0 or 1)
     * @return voltages in volts, one per entry
     */
    double[] readScan(int[] channels) {
        double[] out = new double[channels.length]
        for (int i = 0; i < channels.length; i++) out[i] = readVoltage(channels[i])
        return out
    }

    /**
     * Send the general call reset (0x00 0x06): power-on defaults on the chip
     * (continuous, 12 bit, gain x1, CH1); the driver state follows. Needs a
     * Connection that transmits to the general-call address.
     */
    void generalCallReset() {
        connection.write([0x00, 0x06] as byte[])
        bitsV = 12
        gainV = 1
        channelV = 0
        continuousV = true
        discardV = true
    }

    /**
     * Send the general call conversion (0x00 0x08): a one-shot conversion on every
     * device on the bus; read the result with {@link #readPendingRaw()}.
     */
    void generalCallConvert() {
        connection.write([0x00, 0x08] as byte[])
        continuousV = false
    }

    /** Wait for RDY = 0 and return the code of a conversion started elsewhere. */
    int readPendingRaw() {
        return waitReady()
    }

    /** Full-scale differential input range at the current gain: {@code 2.048 / gain} in volts. */
    double fullScale() {
        return VREF / gainV
    }
}
