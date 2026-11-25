package it.uhde.periph.chips.motor

import groovy.transform.CompileStatic
import it.uhde.periph.connection.AvailableConnection
import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.OutputPin

/**
 * Trinamic TMC2202 / TMC2208 / TMC2224 stepper driver over single-wire UART —
 * minimal interface.
 *
 * <p>Brings the driver up in UART mode ({@code pdn_disable}, microstep
 * resolution from {@code CHOPCONF.MRES}), StealthChop, 16 microsteps with
 * 256-step interpolation and 0.5&nbsp;A RMS run current, then lets the
 * internal step generator ({@code VACTUAL}) spin the motor — no STEP pulses
 * required. Datagram framing (sync byte, CRC8, host echo) is handled here; the
 * connection only needs plain {@code write} / {@code read} / {@code writeRead}.
 *
 * <p>Construction talks to the chip (identity check, configuration,
 * {@code IFCNT} acknowledgement) and throws {@link IOException} if it does not
 * answer.
 */
@CompileStatic
class TMC2208Minimal {

    /** {@code DRV_STATUS.otpw}. */
    public static final int FAULT_OVERTEMP_WARNING = 0x01
    /** {@code DRV_STATUS.ot}. */
    public static final int FAULT_OVERTEMP = 0x02
    /** {@code s2ga} / {@code s2gb}. */
    public static final int FAULT_SHORT_GND = 0x04
    /** {@code s2vsa} / {@code s2vsb}. */
    public static final int FAULT_SHORT_SUPPLY = 0x08
    /** {@code ola} / {@code olb} (informative only). */
    public static final int FAULT_OPEN_LOAD = 0x10

    protected static final int REG_GCONF = 0x00
    protected static final int REG_GSTAT = 0x01
    protected static final int REG_IFCNT = 0x02
    protected static final int REG_NODECONF = 0x03
    protected static final int REG_OTP_READ = 0x05
    protected static final int REG_IOIN = 0x06
    protected static final int REG_IHOLD_IRUN = 0x10
    protected static final int REG_TPOWERDOWN = 0x11
    protected static final int REG_TSTEP = 0x12
    protected static final int REG_TPWMTHRS = 0x13
    protected static final int REG_VACTUAL = 0x22
    protected static final int REG_MSCNT = 0x6A
    protected static final int REG_MSCURACT = 0x6B
    protected static final int REG_CHOPCONF = 0x6C
    protected static final int REG_DRV_STATUS = 0x6F
    protected static final int REG_PWMCONF = 0x70
    protected static final int REG_PWM_SCALE = 0x71
    protected static final int REG_PWM_AUTO = 0x72

    protected static final long GCONF_I_SCALE_ANALOG = 1L << 0
    protected static final long GCONF_INTERNAL_RSENSE = 1L << 1
    protected static final long GCONF_EN_SPREADCYCLE = 1L << 2
    protected static final long GCONF_SHAFT = 1L << 3
    protected static final long GCONF_INDEX_OTPW = 1L << 4
    protected static final long GCONF_INDEX_STEP = 1L << 5
    protected static final long GCONF_PDN_DISABLE = 1L << 6
    protected static final long GCONF_MSTEP_REG_SELECT = 1L << 7
    protected static final long GCONF_MULTISTEP_FILT = 1L << 8

    private static final long PWMCONF_DEFAULT = 0xC10D0024L

    protected final Connection connection
    protected final double rSenseOhm
    protected final OutputPin enPin
    protected final boolean echo
    protected final long fclkHz
    private final boolean expectSelA

    protected long gconfShadow
    protected long chopShadow
    protected long pwmShadow
    protected int stepRes = 16
    protected boolean vsenseOn = false
    protected boolean rsenseInternal = false
    protected int toffValue = 3
    protected int runCs
    protected int holdCs
    protected int iholdDelayValue = 6

    /**
     * Construct the driver, probe the chip and apply the default configuration.
     *
     * @param connection UART connection to the chip's {@code PDN_UART} pin
     * @param rSenseOhm  external sense resistor in ohm (0.11 on common boards)
     * @param enPin      optional {@code ENN} output (active low), may be {@code null};
     *                   driven high (driver off) until {@link #enable()}
     * @param echo       true when TX and RX share {@code PDN_UART}
     * @throws IOException if the chip does not answer or fails verification
     */
    TMC2208Minimal(Connection connection, double rSenseOhm, OutputPin enPin, boolean echo) throws IOException {
        this(connection, rSenseOhm, enPin, echo, 12_000_000L, true)
    }

    /** Construct with defaults: 0.11 ohm sense resistor, no ENN pin, echo on. */
    TMC2208Minimal(Connection connection) throws IOException {
        this(connection, 0.11d, (OutputPin) null, true)
    }

