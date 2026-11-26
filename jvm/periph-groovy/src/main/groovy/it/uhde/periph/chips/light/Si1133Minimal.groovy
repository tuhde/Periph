package it.uhde.periph.chips.light

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

import java.io.IOException

/**
 * Si1133 UV index and ambient light sensor (Silicon Labs) — minimal interface.
 *
 * <p>One call runs a forced measurement and returns the UV and white-light counts. {@link #init()}
 * waits 25 ms, verifies PART_ID = 0x33, sends RESET_SW and configures channel 0 = UV (ADCMUX 0x18,
 * HW_GAIN 9, 24-bit output), channel 1 = white (ADCMUX 0x0B, HW_GAIN 7, 24-bit), CHAN_LIST = 0x03
 * and IRQ_ENABLE = 0x3F (so IRQ_STATUS flags finished channels).
 *
 * <p>All configuration goes through the indirect parameter table (PARAM_SET / PARAM_QUERY); every
 * command is confirmed by the RESPONSE0 counter. Results are raw ADC counts — the datasheet gives
 * no lux or UV-index formula. INT is open-drain and must be high at power-up. I²C address 0x55
 * (AD pulled up) or 0x52 (AD pulled down). The constructor does not touch the bus.
 */
@CompileStatic
public class Si1133Minimal {

    protected static final int REG_PART_ID    = 0x00
    protected static final int REG_HOSTIN0    = 0x0A
    protected static final int REG_COMMAND    = 0x0B
    protected static final int REG_IRQ_ENABLE = 0x0F
    protected static final int REG_RESPONSE1  = 0x10
    protected static final int REG_RESPONSE0  = 0x11
    protected static final int REG_IRQ_STATUS = 0x12
    protected static final int REG_HOSTOUT0   = 0x13

    protected static final int PART_ID = 0x33

    protected static final int CMD_RESET_CMD_CTR = 0x00
    protected static final int CMD_RESET_SW      = 0x01
    protected static final int CMD_FORCE         = 0x11
    protected static final int CMD_PAUSE         = 0x12
    protected static final int CMD_START         = 0x13
    protected static final int CMD_PARAM_QUERY   = 0x40
    protected static final int CMD_PARAM_SET     = 0x80

    protected static final int PARAM_I2C_ADDR     = 0x00
    protected static final int PARAM_CHAN_LIST    = 0x01
    protected static final int PARAM_ADCCONFIG0   = 0x02
    protected static final int PARAM_ADCSENS0     = 0x03
    protected static final int PARAM_ADCPOST0     = 0x04
    protected static final int PARAM_MEASCONFIG0  = 0x05
    protected static final int PARAM_MEASRATE_H   = 0x1A
    protected static final int PARAM_MEASRATE_L   = 0x1B
    protected static final int PARAM_MEASCOUNT0   = 0x1C
    protected static final int PARAM_THRESHOLD0_H = 0x25
    protected static final int PARAM_BURST        = 0x2B

    /** Command handshake polls (1 ms each). */
    protected static final int CMD_POLLS = 50
    /** Measurement polls (1 ms each); covers HW_GAIN 11 with SW_GAIN 128. */
    protected static final int MEASURE_POLLS = 15000

    /** RESPONSE0: sequencer running. */
    public static final int RESP_RUNNING = 0x80
    /** RESPONSE0: suspended. */
    public static final int RESP_SUSPEND = 0x40
    /** RESPONSE0: sleeping (idle). */
    public static final int RESP_SLEEP   = 0x20
    /** RESPONSE0: command error. */
    public static final int RESP_CMD_ERR = 0x10

    /** ADCMUX: small IR photodiode. */
    public static final int MUX_SMALL_IR    = 0x00
    /** ADCMUX: medium IR photodiode. */
    public static final int MUX_MEDIUM_IR   = 0x01
    /** ADCMUX: large IR photodiode. */
    public static final int MUX_LARGE_IR    = 0x02
    /** ADCMUX: white photodiode. */
    public static final int MUX_WHITE       = 0x0B
    /** ADCMUX: large white photodiode. */
    public static final int MUX_LARGE_WHITE = 0x0D
    /** ADCMUX: UV photodiode. */
    public static final int MUX_UV          = 0x18
    /** ADCMUX: deep UV photodiode. */
    public static final int MUX_UV_DEEP     = 0x19

    /**
     * UV and white counts of one forced measurement.
     *
     * @param uv    signed 24-bit raw count of channel 0 (UV)
     * @param white signed 24-bit raw count of channel 1 (white)
     */
    static record Reading(int uv, int white) {}

    protected final RegisterConnection connection
    protected int chanList
    protected int bits24
    protected int irqEnable

    /**
     * Creates the driver; no bus access.
     *
     * @param connection register-addressed I²C connection bound to 0x55 or 0x52
     */
    public Si1133Minimal(RegisterConnection connection) {
        this.connection = connection
    }

    protected int readByte(int reg) throws IOException {
        return connection.read(reg, 1)[0] & 0xFF
    }

