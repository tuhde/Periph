package it.uhde.periph.chips.motor

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.OutputPin

import java.io.IOException

/**
 * Trinamic TMC2130 stepper driver over SPI (Mode 3) — minimal interface.
 *
 * <p>Brings the driver up in SPI mode (internal reference, StealthChop below
 * {@code TPWMTHRS = 500}, SpreadCycle above it, 16 stepRes with 256-step
 * interpolation, 0.5&nbsp;A RMS run current) and moves the motor with STEP/DIR
 * pulses — the chip has no internal step generator. The 40-bit datagram
 * framing and the pipelined two-datagram read are handled here; the
 * connection only needs plain {@code write} / {@code writeRead}.
 *
 * <p>The constructor talks to the chip (identity check, configuration with
 * mirror-verified writes) and throws {@link IOException} if it does not answer.
 */
@CompileStatic
class TMC2130Minimal {

    /** {@code DRV_STATUS.otpw}. */
    public static final int FAULT_OVERTEMP_WARNING = 0x01
    /** {@code DRV_STATUS.ot}. */
    public static final int FAULT_OVERTEMP = 0x02
    /** {@code s2ga} / {@code s2gb}. */
    public static final int FAULT_SHORT_GND = 0x04
    /** {@code ola} / {@code olb} (informative only). */
    public static final int FAULT_OPEN_LOAD = 0x10

    protected static final int REG_GCONF = 0x00
    protected static final int REG_GSTAT = 0x01
    protected static final int REG_IOIN = 0x04
    protected static final int REG_IHOLD_IRUN = 0x10
    protected static final int REG_TPOWERDOWN = 0x11
    protected static final int REG_TSTEP = 0x12
    protected static final int REG_TPWMTHRS = 0x13
    protected static final int REG_TCOOLTHRS = 0x14
    protected static final int REG_THIGH = 0x15
    protected static final int REG_XDIRECT = 0x2D
    protected static final int REG_VDCMIN = 0x33
    protected static final int REG_MSLUT0 = 0x60
    protected static final int REG_MSLUTSEL = 0x68
    protected static final int REG_MSLUTSTART = 0x69
    protected static final int REG_MSCNT = 0x6A
    protected static final int REG_MSCURACT = 0x6B
    protected static final int REG_CHOPCONF = 0x6C
    protected static final int REG_COOLCONF = 0x6D
    protected static final int REG_DCCTRL = 0x6E
    protected static final int REG_DRV_STATUS = 0x6F
    protected static final int REG_PWMCONF = 0x70
    protected static final int REG_PWM_SCALE = 0x71
    protected static final int REG_LOST_STEPS = 0x73

    protected static final long GCONF_I_SCALE_ANALOG = 1L << 0
    protected static final long GCONF_INTERNAL_RSENSE = 1L << 1
    protected static final long GCONF_EN_PWM_MODE = 1L << 2
    protected static final long GCONF_SHAFT = 1L << 4
    protected static final long GCONF_SMALL_HYSTERESIS = 1L << 14
    protected static final long GCONF_STOP_ENABLE = 1L << 15
    protected static final long GCONF_DIRECT_MODE = 1L << 16

    private static final int VERSION = 0x11
    private static final long CHOPCONF_DEFAULT = 0x140100C3L
    private static final long PWMCONF_DEFAULT = 0x000401C8L

    protected final Connection connection
    protected final OutputPin stepPin
    protected final OutputPin dirPin
    protected final OutputPin enPin
    protected final double rSense

    protected long gconf
    protected long chopconf
    protected long pwmconf
    protected long coolconf
    protected int stepRes = 16
    protected boolean vsenseOn = false
    protected boolean autoVsense = true
    protected boolean rsenseInternal = false
    protected int toff = 3
    protected int runCs
    protected int holdCs
    protected int iholdDelay = 6

    /**
     * Construct the driver, probe the chip and apply the default configuration.
     *
     * @param connection SPI connection configured for Mode 3 (CPOL=1, CPHA=1)
     * @param stepPin    {@code STEP} output
     * @param dirPin     {@code DIR} output
     * @param rSenseOhm  external sense resistor in ohm (0.11 on common boards)
     * @param enPin      optional {@code ENN} output (active low), may be {@code null}
     *                   driven high (driver off) until {@link #enable()}
     * @throws IOException if the chip does not answer or fails verification
     */
    TMC2130Minimal(Connection connection, OutputPin stepPin, OutputPin dirPin, double rSenseOhm,
                          OutputPin enPin) throws IOException {
        this.connection = connection
        this.stepPin = stepPin
        this.dirPin = dirPin
        this.rSense = rSenseOhm
        this.enPin = enPin
        initialise()
    }

