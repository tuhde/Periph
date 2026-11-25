package it.uhde.periph.chips.motor

import groovy.transform.CompileStatic

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.OutputPin

import java.io.IOException
import java.util.Arrays

/**
 * Trinamic TMC5160 stepper controller/driver over SPI (Mode 3) or single-wire
 * UART — minimal interface.
 *
 * <p>Brings the chip up with the internal SixPoint ramp generator in
 * positioning mode (256 microsteps, StealthChop below {@code TPWMTHRS = 500},
 * 0.5&nbsp;A RMS run current, 200 full steps/s, 400 full steps/s²) and moves
 * the motor with register writes — no STEP pulses. The transport follows the
 * constructor: the SPI constructor builds pipelined 40-bit datagrams, the UART
 * constructor builds CRC-protected datagrams. Positions are in full steps,
 * velocities in full steps per second, accelerations in full steps per second
 * squared.
 */
@CompileStatic
class TMC5160Minimal {

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
    protected static final int REG_SLAVECONF = 0x03
    protected static final int REG_IOIN = 0x04
    protected static final int REG_OUTPUT = 0x04
    protected static final int REG_X_COMPARE = 0x05
    protected static final int REG_SHORT_CONF = 0x09
    protected static final int REG_DRV_CONF = 0x0A
    protected static final int REG_GLOBALSCALER = 0x0B
    protected static final int REG_OFFSET_READ = 0x0C
    protected static final int REG_IHOLD_IRUN = 0x10
    protected static final int REG_TPOWERDOWN = 0x11
    protected static final int REG_TSTEP = 0x12
    protected static final int REG_TPWMTHRS = 0x13
    protected static final int REG_TCOOLTHRS = 0x14
    protected static final int REG_THIGH = 0x15
    protected static final int REG_RAMPMODE = 0x20
    protected static final int REG_XACTUAL = 0x21
    protected static final int REG_VACTUAL = 0x22
    protected static final int REG_VSTART = 0x23
    protected static final int REG_A1 = 0x24
    protected static final int REG_V1 = 0x25
    protected static final int REG_AMAX = 0x26
    protected static final int REG_VMAX = 0x27
    protected static final int REG_DMAX = 0x28
    protected static final int REG_D1 = 0x2A
    protected static final int REG_VSTOP = 0x2B
    protected static final int REG_TZEROWAIT = 0x2C
    protected static final int REG_XTARGET = 0x2D
    protected static final int REG_VDCMIN = 0x33
    protected static final int REG_SW_MODE = 0x34
    protected static final int REG_RAMP_STAT = 0x35
    protected static final int REG_XLATCH = 0x36
    protected static final int REG_ENCMODE = 0x38
    protected static final int REG_X_ENC = 0x39
    protected static final int REG_ENC_CONST = 0x3A
    protected static final int REG_ENC_STATUS = 0x3B
    protected static final int REG_ENC_LATCH = 0x3C
    protected static final int REG_ENC_DEVIATION = 0x3D
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
    protected static final int REG_PWM_AUTO = 0x72

    protected static final long GCONF_EN_PWM_MODE = 1L << 2
    protected static final long GCONF_SHAFT = 1L << 4
    protected static final long GCONF_SMALL_HYSTERESIS = 1L << 14
    protected static final long GCONF_STOP_ENABLE = 1L << 15
    protected static final long GCONF_DIRECT_MODE = 1L << 16
    protected static final long SW_SG_STOP = 1L << 10
    protected static final long SW_SOFTSTOP = 1L << 11

    private static final int VERSION = 0x30
    private static final double V_FS = 0.325
    private static final long CHOPCONF_DEFAULT = 0x000100C3L
    protected static final long PWMCONF_RESET = 0xC40C001EL

    protected final Connection connection
    protected final double rSense
    protected final OutputPin enPin
    protected final boolean uart
    protected final boolean echo
    protected final long fclkHz
    private final boolean multiNode

    protected int address
    protected final long[] shadow = new long[128]
    protected final boolean[] seen = new boolean[128]
    protected int usc = 256
    protected int toff = 3
    protected int rampModeCur
    protected double speedCur = 200.0
    protected long speedReg
    protected double accelCur = 400.0
    protected int irun
    protected int ihold
    protected int iholdDelay = 6
    protected int sendDelayVal
    private double lastDist
    private double lastSpeed = 200.0
    private double lastAccel = 400.0
    private int writes

