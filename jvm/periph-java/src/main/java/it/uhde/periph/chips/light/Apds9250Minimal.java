package it.uhde.periph.chips.light;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * APDS-9250 — digital RGB, IR and ambient light sensor (Broadcom) — minimal
 * interface.
 *
 * <p>Four simultaneously integrating photodiode channels (red, green, blue,
 * IR; green doubles as the ambient-light channel) with a 13- to 20-bit ADC,
 * five gains and a 25 ms to 2 s measurement rate. Results are unsigned counts
 * in 3-byte registers (LSB first). The I²C address is fixed at 0x52.
 *
 * <p>The constructor reads PART_ID (the upper nibble must be 0xB), writes the
 * defaults (18 bit, 100 ms, gain ×3, interrupt off) and sets LS_EN and
 * CS_MODE. Reading MAIN_STATUS clears its flags, so the driver keeps the
 * interrupt and power-on bits it has seen until
 * {@link Apds9250Full#pollInterrupt()} / {@link Apds9250Full#status()} hands
 * them out.
 */
public class Apds9250Minimal {

    /** Fixed 7-bit I²C address. */
    public static final int DEFAULT_ADDRESS = 0x52;

    /** Wait after enabling the sensor so the support blocks settle, in ms (tests may set it to 0). */
    public static volatile int startWaitMs = 5;

    /** Poll step while waiting for LS_DATA, in ms (tests may set it to 0). */
    public static volatile int pollStepMs = 5;

    protected static final int REG_MAIN_CTRL = 0x00;
    protected static final int REG_LS_MEAS_RATE = 0x04;
    protected static final int REG_LS_GAIN = 0x05;
    protected static final int REG_PART_ID = 0x06;
    protected static final int REG_MAIN_STATUS = 0x07;
    protected static final int REG_DATA_IR = 0x0A;
    protected static final int REG_DATA_GREEN = 0x0D;
    protected static final int REG_DATA_BLUE = 0x10;
    protected static final int REG_DATA_RED = 0x13;
    protected static final int REG_INT_CFG = 0x19;
    protected static final int REG_INT_PERSISTENCE = 0x1A;
    protected static final int REG_THRES_UP = 0x21;
    protected static final int REG_THRES_LOW = 0x24;
    protected static final int REG_THRES_VAR = 0x27;

    protected static final int CTRL_SW_RESET = 0x10;
    protected static final int CTRL_CS_MODE = 0x04;
    protected static final int CTRL_LS_EN = 0x02;
    protected static final int INT_EN = 0x04;
    protected static final int INT_VAR_MODE = 0x08;
    protected static final int INT_SEL_MASK = 0x30;

    /** MAIN_STATUS bit 5: power-up event occurred. */
    public static final int STATUS_POWER_ON = 0x20;
    /** MAIN_STATUS bit 4: interrupt condition fulfilled. */
    public static final int STATUS_INTERRUPT = 0x10;
    /** MAIN_STATUS bit 3: new data available. */
    public static final int STATUS_DATA_READY = 0x08;

    /** The four channel counts of one conversion (unsigned, up to 20 bit). */
    public static final class Counts {
        /** Red count. */
        public final int red;
        /** Green / ambient-light count. */
        public final int green;
        /** Blue count. */
        public final int blue;
        /** Infrared count. */
        public final int ir;

        Counts(int red, int green, int blue, int ir) {
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.ir = ir;
        }
    }

    protected final RegisterConnection connection;
    protected int pending;
    protected double integrationS = 0.1;

    /**
     * Construct the driver and start the sensor.
     *
     * @param connection I²C register connection bound to the device (0x52)
     * @throws IOException           on bus error
     * @throws IllegalStateException if PART_ID does not identify an APDS-9250
     */
    public Apds9250Minimal(RegisterConnection connection) throws IOException {
        this.connection = connection;
        int part = readReg(REG_PART_ID);
        if ((part & 0xF0) != 0xB0) {
            throw new IllegalStateException(String.format("APDS-9250 not found (PART_ID 0x%02X)", part));
        }
        writeReg(REG_LS_MEAS_RATE, 0x22);
        writeReg(REG_LS_GAIN, 0x01);
        writeReg(REG_INT_CFG, 0x10);
        writeReg(REG_MAIN_CTRL, CTRL_LS_EN | CTRL_CS_MODE);
        sleep(startWaitMs);
    }

    protected static void sleep(int ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected int readReg(int reg) throws IOException {
        return connection.read(reg, 1)[0] & 0xFF;
    }

    protected void writeReg(int reg, int value) throws IOException {
        connection.write(reg, new byte[]{(byte) value});
    }

    protected void updateReg(int reg, int setBits, int clearBits) throws IOException {
        int value = readReg(reg);
        writeReg(reg, (value & ~clearBits) | setBits);
    }

    /** Read MAIN_STATUS once and keep the POWER_ON / LS_INT bits until handed out. */
    protected int readStatus() throws IOException {
        int value = readReg(REG_MAIN_STATUS);
        pending |= value & (STATUS_POWER_ON | STATUS_INTERRUPT);
        return value;
    }

    protected static int count(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0x0F) << 16);
    }

    protected Counts readBlock() throws IOException {
        byte[] raw = connection.read(REG_DATA_IR, 12);
        return new Counts(count(raw, 9), count(raw, 3), count(raw, 6), count(raw, 0));
    }

    protected int readChannel(int reg) throws IOException {
        return count(connection.read(reg, 3), 0);
    }

    /**
     * Read all four channels from one conversion. Waits for a new result (at
     * most one integration time plus 50 ms), then block-reads 0x0A–0x15.
     *
     * @return the four unsigned counts
     * @throws IOException on bus error
     */
    public Counts read() throws IOException {
        int limitMs = (int) ((integrationS + 0.05) * 1000.0);
        int step = pollStepMs > 0 ? pollStepMs : 1;
        for (int waited = 0; waited <= limitMs; waited += step) {
            if ((readStatus() & STATUS_DATA_READY) != 0) break;
            sleep(pollStepMs);
        }
        return readBlock();
    }
}