    protected TMC2208Minimal(Connection connection, double rSenseOhm, OutputPin enPin, boolean echo,
                             long fclkHz, boolean expectSelA) throws IOException {
        this.connection = connection
        this.rSenseOhm = rSenseOhm
        this.enPin = enPin
        this.echo = echo
        this.fclkHz = fclkHz
        this.expectSelA = expectSelA
        initialise()
    }

    /** CRC-8 (polynomial 0x07, init 0, LSB first) over the first {@code len} bytes. */
    static int crc8(byte[] data, int len) {
        int crc = 0
        for (int i = 0; i < len; i++) {
            int b = data[i] & 0xFF
            for (int k = 0; k < 8; k++) {
                if ((((crc >> 7) ^ b) & 1) != 0) crc = ((crc << 1) ^ 0x07) & 0xFF
                else crc = (crc << 1) & 0xFF
                b >>= 1
            }
        }
        return crc
    }

    private void recover() {
        Connection conn = this.connection
        if (conn instanceof AvailableConnection) {
            AvailableConnection a = (AvailableConnection) conn
            while (a.available() > 0) {
                int pending = a.available()
                conn.read(Math.min(pending, 64))
            }
        }
        Thread.sleep(2L)
    }

    private void writeDatagram(int reg, long value) {
        byte[] dg = new byte[8]
        dg[0] = (byte) 0x05
        dg[1] = (byte) 0x00
        dg[2] = (byte) (reg | 0x80)
        dg[3] = (byte) (value >> 24)
        dg[4] = (byte) (value >> 16)
        dg[5] = (byte) (value >> 8)
        dg[6] = (byte) value
        dg[7] = (byte) crc8(dg, 7)
        connection.write(dg)
        if (echo) {
            byte[] echoed = connection.read(8)
            if (!Arrays.equals(echoed, dg)) throw new IOException('TMC2208: write echo mismatch')
        }
    }

    private long readDatagram(int reg) {
        byte[] req = new byte[4]
        req[0] = (byte) 0x05
        req[1] = (byte) 0x00
        req[2] = (byte) (reg & 0x7F)
        req[3] = (byte) crc8(req, 3)
        byte[] reply
        if (echo) {
            byte[] raw = connection.writeRead(req, 12)
            if (raw.length != 12) throw new IOException('TMC2208: short read')
            if (!Arrays.equals(Arrays.copyOfRange(raw, 0, 4), req)) throw new IOException('TMC2208: read echo mismatch')
            reply = Arrays.copyOfRange(raw, 4, 12)
        } else {
            reply = connection.writeRead(req, 8)
            if (reply.length != 8) throw new IOException('TMC2208: short read')
        }
        if ((reply[0] & 0xFF) != 0x05 || (reply[1] & 0xFF) != 0xFF || (reply[2] & 0xFF) != (reg & 0x7F)) {
            throw new IOException('TMC2208: malformed reply')
        }
        if (crc8(reply, 7) != (reply[7] & 0xFF)) throw new IOException('TMC2208: reply CRC error')
        return (((long) (reply[3] & 0xFF)) << 24) | (((long) (reply[4] & 0xFF)) << 16) |
                (((long) (reply[5] & 0xFF)) << 8) | ((long) (reply[6] & 0xFF))
    }

    /** Write a 32-bit register, retrying once after the idle time the chip needs following an error. */
    protected void writeReg(int reg, long value) {
        long v = value & 0xFFFFFFFFL
        try {
            writeDatagram(reg, v)
        } catch (IOException e) {
            recover()
            writeDatagram(reg, v)
        }
        if (reg == REG_GCONF) gconfShadow = v
        else if (reg == REG_CHOPCONF) chopShadow = v
        else if (reg == REG_PWMCONF) pwmShadow = v
    }

    /** Read a 32-bit register (unsigned in a {@code long}), retrying once after an error. */
    protected long readReg(int reg) {
        try {
            return readDatagram(reg)
        } catch (IOException e) {
            recover()
            return readDatagram(reg)
        }
    }

    /** Read-modify-write a shadowed register: {@code (shadow & ~mask) | (value & mask)}. */
    protected void modify(int reg, long mask, long value) {
        long current = reg == REG_GCONF ? gconfShadow : (reg == REG_CHOPCONF ? chopShadow : pwmShadow)
        writeReg(reg, (current & ~mask) | (value & mask))
    }

    /** Convert A RMS to the 0–31 current scale for the current sense range. */
    protected int currentToCs(double rmsA) {
        if (rsenseInternal) throw new IllegalStateException('set_current is unsupported with internal Rsense')
        double vfs = vsenseOn ? 0.180d : 0.325d
        long cs = Math.round(rmsA * Math.sqrt(2.0d) * (rSenseOhm + 0.030d) * 32.0d / vfs) - 1L
        return (int) Math.max(0L, Math.min(31L, cs))
    }