    /**
     * Construct the driver on SPI (Mode 3), probe the chip and apply the default configuration.
     *
     * @param connection SPI connection configured for Mode 3
     * @param rSenseOhm  external sense resistor in ohm (0.075 on common boards)
     * @param enPin      optional {@code DRV_ENN} output (active low), may be {@code null}
     *                   driven high (driver off) until {@link #enable()}
     * @throws IOException if the chip does not answer, is not a TMC5160, or fails verification
     */
    public TMC5160Minimal(Connection connection, double rSenseOhm, OutputPin enPin) throws IOException {
        this(connection, rSenseOhm, enPin, false, 0, false, false, 12_000_000L)
    }

    /**
     * Construct the driver on the single-wire UART, probe the chip and apply the default configuration.
     *
     * @param connection UART connection
     * @param rSenseOhm  external sense resistor in ohm
     * @param address    node address 0–253
     * @param enPin      optional {@code DRV_ENN} output, may be {@code null}
     * @param echo       true when TX and RX share the wire
     * @param multiNode  write {@code SLAVECONF.SENDDELAY = 2} first (several chips on one UART)
     * @throws IOException              if the chip does not answer or fails verification
     * @throws IllegalArgumentException if {@code address} is not 0–253
     */
    public TMC5160Minimal(Connection connection, double rSenseOhm, int address, OutputPin enPin, boolean echo,
                          boolean multiNode) throws IOException {
        this(connection, rSenseOhm, enPin, true, address, echo, multiNode, 12_000_000L)
    }

    protected TMC5160Minimal(Connection connection, double rSenseOhm, OutputPin enPin, boolean uart, int address,
                             boolean echo, boolean multiNode, long fclkHz) throws IOException {
        if (address < 0 || address > 253) throw new IllegalArgumentException("address must be 0-253")
        this.connection = connection
        this.rSense = rSenseOhm
        this.enPin = enPin
        this.uart = uart
        this.address = address
        this.echo = echo
        this.multiNode = multiNode
        this.fclkHz = fclkHz
        initialise()
    }

    /** CRC-8 (polynomial 0x07, init 0, LSB first) over the first {@code len} bytes. */
    public static int crc8(byte[] data, int len) {
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

    private void uartWrite(int reg, long value) throws IOException {
        byte[] dg = [0x05, (byte) address, (byte) (reg | 0x80), (byte) (value >> 24), (byte) (value >> 16),
                (byte) (value >> 8), (byte) value, 0] as byte[]
        dg[7] = (byte) crc8(dg, 7)
        connection.write(dg)
        if (echo) {
            byte[] echoed = connection.read(8)
            if (!Arrays.equals(echoed, dg)) throw new IOException("TMC5160: write echo mismatch")
        }
    }

    private long uartRead(int reg) throws IOException {
        byte[] req = [0x05, (byte) address, (byte) (reg & 0x7F), 0] as byte[]
        req[3] = (byte) crc8(req, 3)
        byte[] reply
        if (echo) {
            byte[] raw = connection.writeRead(req, 12)
            if (raw.length != 12) throw new IOException("TMC5160: short read")
            if (!Arrays.equals(Arrays.copyOfRange(raw, 0, 4), req)) throw new IOException("TMC5160: read echo mismatch")
            reply = Arrays.copyOfRange(raw, 4, 12)
        } else {
            reply = connection.writeRead(req, 8)
            if (reply.length != 8) throw new IOException("TMC5160: short read")
        }
        if ((reply[0] & 0xFF) != 0x05 || (reply[1] & 0xFF) != 0xFF || (reply[2] & 0xFF) != (reg & 0x7F)) {
            throw new IOException("TMC5160: malformed reply")
        }
        if (crc8(reply, 7) != (reply[7] & 0xFF)) throw new IOException("TMC5160: reply CRC error")
        return ((reply[3] & 0xFFL) << 24) | ((reply[4] & 0xFFL) << 16) | ((reply[5] & 0xFFL) << 8) | (reply[6] & 0xFFL)
    }

    private long spiSecond() throws IOException {
        byte[] r = connection.writeRead([REG_GCONF] as byte[], 4)
        if (r.length != 4) throw new IOException("TMC5160: short read")
        return ((r[0] & 0xFFL) << 24) | ((r[1] & 0xFFL) << 16) | ((r[2] & 0xFFL) << 8) | (r[3] & 0xFFL)
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }
    }

