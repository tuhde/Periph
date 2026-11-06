package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.Register;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * ADS1014 12-bit I²C ADC with PGA and programmable comparator (Texas Instruments) — minimal
 * interface.
 *
 * <p>Single-input ({@code AIN0} − {@code AIN1}) ΔΣ ADC with internal reference and oscillator.
 * Registers are 16-bit, big-endian, addressed through a persistent Address Pointer. Four
 * selectable addresses ({@code 0x48}–{@code 0x4B}) via the {@code ADDR} strap. The chip has no ID
 * register, so the constructor performs no I²C traffic.
 *
 * <p>Defaults: ±2.048&nbsp;V range (1&nbsp;mV/LSB), 1600&nbsp;SPS, single-shot, comparator
 * disabled. The whole Config word is written for every conversion.
 */
public class ADS1014Minimal {

    /** Default 7-bit I²C address ({@code ADDR} = GND). Valid range 0x48–0x4B. */
    public static final int DEFAULT_ADDRESS = 0x48;

    // Register pointers.
    protected static final int REG_CONVERSION = 0x00;
    protected static final int REG_CONFIG     = 0x01;
    protected static final int REG_LO_THRESH  = 0x02;
    protected static final int REG_HI_THRESH  = 0x03;

    protected static final int CFG_OS          = 0x8000;
    protected static final int CFG_MODE_SINGLE = 0x0100;
    protected static final int QUEUE_DISABLE   = 3;

    protected static final double[] FULL_SCALE = {6.144, 4.096, 2.048, 1.024, 0.512, 0.256};
    protected static final int[] SPS = {128, 250, 490, 920, 1600, 2400, 3300};

    /** Interrupt source: a conversion finished. */
    public static final int SOURCE_CONVERSION_READY = 0x01;
    /** Interrupt source: the result is beyond the comparator thresholds. */
    public static final int SOURCE_COMPARATOR       = 0x02;

    protected final RegisterConnection connection;

    protected int pga = 2;
    protected int dr = 4;
    protected boolean continuous = false;
    protected int compMode = 0;
    protected int compPol = 0;
    protected boolean compLat = false;
    protected int queue = 0;
    protected int source = 0;

    /**
     * Construct the driver. Performs no I²C traffic.
     *
     * @param connection configured I²C connection pointing at the device (0x48–0x4B)
     */
    public ADS1014Minimal(RegisterConnection connection) {
        this.connection = connection;
    }

    /**
     * Read a 16-bit big-endian register.
     *
     * @param reg register pointer
     * @return unsigned register value
     * @throws IOException on bus error
     */
    protected int readReg(int reg) throws IOException {
        byte[] b = connection.read(reg, 2);
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
    }

    /**
     * Write a 16-bit big-endian register.
     *
     * @param reg   register pointer
     * @param value 16-bit value
     * @throws IOException on bus error
     */
    protected void writeReg(int reg, int value) throws IOException {
        connection.write(reg, new byte[]{(byte) (value >> 8), (byte) value});
    }

    /** {@code COMP_QUE} for the currently routed interrupt source. */
    private int compQue() {
        if (source == SOURCE_CONVERSION_READY) return 0;
        if (source == SOURCE_COMPARATOR) return queue;
        return QUEUE_DISABLE;
    }

    /**
     * Build the whole 16-bit Config word from the cached settings.
     *
     * @param os         value of the {@code OS} bit (start a conversion)
     * @param continuous {@code true} for continuous mode ({@code MODE} = 0)
     * @return Config register value
     */
    protected int configWord(boolean os, boolean continuous) {
        int word = os ? CFG_OS : 0;
        word |= pga << 9;
        if (!continuous) word |= CFG_MODE_SINGLE;
        word |= dr << 5;
        word |= compMode << 4;
        word |= compPol << 3;
        word |= (compLat ? 1 : 0) << 2;
        return word | compQue();
    }

    /**
     * Read the CONVERSION register as a signed 12-bit code.
     *
     * @return code, −2048…2047
     * @throws IOException on bus error
     */
    protected int readConversion() throws IOException {
        return Register.toSigned(readReg(REG_CONVERSION), 16) >> 4;
    }

    /**
     * Size of one conversion code at the current gain.
     *
     * @return {@code full_scale / 2048} in volts
     */
    protected double lsbVolts() {
        return FULL_SCALE[pga] / 2048.0;
    }

    /**
     * Run one single-shot conversion of {@code AIN0} − {@code AIN1}.
     *
     * <p>Writes the whole Config word with {@code OS} = 1, polls {@code OS} until the chip is idle
     * again, then reads {@code CONVERSION}.
     *
     * @return 12-bit two's-complement code, −2048…2047
     * @throws IOException on bus error
     */
    public int readRaw() throws IOException {
        writeReg(REG_CONFIG, configWord(true, false));
        continuous = false;
        for (int i = 0; i < 100; i++) {
            if ((readReg(REG_CONFIG) & CFG_OS) != 0) break;
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return readConversion();
    }

    /**
     * Run one single-shot conversion and convert it to volts.
     *
     * @return voltage {@code AIN0} − {@code AIN1} in volts ({@code raw × full-scale / 2048})
     * @throws IOException on bus error
     */
    public double readVoltage() throws IOException {
        return readRaw() * lsbVolts();
    }
}