    /** Construct with defaults: 0.11 ohm sense resistor, no ENN pin. */
    TMC2130Minimal(Connection connection, OutputPin stepPin, OutputPin dirPin) throws IOException {
        this(connection, stepPin, dirPin, 0.11, null)
    }

    /** Write a 32-bit register; with {@code verify} also check the mirror of the write. */
    protected void writeReg(int reg, long value, boolean verify) throws IOException {
        value &= 0xFFFFFFFFL
        connection.write([(byte) (reg | 0x80), (byte) (value >> 24), (byte) (value >> 16),
                (byte) (value >> 8), (byte) value] as byte[])
        if (reg == REG_GCONF) gconf = value
        else if (reg == REG_CHOPCONF) chopconf = value
        else if (reg == REG_PWMCONF) pwmconf = value
        else if (reg == REG_COOLCONF) coolconf = value
        if (verify && secondDatagram() != value) {
            throw new IOException(String.format("TMC2130: write mirror mismatch (reg 0x%02X)", reg))
        }
    }

    /** A harmless GCONF read request whose reply answers the previous datagram. Never GSTAT. */
    private long secondDatagram() throws IOException {
        byte[] r = connection.writeRead([(byte) REG_GCONF] as byte[], 4)
        if (r.length != 4) throw new IOException("TMC2130: short read")
        return ((r[0] & 0xFFL) << 24) | ((r[1] & 0xFFL) << 16) | ((r[2] & 0xFFL) << 8) | (r[3] & 0xFFL)
    }

    /** Pipelined two-datagram read of a 32-bit register (unsigned in a {@code long}). */
    protected long readReg(int reg) throws IOException {
        connection.writeRead([(byte) (reg & 0x7F)] as byte[], 4)
        return secondDatagram()
    }

    /** Read-modify-write a shadowed register: {@code (shadow & ~mask) | (value & mask)}. */
    protected void modify(int reg, long mask, long value) throws IOException {
        long current = reg == REG_GCONF ? gconf : (reg == REG_CHOPCONF ? chopconf : (reg == REG_COOLCONF ? coolconf : pwmconf))
        writeReg(reg, (current & ~mask) | (value & mask), false)
    }

    private int csFor(double rmsA, boolean vs) {
        double vfs = vs ? 0.180d : 0.325d
        long cs = Math.round(rmsA * Math.sqrt(2.0d) * (rSense + 0.020d) * 32.0d / vfs) - 1L
        return (int) Math.max(0L, Math.min(31L, cs))
    }

    /** Convert A RMS to the 0–31 scale; element 1 is the vsenseOn choice (0/1). */
    protected int[] currentToCs(double rmsA, Boolean forced) {
        if (rsenseInternal) throw new IllegalStateException("set_current is unsupported with internal Rsense")
        if (forced != null) return [csFor(rmsA, forced.booleanValue()), forced.booleanValue() ? 1 : 0] as int[]
        if (!autoVsense) return [csFor(rmsA, vsenseOn), vsenseOn ? 1 : 0] as int[]
        int cs = csFor(rmsA, false)
        if (cs < 16) {
            int high = csFor(rmsA, true)
            if (high <= 31) return [high, 1] as int[]
        }
        return [cs, 0] as int[]
    }

    protected void writeIholdIrun(boolean verify) throws IOException {
        writeReg(REG_IHOLD_IRUN, (holdCs & 0x1FL) | ((runCs & 0x1FL) << 8) | ((iholdDelay & 0xFL) << 16), verify)
    }