    /** Write a 32-bit register (UART: retried once after the idle time the chip needs following an error). */
    protected void writeReg(int reg, long value) throws IOException {
        value &= 0xFFFFFFFFL
        if (uart) {
            try {
                uartWrite(reg, value)
            } catch (IOException e) {
                pause(2)
                uartWrite(reg, value)
            }
        } else {
            connection.write([(byte) (reg | 0x80), (byte) (value >> 24), (byte) (value >> 16),
                    (byte) (value >> 8), (byte) value] as byte[])
        }
        writes++
        shadow[reg] = value
        seen[reg] = true
    }

    /** Read a 32-bit register (unsigned in a {@code long}). */
    protected long readReg(int reg) throws IOException {
        if (uart) {
            try {
                return uartRead(reg)
            } catch (IOException e) {
                pause(2)
                return uartRead(reg)
            }
        }
        connection.writeRead([(byte) (reg & 0x7F)] as byte[], 4)
        return spiSecond()
    }

    /** Write and check the acknowledge: {@code IFCNT} + 1 on UART, a follow-up datagram on SPI. */
    protected void writeVerified(int reg, long value) throws IOException {
        if (uart) {
            int before = (int) (readReg(REG_IFCNT) & 0xFF)
            writeReg(reg, value)
            int after = (int) (readReg(REG_IFCNT) & 0xFF)
            if (after != ((before + 1) & 0xFF)) throw new IOException("TMC5160: write not acknowledged")
        } else {
            writeReg(reg, value)
            if (spiSecond() != (value & 0xFFFFFFFFL)) throw new IOException("TMC5160: write mirror mismatch")
        }
    }

    /** Read-modify-write a shadowed register: {@code (shadow & ~mask) | (value & mask)}. */
    protected void modify(int reg, long mask, long value) throws IOException {
        writeReg(reg, (shadow[reg] & ~mask) | (value & mask))
    }

    /** Seed the shadow of a write-only register with its reset value on first use. */
    protected void firstUse(int reg, long reset) {
        if (!seen[reg]) {
            shadow[reg] = reset
            seen[reg] = true
        }
    }

    protected long vReg(double v) {
        return rnd(v * usc * 16777216.0d / fclkHz)
    }

    protected long aReg(double a) {
        return rnd(a * usc * 2199023255552.0 / ((double) fclkHz * fclkHz))
    }

    /** Round half up to a {@code long}. */
    protected static long rnd(double x) {
        return Math.round(x)
    }

    protected static long check(long v, long limit) {
        if (v < 0 || v > limit) throw new IllegalArgumentException("value out of range")
        return v
    }

    /** Maximum RMS current for the sense resistor at full {@code GLOBALSCALER}. */
    protected double iMax() {
        return V_FS / (rSense * Math.sqrt(2.0))
    }

    private void applyCurrent(double rmsA) throws IOException {
        double r = rmsA / iMax()
        if (r > 1.0 || r < 1.0 / 64.0) throw new IllegalArgumentException("current out of range")
        int newIrun
        long scaler
        if (r >= 0.5) {
            newIrun = 31
            scaler = rnd(256.0 * r)
        } else {
            newIrun = (int) Math.max(0, Math.min(31, rnd(64.0 * r) - 1))
            scaler = 128
        }
        writeReg(REG_GLOBALSCALER, scaler >= 256 ? 0 : scaler)
        irun = newIrun
        ihold = newIrun.intdiv(3)
        iholdDelay = 6
        writeIholdIrun()
    }

    protected void writeIholdIrun() throws IOException {
        writeReg(REG_IHOLD_IRUN, (ihold & 0x1FL) | ((irun & 0x1FL) << 8) | ((iholdDelay & 0xFL) << 16))
    }

