package it.uhde.periph.chips.light;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * Si1145 proximity / UV index / ambient light sensor (Silicon Labs) — minimal interface.
 *
 * <p>One call runs a forced PS + ALS measurement and returns the proximity count, the UV index
 * and the visible/infrared counts. {@link #init()} waits 25 ms, verifies PART_ID ∈ {0x45, 0x46,
 * 0x47}, writes HW_KEY = 0x17, UCOEF0..3 = 7B 6B 01 00, CHLIST = 0xB1 (UV, ALS IR, ALS VIS, PS1),
 * PSLED12_SELECT = 0x01, PS_LED21 = 0x03 (LED1 at level 3 of 15) and IRQ_ENABLE = ALS_IE | PS1_IE
 * (INT stays undriven until {@link Si1145Full#enableInterrupt(int)}).
 *
 * <p>Configuration is split between registers and the parameter RAM (PARAM_SET / PARAM_QUERY);
 * every command increments the RESPONSE counter. I²C address 0x60 (older silicon: 0x5A). The
 * constructor does not touch the bus.
 */
public class Si1145Minimal {

    protected static final int REG_PART_ID    = 0x00;
    protected static final int REG_SEQ_ID     = 0x02;
    protected static final int REG_INT_CFG    = 0x03;
    protected static final int REG_IRQ_ENABLE = 0x04;
    protected static final int REG_HW_KEY     = 0x07;
    protected static final int REG_MEAS_RATE0 = 0x08;
    protected static final int REG_PS_RATE    = 0x0A;
    protected static final int REG_PS_LED21   = 0x0F;
    protected static final int REG_PS_LED3    = 0x10;
    protected static final int REG_UCOEF0     = 0x13;
    protected static final int REG_PARAM_WR   = 0x17;
    protected static final int REG_COMMAND    = 0x18;
    protected static final int REG_RESPONSE   = 0x20;
    protected static final int REG_IRQ_STATUS = 0x21;
    protected static final int REG_DATA       = 0x22;
    protected static final int REG_PARAM_RD   = 0x2E;

    protected static final int HW_KEY = 0x17;

    protected static final int CMD_NOP         = 0x00;
    protected static final int CMD_RESET       = 0x01;
    protected static final int CMD_BUSADDR     = 0x02;
    protected static final int CMD_PS_FORCE    = 0x05;
    protected static final int CMD_ALS_FORCE   = 0x06;
    protected static final int CMD_PSALS_FORCE = 0x07;
    protected static final int CMD_PSALS_PAUSE = 0x0B;
    protected static final int CMD_PS_AUTO     = 0x0D;
    protected static final int CMD_ALS_AUTO    = 0x0E;
    protected static final int CMD_PSALS_AUTO  = 0x0F;
    protected static final int CMD_GET_CAL     = 0x12;
    protected static final int CMD_PARAM_QUERY = 0x80;
    protected static final int CMD_PARAM_SET   = 0xA0;

    protected static final int PARAM_I2C_ADDR       = 0x00;
    protected static final int PARAM_CHLIST         = 0x01;
    protected static final int PARAM_PSLED12_SELECT = 0x02;
    protected static final int PARAM_PSLED3_SELECT  = 0x03;
    protected static final int PARAM_PS_ENCODING    = 0x05;
    protected static final int PARAM_ALS_ENCODING   = 0x06;
    protected static final int PARAM_PS1_ADCMUX     = 0x07;
    protected static final int PARAM_PS_ADC_COUNTER = 0x0A;
    protected static final int PARAM_PS_ADC_GAIN    = 0x0B;
    protected static final int PARAM_PS_ADC_MISC    = 0x0C;
    protected static final int PARAM_AUX_ADCMUX     = 0x0F;
    protected static final int PARAM_ALS_VIS_ADC_COUNTER = 0x10;
    protected static final int PARAM_ALS_VIS_ADC_GAIN    = 0x11;
    protected static final int PARAM_ALS_VIS_ADC_MISC    = 0x12;
    protected static final int PARAM_ALS_IR_ADC_COUNTER  = 0x1D;
    protected static final int PARAM_ALS_IR_ADC_GAIN     = 0x1E;
    protected static final int PARAM_ALS_IR_ADC_MISC     = 0x1F;

    protected static final int SOURCE_MASK = 0x3D;
    /** Command handshake polls (1 ms each). */
    protected static final int CMD_POLLS = 50;
    /** Measurement polls (1 ms each); a gain-7 ALS measurement takes ~200 ms. */
    protected static final int MEASURE_POLLS = 1000;

    /** CHLIST.EN_PS1. */
    public static final int CH_PS1 = 0x01;
    /** CHLIST.EN_PS2 (Si1146/47). */
    public static final int CH_PS2 = 0x02;
    /** CHLIST.EN_PS3 (Si1147). */
    public static final int CH_PS3 = 0x04;
    /** CHLIST.EN_ALS_VIS. */
    public static final int CH_ALS_VIS = 0x10;
    /** CHLIST.EN_ALS_IR. */
    public static final int CH_ALS_IR = 0x20;
    /** CHLIST.EN_AUX. */
    public static final int CH_AUX = 0x40;
    /** CHLIST.EN_UV. */
    public static final int CH_UV = 0x80;

    /** Interrupt source: ALS/UV measurement complete. */
    public static final int SOURCE_ALS = 0x01;
    /** Interrupt source: PS1 complete. */
    public static final int SOURCE_PS1 = 0x04;
    /** Interrupt source: PS2 complete (Si1146/47). */
    public static final int SOURCE_PS2 = 0x08;
    /** Interrupt source: PS3 complete (Si1147). */
    public static final int SOURCE_PS3 = 0x10;
    /** Interrupt source: command complete. */
    public static final int SOURCE_CMD = 0x20;

    /** Default UV coefficients: AUX_DATA = UV index × 100. */
    public static final int[] UCOEF_DEFAULT = {0x7B, 0x6B, 0x01, 0x00};

    /**
     * One forced PS + ALS measurement.
     *
     * @param proximity raw PS1 count (rises as an object approaches)
     * @param uvIndex   UV index (AUX_DATA / 100)
     * @param visible   raw visible ambient count
     * @param infrared  raw infrared ambient count
     */
    public record Reading(int proximity, double uvIndex, int visible, int infrared) {}

    protected final RegisterConnection connection;
    protected int partId = 0x45;
    protected int seqId;
    protected int chList;
    protected int irqEnable;

    /**
     * Creates the driver; no bus access.
     *
     * @param connection register-addressed I²C connection bound to 0x60
     */
    public Si1145Minimal(RegisterConnection connection) {
        this.connection = connection;
    }

    protected int readByte(int reg) throws IOException {
        return connection.read(reg, 1)[0] & 0xFF;
    }

    protected void writeByte(int reg, int value) throws IOException {
        connection.write(reg, new byte[]{(byte) value});
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Verifies the chip, unlocks it and configures UV, ALS and PS1.
     *
     * @throws IOException if PART_ID is not 0x45/0x46/0x47, a command fails or on a bus error
     */
    public void init() throws IOException {
        sleepMs(25);
        int part = readByte(REG_PART_ID);
        if (part < 0x45 || part > 0x47) {
            throw new IOException(String.format("Unexpected Si1145 PART_ID 0x%02X, expected 0x45/0x46/0x47", part));
        }
        partId = part;
        seqId = readByte(REG_SEQ_ID);
        applyDefaults();
    }

    protected void applyDefaults() throws IOException {
        writeByte(REG_HW_KEY, HW_KEY);
        connection.write(REG_UCOEF0, toBytes(UCOEF_DEFAULT));
        int list = CH_UV | CH_ALS_IR | CH_ALS_VIS | CH_PS1;
        paramSet(PARAM_CHLIST, list);
        chList = list;
        paramSet(PARAM_PSLED12_SELECT, 0x01);
        writeByte(REG_PS_LED21, 0x03);
        irqEnable = SOURCE_ALS | SOURCE_PS1;
        writeByte(REG_IRQ_ENABLE, irqEnable);
    }

    protected static byte[] toBytes(int[] values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) b[i] = (byte) values[i];
        return b;
    }

    protected int counter() throws IOException {
        int r = readByte(REG_RESPONSE);
        if ((r & 0x80) != 0) {
            writeByte(REG_COMMAND, CMD_NOP);
            r = readByte(REG_RESPONSE);
        }
        return r & 0x0F;
    }

    protected void command(int cmd) throws IOException {
        int before = counter();
        writeByte(REG_COMMAND, cmd);
        for (int i = 0; i < CMD_POLLS; i++) {
            int r = readByte(REG_RESPONSE);
            if ((r & 0x80) != 0) {
                writeByte(REG_COMMAND, CMD_NOP);
                throw new IOException(String.format("Si1145 command 0x%02X error 0x%02X", cmd, r));
            }
            if ((r & 0x0F) != before) return;
            sleepMs(1);
        }
        throw new IOException(String.format("Si1145 command 0x%02X timeout", cmd));
    }

    protected void paramSet(int offset, int value) throws IOException {
        writeByte(REG_PARAM_WR, value);
        command(CMD_PARAM_SET | (offset & 0x1F));
    }

    protected int paramQuery(int offset) throws IOException {
        command(CMD_PARAM_QUERY | (offset & 0x1F));
        return readByte(REG_PARAM_RD);
    }

    protected int clearIrq() throws IOException {
        int status = readByte(REG_IRQ_STATUS);
        if (status != 0) writeByte(REG_IRQ_STATUS, status);
        return status;
    }

    protected static int forceCommand(boolean proximity, boolean als) {
        if (proximity && als) return CMD_PSALS_FORCE;
        return proximity ? CMD_PS_FORCE : CMD_ALS_FORCE;
    }

    protected void measure(boolean proximity, boolean als) throws IOException {
        clearIrq();
        counter();
        writeByte(REG_COMMAND, forceCommand(proximity, als));
        int wait = 0;
        if (als && (chList & 0xF0) != 0) wait |= SOURCE_ALS;
        if (proximity) wait |= (chList & 0x07) << 2;
        wait &= irqEnable;
        if (wait == 0) {
            sleepMs(100);
            return;
        }
        int seen = 0;
        for (int i = 0; i < MEASURE_POLLS; i++) {
            seen |= clearIrq();
            if ((seen & wait) == wait) return;
            sleepMs(1);
        }
        throw new IOException("Si1145 measurement timeout");
    }

    protected int[] readData() throws IOException {
        byte[] b = connection.read(REG_DATA, 12);
        int[] v = new int[6];
        for (int i = 0; i < 6; i++) v[i] = (b[2 * i] & 0xFF) | ((b[2 * i + 1] & 0xFF) << 8);
        return v;
    }

    /**
     * Runs one forced PS + ALS measurement (PSALS_FORCE).
     *
     * @return proximity, UV index, visible and infrared values
     * @throws IOException if a command fails, on timeout or on a bus error
     */
    public Reading read() throws IOException {
        measure(true, true);
        int[] v = readData();
        return new Reading(v[2], v[5] / 100.0, v[0], v[1]);
    }
}