    protected void writeIholdIrun() {
        writeReg(REG_IHOLD_IRUN, (((long) holdCs) & 0x1FL) | ((((long) runCs) & 0x1FL) << 8) |
                ((((long) iholdDelayValue) & 0xFL) << 16))
    }

    private void initialise() {
        if (enPin != null) enPin.set(true)
        Thread.sleep(10L)
        int baseline = (int) (readReg(REG_IFCNT) & 0xFFL)
        long ioin = readReg(REG_IOIN)
        int version = (int) ((ioin >> 24) & 0xFFL)
        boolean selA = (ioin & (1L << 8)) != 0L
        if (version == 0x00 || version == 0xFF || selA != expectSelA) {
            throw new IOException(String.format('TMC2208 not found (IOIN=0x%08X)', ioin))
        }
        writeReg(REG_GCONF, GCONF_I_SCALE_ANALOG | GCONF_PDN_DISABLE | GCONF_MSTEP_REG_SELECT | GCONF_MULTISTEP_FILT)
        writeReg(REG_GSTAT, 0x07L)
        runCs = currentToCs(0.5d)
        holdCs = currentToCs(0.5d / 3.0d)
        writeIholdIrun()
        writeReg(REG_TPOWERDOWN, 20L)
        long chop = readReg(REG_CHOPCONF)
        chop &= ~(0xFL | (3L << 15) | (7L << 4) | (0xFL << 7) | (0xFL << 24) | (1L << 17))
        chop |= 3L | (2L << 15) | (5L << 4) | (4L << 24) | (1L << 28)
        writeReg(REG_CHOPCONF, chop)
        writeReg(REG_PWMCONF, PWMCONF_DEFAULT)
        int ifcnt = (int) (readReg(REG_IFCNT) & 0xFFL)
        if (ifcnt != ((baseline + 6) & 0xFF)) throw new IOException('TMC2208: IFCNT mismatch (writes not acknowledged)')
    }

    /**
     * Switch the power stage on ({@code ENN} low, or {@code TOFF} restored when no pin is wired).
     *
     * @throws IOException on UART error
     */
    void enable() throws IOException {
        if (enPin != null) enPin.set(false)
        else writeReg(REG_CHOPCONF, (chopShadow & ~0xFL) | (long) toffValue)
    }

    /**
     * Switch the power stage off; the motor freewheels.
     *
     * @throws IOException on UART error
     */
    void disable() throws IOException {
        if (enPin != null) {
            enPin.set(true)
            return
        }
        if ((chopShadow & 0xFL) != 0L) toffValue = (int) (chopShadow & 0xFL)
        writeReg(REG_CHOPCONF, chopShadow & ~0xFL)
    }

    /**
     * Set the run current; the hold current becomes one third of it.
     *
     * @param rmsA run current in A RMS (at most 1.2)
     * @throws IllegalStateException if the internal sense resistors are selected
     */
    void setCurrent(double rmsA) throws IOException {
        runCs = currentToCs(rmsA)
        holdCs = currentToCs(rmsA / 3.0d)
        writeIholdIrun()
    }

    /**
     * Move with the internal step generator ({@code VACTUAL}).
     *
     * @param stepsPerS full steps per second at the configured microstepping;
     *                  sign = direction, 0 stops
     * @throws IOException on UART error
     */
    void run(double stepsPerS) throws IOException {
        long v = Math.round(stepsPerS * stepRes * 16777216.0d / (double) fclkHz)
        v = Math.max(-8388607L, Math.min(8388607L, v))
        writeReg(REG_VACTUAL, v & 0xFFFFFFL)
    }

    /**
     * Stop the internal step generator ({@code VACTUAL} = 0).
     *
     * @throws IOException on UART error
     */
    void stop() throws IOException {
        writeReg(REG_VACTUAL, 0L)
    }

    /**
     * @return true when {@code DRV_STATUS.stst} is set
     * @throws IOException on UART error
     */
    boolean isStandstill() throws IOException {
        return (readReg(REG_DRV_STATUS) & (1L << 31)) != 0L
    }

    /**
     * @return bitmask of {@code FAULT_*} constants, 0 when no fault is present
     * @throws IOException on UART error
     */
    int readFault() throws IOException {
        long s = readReg(REG_DRV_STATUS)
        int f = 0
        if ((s & 0x01L) != 0L) f |= FAULT_OVERTEMP_WARNING
        if ((s & 0x02L) != 0L) f |= FAULT_OVERTEMP
        if ((s & 0x0CL) != 0L) f |= FAULT_SHORT_GND
        if ((s & 0x30L) != 0L) f |= FAULT_SHORT_SUPPLY
        if ((s & 0xC0L) != 0L) f |= FAULT_OPEN_LOAD
        return f
    }
}