    private void initialise() throws IOException {
        if (enPin != null) enPin.set(true)
        pause(10)
        int base = uart ? (int) (readReg(REG_IFCNT) & 0xFF) : 0
        long ioin = readReg(REG_IOIN)
        if (((ioin >> 24) & 0xFF) != VERSION) {
            throw new IOException(String.format("TMC5160 not found (IOIN=0x%08X)", ioin))
        }
        if ((ioin & (1L << 6)) != 0) throw new IOException("TMC5160 is strapped for STEP/DIR (SD_MODE = 1)")
        writes = 0
        if (uart && multiNode) {
            sendDelayVal = 2
            writeVerified(REG_SLAVECONF, 2L << 8)
            writes = 0
        }
        readReg(REG_GSTAT)
        writeReg(REG_GSTAT, 0x07)
        writeReg(REG_GLOBALSCALER, 0)
        applyCurrent(Math.min(0.5, iMax()))
        writeReg(REG_TPOWERDOWN, 10)
        writeReg(REG_CHOPCONF, CHOPCONF_DEFAULT)
        writeReg(REG_GCONF, GCONF_EN_PWM_MODE)
        writeReg(REG_TPWMTHRS, 500)
        writeReg(REG_RAMPMODE, 0)
        writeReg(REG_VSTART, 0)
        writeReg(REG_V1, 0)
        writeReg(REG_VSTOP, 10)
        writeReg(REG_TZEROWAIT, 0)
        setSpeed(200.0)
        setAcceleration(400.0)
        if (uart) {
            int after = (int) (readReg(REG_IFCNT) & 0xFF)
            if (after != ((base + writes) & 0xFF)) throw new IOException("TMC5160: IFCNT mismatch (writes not acknowledged)")
        } else if (readReg(REG_GCONF) != shadow[REG_GCONF] || readReg(REG_CHOPCONF) != shadow[REG_CHOPCONF]) {
            throw new IOException("TMC5160: SPI write verification failed")
        }
        rampModeCur = 0
    }

    /** Switch the power stage on ({@code DRV_ENN} low, or {@code TOFF} restored) and wait 150 ms for StealthChop. */
    public void enable() throws IOException {
        if (enPin != null) enPin.set(false)
        else writeReg(REG_CHOPCONF, (shadow[REG_CHOPCONF] & ~0xFL) | toff)
        pause(150)
    }

    /** Switch the power stage off; the motor outputs float. */
    public void disable() throws IOException {
        if (enPin != null) {
            enPin.set(true)
            return
        }
        long chop = shadow[REG_CHOPCONF]
        if ((chop & 0xF) != 0) toff = (int) (chop & 0xF)
        writeReg(REG_CHOPCONF, chop & ~0xFL)
    }

    /**
     * Set the run current in A RMS (hold becomes one third) by programming {@code GLOBALSCALER} and {@code IHOLD_IRUN}.
     *
     * @throws IllegalArgumentException above the sense-resistor maximum or below 1/64 of it
     */
    public void setCurrent(double rmsA) throws IOException {
        applyCurrent(rmsA)
    }

    /** Set {@code VMAX} in full steps per second (applied immediately in positioning mode). */
    public void setSpeed(double fullStepsPerS) throws IOException {
        if (fullStepsPerS <= 0) throw new IllegalArgumentException("speed must be positive")
        long reg = check(vReg(fullStepsPerS), (1L << 23) - 512)
        speedCur = fullStepsPerS
        speedReg = reg
        if (rampModeCur == 0) writeReg(REG_VMAX, reg)
    }

    /** Set a single trapezoid: {@code A1 = AMAX = DMAX = D1} (with {@code V1 = 0}), in full steps per second squared. */
    public void setAcceleration(double fullStepsPerS2) throws IOException {
        if (fullStepsPerS2 <= 0) throw new IllegalArgumentException("acceleration must be positive")
        long reg = check(aReg(fullStepsPerS2), 0xFFFF)
        if (reg < 1) throw new IllegalArgumentException("acceleration too small")
        accelCur = fullStepsPerS2
        for (int r : [REG_A1, REG_AMAX, REG_DMAX, REG_D1] as int[]) writeReg(r, reg)
    }