    private void initialise() throws IOException {
        if (enPin != null) enPin.set(true)
        try {
            Thread.sleep(10)
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
        long ioin = readReg(REG_IOIN)
        if (((ioin >> 24) & 0xFF) != VERSION || (ioin & (1L << 6)) == 0) {
            throw new IOException(String.format("TMC2130 not found (IOIN=0x%08X)", ioin))
        }
        readReg(REG_GSTAT)
        int[] cs = currentToCs(0.5, null)
        runCs = cs[0]
        holdCs = cs[0].intdiv(3)
        vsenseOn = cs[1] == 1
        writeReg(REG_CHOPCONF, CHOPCONF_DEFAULT | (vsenseOn ? (1L << 17) : 0), true)
        writeIholdIrun(true)
        writeReg(REG_TPOWERDOWN, 10, true)
        writeReg(REG_GCONF, GCONF_EN_PWM_MODE, true)
        writeReg(REG_TPWMTHRS, 500, true)
        writeReg(REG_PWMCONF, PWMCONF_DEFAULT, true)
        if (readReg(REG_GCONF) != gconf || readReg(REG_CHOPCONF) != chopconf) {
            throw new IOException("TMC2130: GCONF/CHOPCONF readback mismatch")
        }
    }

    /**
     * Switch the power stage on ({@code ENN} low, or {@code TOFF} restored when no pin is
     * wired) and wait 100&nbsp;ms for StealthChop to settle.
     *
     * @throws IOException on SPI error
     */
    void enable() throws IOException {
        if (enPin != null) enPin.set(false)
        else writeReg(REG_CHOPCONF, (chopconf & ~0xFL) | toff, false)
        sleepMs(100)
    }

    /**
     * Switch the power stage off; the motor freewheels.
     *
     * @throws IOException on SPI error
     */
    void disable() throws IOException {
        if (enPin != null) {
            enPin.set(true)
            return
        }
        if ((chopconf & 0xF) != 0) toff = (int) (chopconf & 0xF)
        writeReg(REG_CHOPCONF, chopconf & ~0xFL, false)
    }

    /**
     * Set the run current; the hold current becomes one third of it. The sense range
     * ({@code CHOPCONF.vsenseOn}) is selected automatically unless {@code setVsense} was called.
     *
     * @param rmsA run current in A RMS (at most 1.4)
     * @throws IOException              on SPI error
     * @throws IllegalArgumentException if {@code rmsA} is above 1.4
     * @throws IllegalStateException    if the internal sense resistors are selected
     */
    void setCurrent(double rmsA) throws IOException {
        if (rmsA > 1.4) throw new IllegalArgumentException("rmsA must be at most 1.4 A")
        int[] cs = currentToCs(rmsA, null)
        runCs = cs[0]
        holdCs = cs[0].intdiv(3)
        boolean vs = cs[1] == 1
        if (vs != vsenseOn) {
            vsenseOn = vs
            modify(REG_CHOPCONF, 1L << 17, vs ? (1L << 17) : 0)
        }
        writeIholdIrun(false)
    }

    /**
     * Move a number of full steps (blocking, software-timed STEP pulses).
     *
     * @param fullSteps     full steps to move; the sign sets the direction
     * @param fullStepsPerS speed in full steps per second (&gt; 0)
     * @throws IOException              on pin or SPI error
     * @throws IllegalArgumentException if {@code fullStepsPerS} is not positive
     */
    void move(int fullSteps, double fullStepsPerS) throws IOException {
        if (!(fullStepsPerS > 0)) throw new IllegalArgumentException("fullStepsPerS must be > 0")
        long pulses = (long) Math.abs(fullSteps) * stepRes
        long halfNs = (long) (1e9 / (2.0 * fullStepsPerS * stepRes))
        dirPin.set(fullSteps >= 0)
        for (long i = 0; i < pulses; i++) {
            stepPin.set(true)
            sleepNanos(halfNs)
            stepPin.set(false)
            sleepNanos(halfNs)
        }
    }

    static void sleepMs(long ms) {
        try {
            Thread.sleep(ms)
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
    }

    static void sleepNanos(long nanos) {
        try {
            Thread.sleep(nanos.intdiv(1_000_000L), (int) (nanos % 1_000_000L))
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * @return true when {@code DRV_STATUS.stst} is set
     * @throws IOException on SPI error
     */
    boolean isStandstill() throws IOException {
        return (readReg(REG_DRV_STATUS) & (1L << 31)) != 0
    }

    /**
     * @return bitmask of {@code FAULT_*} constants, 0 when no fault is present
     * @throws IOException on SPI error
     */
    int readFault() throws IOException {
        long s = readReg(REG_DRV_STATUS)
        int f = 0
        if ((s & (1L << 26)) != 0) f |= FAULT_OVERTEMP_WARNING
        if ((s & (1L << 25)) != 0) f |= FAULT_OVERTEMP
        if ((s & (3L << 27)) != 0) f |= FAULT_SHORT_GND
        if ((s & (3L << 29)) != 0) f |= FAULT_OPEN_LOAD
        return f
    }
}