    protected void writeByte(int reg, int value) throws IOException {
        connection.write(reg, [(byte) value] as byte[])
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Verifies the chip, resets it and configures the UV and white channels.
     *
     * @throws IOException if PART_ID is not 0x33, a command fails or on a bus error
     */
    public void init() throws IOException {
        sleepMs(25)
        int part = readByte(REG_PART_ID)
        if (part != PART_ID) throw new IOException(String.format("Unexpected Si1133 PART_ID 0x%02X, expected 0x33", (part) as Object))
        softwareReset()
        applyDefaults()
    }

    protected void softwareReset() throws IOException {
        writeByte(REG_COMMAND, CMD_RESET_SW)
        sleepMs(10)
        chanList = 0
        bits24 = 0
        irqEnable = 0
        for (int i = 0; i < CMD_POLLS; i++) {
            if ((readByte(REG_RESPONSE0) & RESP_SLEEP) != 0) return
            sleepMs(1)
        }
        throw new IOException("Si1133 reset timeout")
    }

    protected void applyDefaults() throws IOException {
        setChannel(0, MUX_UV, 9, 0, 0, false, true, 0)
        setChannel(1, MUX_WHITE, 7, 0, 0, false, true, 0)
        paramSet(PARAM_CHAN_LIST, 0x03)
        chanList = 0x03
        irqEnable = 0x3F
        writeByte(REG_IRQ_ENABLE, irqEnable)
    }

    private int counter() throws IOException {
        int r = readByte(REG_RESPONSE0)
        if ((r & RESP_CMD_ERR) != 0) {
            writeByte(REG_COMMAND, CMD_RESET_CMD_CTR)
            r = readByte(REG_RESPONSE0)
        }
        return r & 0x0F
    }

    private void waitCounter(int before) throws IOException {
        for (int i = 0; i < CMD_POLLS; i++) {
            int r = readByte(REG_RESPONSE0)
            if ((r & RESP_CMD_ERR) != 0) {
                writeByte(REG_COMMAND, CMD_RESET_CMD_CTR)
                throw new IOException(String.format("Si1133 command error 0x%02X", (r & 0x1F) as Object))
            }
            if ((r & 0x0F) != before) return
            sleepMs(1)
        }
        throw new IOException("Si1133 command timeout")
    }

    protected void command(int cmd) throws IOException {
        int before = counter()
        writeByte(REG_COMMAND, cmd)
        waitCounter(before)
    }

    protected void paramSet(int address, int value) throws IOException {
        int before = counter()
        connection.write(REG_HOSTIN0, [(byte) value, (byte) (CMD_PARAM_SET | (address & 0x3F))] as byte[])
        waitCounter(before)
    }

    protected int paramQuery(int address) throws IOException {
        command(CMD_PARAM_QUERY | (address & 0x3F))
        return readByte(REG_RESPONSE1)
    }

    protected void setChannel(int channel, int adcmux, int hwGain, int swGain, int decimRate,
                              boolean highSignal, boolean bits24Out, int postshift) throws IOException {
        int base = 4 * channel
        paramSet(PARAM_ADCCONFIG0 + base, (decimRate << 5) | adcmux)
        paramSet(PARAM_ADCSENS0 + base, (highSignal ? 0x80 : 0) | (swGain << 4) | hwGain)
        paramSet(PARAM_ADCPOST0 + base, (bits24Out ? 0x40 : 0) | (postshift << 3))
        if (bits24Out) bits24 |= 1 << channel
        else bits24 &= ~(1 << channel)
    }

    protected int[] readOutputs() throws IOException {
        int n = 0, count = 0
        for (int ch = 0; ch < 6; ch++) {
            if ((chanList & (1 << ch)) != 0) {
                n += (bits24 & (1 << ch)) != 0 ? 3 : 2
                count++
            }
        }
        int[] values = new int[count]
        if (n == 0) return values
        byte[] d = connection.read(REG_HOSTOUT0, n)
        int pos = 0, k = 0
        for (int ch = 0; ch < 6; ch++) {
            if ((chanList & (1 << ch)) == 0) continue
            if ((bits24 & (1 << ch)) != 0) {
                int u = ((d[pos] & 0xFF) << 16) | ((d[pos + 1] & 0xFF) << 8) | (d[pos + 2] & 0xFF)
                values[k++] = (u << 8) >> 8
                pos += 3
            } else {
                values[k++] = ((d[pos] & 0xFF) << 8) | (d[pos + 1] & 0xFF)
                pos += 2
            }
        }
        return values
    }

    protected int[] measure() throws IOException {
        readByte(REG_IRQ_STATUS)  // clear stale flags
        command(CMD_FORCE)
        int wait = chanList & irqEnable
        if (wait == 0) {
            sleepMs(100)
        } else {
            int seen = 0
            int i = 0
            while (true) {
                seen |= readByte(REG_IRQ_STATUS)
                if ((seen & wait) == wait) break
                if (++i >= MEASURE_POLLS) throw new IOException("Si1133 measurement timeout")
                sleepMs(1)
            }
        }
        return readOutputs()
    }

    /**
     * Runs one forced measurement and returns the UV and white counts of channels 0 and 1.
     *
     * @return signed 24-bit raw counts (near zero or slightly negative in the dark)
     * @throws IOException if channel 0 or 1 is disabled, a command fails or the measurement times out
     */
    public Reading read() throws IOException {
        if ((chanList & 0x03) != 0x03) throw new IOException("Si1133 channels 0 and 1 must be enabled for read()")
        int[] v = measure()
        return new Reading(v[0], v[1])
    }
}