    /** Start a positioning move to an absolute position in full steps (non-blocking). */
    public void moveTo(double pos) throws IOException {
        long target = rnd(pos * usc)
        lastDist = Math.abs(pos - position())
        lastSpeed = speedCur
        lastAccel = accelCur
        writeReg(REG_VMAX, speedReg)
        writeReg(REG_RAMPMODE, 0)
        rampModeCur = 0
        writeReg(REG_XTARGET, target)
    }

    /** Blocking relative move; {@code fullStepsPerS} {@code <= 0} keeps the configured speed. */
    public void moveSteps(double fullSteps, double fullStepsPerS) throws IOException {
        double saved = speedCur
        if (fullStepsPerS > 0) setSpeed(fullStepsPerS)
        try {
            moveTo(position() + fullSteps)
            waitUntilReached(0)
        } finally {
            if (fullStepsPerS > 0) setSpeed(saved)
        }
    }

    /** Velocity mode: ramp to a velocity (sign = direction); 0 ramps to standstill. */
    public void run(double fullStepsPerS) throws IOException {
        long reg = check(vReg(Math.abs(fullStepsPerS)), (1L << 23) - 512)
        int mode = fullStepsPerS < 0 ? 2 : 1
        writeReg(REG_RAMPMODE, mode)
        rampModeCur = mode
        writeReg(REG_VMAX, reg)
    }

    /** Controlled stop: velocity mode with {@code VMAX = 0}. */
    public void stop() throws IOException {
        writeReg(REG_RAMPMODE, 1)
        rampModeCur = 1
        writeReg(REG_VMAX, 0)
    }

    /** {@code XACTUAL} in full steps. */
    public double position() throws IOException {
        return (int) readReg(REG_XACTUAL) / (double) usc
    }

    /** Define the current position (no motion): writes {@code XACTUAL} and {@code XTARGET}. */
    public void setPosition(double pos) throws IOException {
        long v = rnd(pos * usc) & 0xFFFFFFFFL
        writeReg(REG_XACTUAL, v)
        writeReg(REG_XTARGET, v)
    }

    /** {@code VACTUAL} in full steps per second, signed. */
    public double velocity() throws IOException {
        long raw = readReg(REG_VACTUAL) & 0xFFFFFFL
        if ((raw & 0x800000L) != 0) raw -= 0x1000000L
        return raw * (fclkHz / 2.0 / 8388608.0) / usc
    }

    /** {@code RAMP_STAT.position_reached}. */
    public boolean isPositionReached() throws IOException {
        return (readReg(REG_RAMP_STAT) & (1L << 9)) != 0
    }

    /**
     * Poll {@code RAMP_STAT} every 5 ms until {@code position_reached} (positioning) or {@code vzero} (velocity mode).
     *
     * @param timeoutS timeout in seconds; {@code <= 0} estimates it from the last move
     * @throws IOException on bus error or timeout
     */
    public void waitUntilReached(double timeoutS) throws IOException {
        if (timeoutS <= 0) timeoutS = 2 * lastDist / lastSpeed + 2 * lastSpeed / lastAccel + 1.0
        long bit = rampModeCur == 0 ? 1L << 9 : 1L << 10
        pause(1)
        long deadline = System.nanoTime() + (long) (timeoutS * 1e9)
        while (true) {
            if ((readReg(REG_RAMP_STAT) & bit) != 0) return
            if (System.nanoTime() > deadline) throw new IOException("TMC5160: timed out waiting for the ramp generator")
            pause(5)
        }
    }

    /** True when {@code DRV_STATUS.stst} is set. */
    public boolean isStandstill() throws IOException {
        return (readReg(REG_DRV_STATUS) >> 31) == 1
    }

    /** Bitmask of {@code FAULT_*} constants, 0 when none. */
    public int readFault() throws IOException {
        long s = readReg(REG_DRV_STATUS)
        int f = 0
        if ((s & (1L << 26)) != 0) f |= FAULT_OVERTEMP_WARNING
        if ((s & (1L << 25)) != 0) f |= FAULT_OVERTEMP
        if ((s & (3L << 27)) != 0) f |= FAULT_SHORT_GND
        if ((s & (3L << 12)) != 0) f |= FAULT_SHORT_SUPPLY
        if ((s & (3L << 29)) != 0) f |= FAULT_OPEN_LOAD
        return f
    }
}
