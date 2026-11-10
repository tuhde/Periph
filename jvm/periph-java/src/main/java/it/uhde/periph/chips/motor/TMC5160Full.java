package it.uhde.periph.chips.motor;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.OutputPin;

import java.io.IOException;

/**
 * Trinamic TMC5160 stepper controller/driver — complete interface.
 *
 * <p>Extends {@link TMC5160Minimal} with raw register access, the full SixPoint
 * ramp, reference switches, chopper / StealthChop tuning, StallGuard2 /
 * CoolStep, DcStep, the ABN encoder and every status read-back. Methods that
 * only exist on the UART transport throw {@link IllegalStateException} on SPI.
 */
public class TMC5160Full extends TMC5160Minimal {

    /** Decoded {@code DRV_STATUS}. */
    public record Status(boolean stst, boolean stealth, boolean fsactive, int csActual, int sgResult,
                         boolean stallguard, boolean otpw, boolean ot, boolean shortGnd, boolean shortSupply,
                         boolean openLoad) {}

    /** Decoded {@code RAMP_STAT}. */
    public record RampStatus(boolean statusStopL, boolean statusStopR, boolean statusLatchL, boolean statusLatchR,
                             boolean eventStopL, boolean eventStopR, boolean eventStopSg, boolean eventPosReached,
                             boolean velocityReached, boolean positionReached, boolean vzero,
                             boolean tZerowaitActive, boolean secondMove, boolean statusSg) {}

    /** Decoded {@code GSTAT}. */
    public record GlobalStatus(boolean reset, boolean drvErr, boolean uvCp) {}

    /** Decoded {@code IOIN}. */
    public record Ioin(long raw, boolean refl, boolean refr, boolean encb, boolean enca, boolean drvEnn,
                       boolean encn, boolean sdMode, boolean swcompIn, int version) {}

    private static final int[] READABLE = {0x00, 0x01, 0x02, 0x04, 0x07, 0x0C, 0x12, 0x20, 0x21, 0x22, 0x2D, 0x34,
            0x35, 0x36, 0x38, 0x39, 0x3B, 0x3C, 0x6A, 0x6B, 0x6C, 0x6F, 0x71, 0x72, 0x73};
    private static final int[] WRITABLE = {0x00, 0x01, 0x03, 0x04, 0x05, 0x09, 0x0A, 0x0B, 0x10, 0x11, 0x13, 0x14,
            0x15, 0x20, 0x21, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2A, 0x2B, 0x2C, 0x2D, 0x33, 0x34, 0x35, 0x38,
            0x39, 0x3A, 0x3B, 0x3D, 0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6C, 0x6D, 0x6E,
            0x70};

    private final InputPin diag0;
    private final InputPin diag1;

    /**
     * Construct on SPI (Mode 3).
     *
     * @param diag0 optional {@code DIAG0} input, may be {@code null}
     * @param diag1 optional {@code DIAG1} input, may be {@code null}
     */
    public TMC5160Full(Connection connection, double rSenseOhm, OutputPin enPin, InputPin diag0, InputPin diag1)
            throws IOException {
        super(connection, rSenseOhm, enPin);
        this.diag0 = diag0;
        this.diag1 = diag1;
    }

    /** Construct on the single-wire UART (the DIAG pins carry the UART and are unavailable). */
    public TMC5160Full(Connection connection, double rSenseOhm, int address, OutputPin enPin, boolean echo,
                       boolean multiNode) throws IOException {
        super(connection, rSenseOhm, address, enPin, echo, multiNode);
        this.diag0 = null;
        this.diag1 = null;
    }

    private static boolean contains(int[] set, int v) {
        for (int x : set) if (x == v) return true;
        return false;
    }

    private static long flag(boolean on, long mask) {
        return on ? mask : 0L;
    }

    private static int signExtend(long v, int bits) {
        v &= (1L << bits) - 1;
        return (int) ((v & (1L << (bits - 1))) != 0 ? v - (1L << bits) : v);
    }

    private void threshold(int reg, long v) throws IOException {
        if (v < 0 || v > 0xFFFFFL) throw new IllegalArgumentException("value out of range");
        writeReg(reg, v);
    }

    private void requireUart() {
        if (!uart) throw new IllegalStateException("this operation needs the UART bus");
    }

    /** Raw 32-bit read of a readable register. */
    public long readRegister(int reg) throws IOException {
        if (!contains(READABLE, reg)) throw new IllegalArgumentException("register not readable");
        return readReg(reg);
    }

    /** Raw 32-bit write; {@code verify} adds the SPI mirror / UART {@code IFCNT} check. */
    public void writeRegister(int reg, long value, boolean verify) throws IOException {
        if (!contains(WRITABLE, reg)) throw new IllegalArgumentException("register not writable");
        if (verify) writeVerified(reg, value);
        else writeReg(reg, value);
    }

    /** Independent hold current in A RMS (not above the run current). */
    public void setHoldCurrent(double rmsA) throws IOException {
        double gs = shadow[REG_GLOBALSCALER] == 0 ? 256.0 : shadow[REG_GLOBALSCALER];
        double full = gs / 256.0 * iMax();
        long h = Math.round(rmsA / full * 32.0) - 1;
        if (rmsA <= 0 || h > irun) throw new IllegalArgumentException("hold current out of range");
        ihold = (int) Math.max(0, h);
        writeIholdIrun();
    }

    /** Raw {@code GLOBALSCALER}: 0 or 32–256 (256 is written as 0). */
    public void setGlobalScaler(int value) throws IOException {
        if (!(value == 0 || (value >= 32 && value <= 256))) throw new IllegalArgumentException("value out of range");
        writeReg(REG_GLOBALSCALER, value == 256 ? 0 : value);
    }

    /** Raw {@code IHOLD_IRUN} fields: {@code irun} 0–31, {@code ihold} 0–31, {@code iholdDelay} 0–15. */
    public void setCurrentScale(int irunValue, int iholdValue, int iholdDelayValue) throws IOException {
        if (irunValue < 0 || irunValue > 31 || iholdValue < 0 || iholdValue > 31 || iholdDelayValue < 0
                || iholdDelayValue > 15) throw new IllegalArgumentException("value out of range");
        irun = irunValue;
        ihold = iholdValue;
        iholdDelay = iholdDelayValue;
        writeIholdIrun();
    }

    /** {@code TPOWERDOWN} (values below 2 break StealthChop auto-tuning). */
    public void setPowerDownDelay(int tpowerdown) throws IOException {
        if (tpowerdown < 0 || tpowerdown > 255) throw new IllegalArgumentException("value out of range");
        writeReg(REG_TPOWERDOWN, tpowerdown);
    }

    /** Program the full SixPoint ramp in physical units (full steps/s and full steps/s²). */
    public void configureRamp(double vstart, double a1, double v1, double amax, double vmax, double dmax, double d1,
                              double vstop) throws IOException {
        long[] raw = {vReg(vstart), aReg(a1), vReg(v1), aReg(amax), vReg(vmax), aReg(dmax), aReg(d1), vReg(vstop)};
        for (long v : raw) if (v < 0 || v > 0xFFFFFFFFL) throw new IllegalArgumentException("value out of range");
        setRampRaw(raw[0], raw[1], raw[2], raw[3], raw[4], raw[5], raw[6], raw[7]);
        speed = vmax;
        accel = amax;
    }

    /** Program the full SixPoint ramp in raw register units. */
    public void setRampRaw(long vstart, long a1, long v1, long amax, long vmax, long dmax, long d1, long vstop)
            throws IOException {
        if (vstart >= (1L << 18) || a1 > 0xFFFF || v1 >= (1L << 20) || amax > 0xFFFF || vmax > (1L << 23) - 512
                || dmax > 0xFFFF || d1 > 0xFFFF || vstop >= (1L << 18) || d1 < 1 || vstop < 1 || vstop < vstart) {
            throw new IllegalArgumentException("ramp value out of range");
        }
        writeReg(REG_VSTART, vstart);
        writeReg(REG_A1, a1);
        writeReg(REG_V1, v1);
        writeReg(REG_AMAX, amax);
        writeReg(REG_VMAX, vmax);
        writeReg(REG_DMAX, dmax);
        writeReg(REG_D1, d1);
        writeReg(REG_VSTOP, vstop);
        speedReg = vmax;
    }

    /** {@code TZEROWAIT} in seconds (0 to about 2.8 s at 12 MHz). */
    public void setZeroWait(double seconds) throws IOException {
        writeReg(REG_TZEROWAIT, check(Math.round(seconds * fclkHz / 512.0), 0xFFFF));
    }

    /** {@code RAMPMODE} 0–3 (3 = hold). */
    public void setRampMode(int mode) throws IOException {
        if (mode < 0 || mode > 3) throw new IllegalArgumentException("mode must be 0-3");
        writeReg(REG_RAMPMODE, mode);
        rampmode = mode;
    }

    /** Hold mode: keep the present velocity until a stop event. */
    public void hold() throws IOException {
        setRampMode(3);
    }

    /** Write {@code XTARGET} in microsteps (starts positioning in mode 0). */
    public void setTargetRaw(int xtarget) throws IOException {
        writeReg(REG_XTARGET, xtarget & 0xFFFFFFFFL);
    }

    /** Decoded {@code RAMP_STAT} (one read). */
    public RampStatus readRampStatus() throws IOException {
        long s = readReg(REG_RAMP_STAT);
        return new RampStatus(bit(s, 0), bit(s, 1), bit(s, 2), bit(s, 3), bit(s, 4), bit(s, 5), bit(s, 6), bit(s, 7),
                bit(s, 8), bit(s, 9), bit(s, 10), bit(s, 11), bit(s, 12), bit(s, 13));
    }

    private static boolean bit(long v, int i) {
        return (v & (1L << i)) != 0;
    }

    /** Write 1 to the clear-on-write bits of {@code RAMP_STAT}. */
    public void clearRampEvents() throws IOException {
        writeReg(REG_RAMP_STAT, (1L << 2) | (1L << 3) | (1L << 6) | (1L << 7) | (1L << 12));
    }

    /** {@code X_COMPARE} in full steps ({@code DIAG1} goes high while {@code XACTUAL} equals it). */
    public void setPositionCompare(double position) throws IOException {
        writeReg(REG_X_COMPARE, Math.round(position * usc) & 0xFFFFFFFFL);
    }

    /** {@code SW_MODE} bits 0–4 and 11 (latch and {@code sg_stop} bits are kept). */
    public void configureSwitches(boolean stopLeft, boolean stopRight, boolean leftActiveLow, boolean rightActiveLow,
                                  boolean swap, boolean softStop) throws IOException {
        if (softStop && (shadow[REG_SW_MODE] & SW_SG_STOP) != 0) throw new IllegalArgumentException("soft stop conflicts with sg_stop");
        long v = flag(stopLeft, 1) | flag(stopRight, 2) | flag(leftActiveLow, 4) | flag(rightActiveLow, 8)
                | flag(swap, 16) | flag(softStop, SW_SOFTSTOP);
        modify(REG_SW_MODE, 0x1FL | SW_SOFTSTOP, v);
    }

    /** {@code SW_MODE} latch bits 5–9. */
    public void configureLatch(boolean leftActive, boolean leftInactive, boolean rightActive, boolean rightInactive,
                               boolean latchEncoder) throws IOException {
        long v = flag(leftActive, 1L << 5) | flag(leftInactive, 1L << 6) | flag(rightActive, 1L << 7)
                | flag(rightInactive, 1L << 8) | flag(latchEncoder, 1L << 9);
        modify(REG_SW_MODE, 0x1FL << 5, v);
    }

    /** {@code XLATCH} in full steps. */
    public double readLatchedPosition() throws IOException {
        return (int) readReg(REG_XLATCH) / (double) usc;
    }

    /** {@code {status_stop_l, status_stop_r}}. */
    public boolean[] readSwitches() throws IOException {
        long s = readReg(REG_RAMP_STAT);
        return new boolean[] {bit(s, 0), bit(s, 1)};
    }

    /** Microstep resolution 1, 2, 4, …, 256 (the ramp registers are not rewritten). */
    public void setMicrosteps(int n) throws IOException {
        if (n < 1 || n > 256 || (n & (n - 1)) != 0) throw new IllegalArgumentException("microsteps must be a power of two 1-256");
        long mres = 8 - Integer.numberOfTrailingZeros(n);
        modify(REG_CHOPCONF, 0xFL << 24, mres << 24);
        usc = n;
    }

    /** {@code CHOPCONF.intpol}. */
    public void setInterpolation(boolean enabled) throws IOException {
        modify(REG_CHOPCONF, 1L << 28, flag(enabled, 1L << 28));
    }

    /** SpreadCycle only ({@code true}) or StealthChop ({@code false}); switch at standstill. */
    public void setChopperMode(boolean spreadcycle) throws IOException {
        modify(REG_GCONF, GCONF_EN_PWM_MODE, flag(!spreadcycle, GCONF_EN_PWM_MODE));
    }

    /** {@code TPWMTHRS} (20 bit); 0 disables. */
    public void setStealthchopThreshold(long v) throws IOException {
        threshold(REG_TPWMTHRS, v);
    }

    /** {@code THIGH} (20 bit). */
    public void setHighVelocityThreshold(long v) throws IOException {
        threshold(REG_THIGH, v);
    }

    /** {@code TCOOLTHRS} (20 bit); 0 disables. */
    public void setCoolstepThreshold(long v) throws IOException {
        threshold(REG_TCOOLTHRS, v);
    }

    /** SpreadCycle ({@code chm = 0}) fields: toff 0–15, tbl 0–3, hstrt 0–7, raw hend 0–15, tpfd 0–15. */
    public void configureChopper(int toffValue, int tbl, int hstrt, int hend, int tpfd) throws IOException {
        if (toffValue < 0 || toffValue > 15 || tbl < 0 || tbl > 3 || hstrt < 0 || hstrt > 7 || hend < 0 || hend > 15
                || tpfd < 0 || tpfd > 15) throw new IllegalArgumentException("value out of range");
        if (toffValue != 0) toff = toffValue;
        long mask = 0xFL | (3L << 15) | (7L << 4) | (0xFL << 7) | (0xFL << 20) | (1L << 14) | (1L << 11);
        long v = toffValue | ((long) tbl << 15) | ((long) hstrt << 4) | ((long) hend << 7) | ((long) tpfd << 20);
        modify(REG_CHOPCONF, mask, v);
    }

    /** Constant-off-time ({@code chm = 1}) fields: tfd 0–15, offset −3…12. */
    public void configureConstantOffTime(int toffValue, int tbl, int tfd, int offset, boolean disfdcc, int tpfd)
            throws IOException {
        if (toffValue < 0 || toffValue > 15 || tbl < 0 || tbl > 3 || tfd < 0 || tfd > 15 || offset < -3 || offset > 12
                || tpfd < 0 || tpfd > 15) throw new IllegalArgumentException("value out of range");
        if (toffValue != 0) toff = toffValue;
        long mask = 0xFL | (3L << 15) | (7L << 4) | (0xFL << 7) | (0xFL << 20) | (1L << 14) | (1L << 12) | (1L << 11);
        long v = toffValue | ((long) tbl << 15) | ((long) (tfd & 7) << 4) | ((long) (offset + 3) << 7)
                | ((long) tpfd << 20) | (1L << 14) | flag(disfdcc, 1L << 12) | ((long) ((tfd >> 3) & 1) << 11);
        modify(REG_CHOPCONF, mask, v);
    }

    /** {@code CHOPCONF.vhighchm} / {@code vhighfs}. */
    public void configureHighVelocity(boolean vhighchm, boolean vhighfs) throws IOException {
        modify(REG_CHOPCONF, (1L << 19) | (1L << 18), flag(vhighchm, 1L << 19) | flag(vhighfs, 1L << 18));
    }

    /** {@code PWMCONF} fields (freewheel kept): pwmFreq 0–3, pwmReg 1–15, pwmLim 0–15. */
    public void configureStealthchop(boolean autoscale, boolean autograd, int pwmOfs, int pwmGrad, int pwmFreq,
                                     int pwmReg, int pwmLim) throws IOException {
        if (pwmFreq < 0 || pwmFreq > 3 || pwmReg < 1 || pwmReg > 15 || pwmLim < 0 || pwmLim > 15
                || pwmOfs < 0 || pwmOfs > 255 || pwmGrad < 0 || pwmGrad > 255) throw new IllegalArgumentException("value out of range");
        firstUse(REG_PWMCONF, PWMCONF_RESET);
        long mask = (0xFL << 28) | (0xFL << 24) | (1L << 19) | (1L << 18) | (3L << 16) | (0xFFL << 8) | 0xFFL;
        long v = ((long) pwmLim << 28) | ((long) pwmReg << 24) | flag(autograd, 1L << 19) | flag(autoscale, 1L << 18)
                | ((long) pwmFreq << 16) | ((long) pwmGrad << 8) | pwmOfs;
        modify(REG_PWMCONF, mask, v);
    }

    /** {@code PWMCONF.freewheel} 0–3. */
    public void setFreewheel(int mode) throws IOException {
        if (mode < 0 || mode > 3) throw new IllegalArgumentException("mode must be 0-3");
        firstUse(REG_PWMCONF, PWMCONF_RESET);
        modify(REG_PWMCONF, 3L << 20, (long) mode << 20);
    }

    /** {@code CHOPCONF.diss2g} / {@code diss2vs}: {@code true} keeps the protection. */
    public void setShortProtection(boolean shortGnd, boolean shortSupply) throws IOException {
        modify(REG_CHOPCONF, (1L << 30) | (1L << 31), flag(!shortGnd, 1L << 30) | flag(!shortSupply, 1L << 31));
    }

    /** {@code CHOPCONF.dedge} (external STEP/DIR only). */
    public void setDoubleEdge(boolean enabled) throws IOException {
        modify(REG_CHOPCONF, 1L << 29, flag(enabled, 1L << 29));
    }

    private void gconfBit(long mask, boolean on) throws IOException {
        modify(REG_GCONF, mask, flag(on, mask));
    }

    /** {@code GCONF.shaft}. */
    public void setDirectionInverted(boolean inverted) throws IOException {
        gconfBit(GCONF_SHAFT, inverted);
    }

    /** {@code GCONF.faststandstill}. */
    public void setFastStandstill(boolean enabled) throws IOException {
        gconfBit(1L << 1, enabled);
    }

    /** {@code GCONF.recalibrate}. */
    public void setRecalibrate(boolean enabled) throws IOException {
        gconfBit(1L, enabled);
    }

    /** {@code GCONF.small_hysteresis}. */
    public void setSmallHysteresis(boolean enabled) throws IOException {
        gconfBit(GCONF_SMALL_HYSTERESIS, enabled);
    }

    /** {@code GCONF.stop_enable}. */
    public void setEmergencyStop(boolean enabled) throws IOException {
        gconfBit(GCONF_STOP_ENABLE, enabled);
    }

    /** {@code GCONF} bits 7, 8, 12, 13 ({@code SD_MODE = 0} meanings). */
    public void configureDiag(boolean diag0StepOutput, boolean diag1DirOutput, boolean diag0PushPull,
                              boolean diag1PushPull) throws IOException {
        long v = flag(diag0StepOutput, 1L << 7) | flag(diag1DirOutput, 1L << 8) | flag(diag0PushPull, 1L << 12)
                | flag(diag1PushPull, 1L << 13);
        modify(REG_GCONF, (1L << 7) | (1L << 8) | (1L << 12) | (1L << 13), v);
    }

    /** {@code COOLCONF.sgt} −64…63 (lower = more sensitive); other fields are kept. */
    public void setStallThreshold(int sgt) throws IOException {
        if (sgt < -64 || sgt > 63) throw new IllegalArgumentException("sgt must be -64..63");
        modify(REG_COOLCONF, 0x7FL << 16, ((long) sgt & 0x7F) << 16);
    }

    /** {@code COOLCONF.sfilt}. */
    public void setStallguardFilter(boolean enabled) throws IOException {
        modify(REG_COOLCONF, 1L << 24, flag(enabled, 1L << 24));
    }

    /** CoolStep fields of {@code COOLCONF} ({@code sgt} / {@code sfilt} kept). */
    public void configureCoolstep(int semin, int semax, int seup, int sedn, int seimin) throws IOException {
        if (semin < 0 || semin > 15 || semax < 0 || semax > 15 || seup < 0 || seup > 3 || sedn < 0 || sedn > 3
                || seimin < 0 || seimin > 1) throw new IllegalArgumentException("value out of range");
        long mask = 0xFL | (3L << 5) | (0xFL << 8) | (3L << 13) | (1L << 15);
        long v = semin | ((long) seup << 5) | ((long) semax << 8) | ((long) sedn << 13) | ((long) seimin << 15);
        modify(REG_COOLCONF, mask, v);
    }

    /** {@code SW_MODE.sg_stop}: stop the motor on a StallGuard2 event (needs {@code TCOOLTHRS}; not with soft stop). */
    public void enableStallStop(boolean enabled) throws IOException {
        if (enabled && (shadow[REG_SW_MODE] & SW_SOFTSTOP) != 0) throw new IllegalArgumentException("sg_stop conflicts with soft stop");
        modify(REG_SW_MODE, SW_SG_STOP, flag(enabled, SW_SG_STOP));
    }

    /** {@code SG_RESULT} 0–1023. */
    public int readStallguard() throws IOException {
        return (int) (readReg(REG_DRV_STATUS) & 0x3FF);
    }

    /** {@code DRV_STATUS.StallGuard}. */
    public boolean isStalled() throws IOException {
        return bit(readReg(REG_DRV_STATUS), 24);
    }

    /**
     * Level of {@code DIAG0} ({@code n = 0}) or {@code DIAG1} ({@code n = 1}).
     *
     * @throws IllegalStateException if the pin is not wired (or on UART)
     */
    public boolean readDiag(int n) throws IOException {
        if (n != 0 && n != 1) throw new IllegalArgumentException("n must be 0 or 1");
        InputPin p = n == 0 ? diag0 : diag1;
        if (p == null) throw new IllegalStateException("DIAG" + n + " pin not configured");
        return p.isHigh();
    }

    /** {@code VDCMIN} (0…2²³−1), {@code DCCTRL.DC_TIME} (0–1023), {@code DC_SG} (0–255). */
    public void setDcstep(long vdcmin, int dcTime, int dcSg) throws IOException {
        if (vdcmin < 0 || vdcmin > 0x7FFFFFL || dcTime < 0 || dcTime > 1023 || dcSg < 0 || dcSg > 255) {
            throw new IllegalArgumentException("value out of range");
        }
        writeReg(REG_VDCMIN, vdcmin);
        writeReg(REG_DCCTRL, dcTime | ((long) dcSg << 16));
    }

    /** {@code GCONF.direct_mode} (the ramp generator is bypassed while set; leaving it rewrites {@code XTARGET}). */
    public void setDirectMode(boolean enabled) throws IOException {
        gconfBit(GCONF_DIRECT_MODE, enabled);
        if (!enabled) writeReg(REG_XTARGET, shadow[REG_XTARGET]);
    }

    /** {@code XDIRECT} coil currents −255…255 each; requires direct mode. */
    public void setCoilCurrents(int coilA, int coilB) throws IOException {
        if ((shadow[REG_GCONF] & GCONF_DIRECT_MODE) == 0) throw new IllegalStateException("direct mode required");
        if (coilA < -255 || coilA > 255 || coilB < -255 || coilB > 255) throw new IllegalArgumentException("value out of range");
        writeReg(REG_XTARGET, ((long) coilA & 0x1FF) | (((long) coilB & 0x1FF) << 16));
    }

    /** Program {@code MSLUT[0..7]}, {@code MSLUTSEL}, {@code MSLUTSTART}; call at {@code MSCNT = 0}. */
    public void writeMicrostepTable(long[] mslut, long mslutsel, long mslutstart) throws IOException {
        if (mslut.length != 8) throw new IllegalArgumentException("mslut needs 8 words");
        for (int i = 0; i < 8; i++) writeReg(REG_MSLUT0 + i, mslut[i]);
        writeReg(REG_MSLUTSEL, mslutsel);
        writeReg(REG_MSLUTSTART, mslutstart);
    }

    /** {@code DRV_CONF} — board-specific, wrong values can destroy the MOSFETs. */
    public void configureGateDriver(int bbmtime, int bbmclks, int otselect, int drvstrength, int filtIsense)
            throws IOException {
        if (bbmtime < 0 || bbmtime > 24 || bbmclks < 0 || bbmclks > 15 || otselect < 0 || otselect > 3
                || drvstrength < 0 || drvstrength > 3 || filtIsense < 0 || filtIsense > 3) {
            throw new IllegalArgumentException("value out of range");
        }
        writeReg(REG_DRV_CONF, bbmtime | ((long) bbmclks << 8) | ((long) otselect << 16) | ((long) drvstrength << 18)
                | ((long) filtIsense << 20));
    }

    /** {@code SHORT_CONF} — board-specific: s2vsLevel 4–15, s2gLevel 2–15, shortfilter 0–3, shortdelay 0|1. */
    public void configureShortDetection(int s2vsLevel, int s2gLevel, int shortfilter, int shortdelay)
            throws IOException {
        if (s2vsLevel < 4 || s2vsLevel > 15 || s2gLevel < 2 || s2gLevel > 15 || shortfilter < 0 || shortfilter > 3
                || shortdelay < 0 || shortdelay > 1) throw new IllegalArgumentException("value out of range");
        writeReg(REG_SHORT_CONF, s2vsLevel | ((long) s2gLevel << 8) | ((long) shortfilter << 16)
                | ((long) shortdelay << 18));
    }

    /**
     * {@code ENCMODE}; {@code flags} is, in register bit order: pol_a, pol_b, pol_n, ignore_ab, clr_cont, clr_once,
     * pos_edge, neg_edge, clr_enc_x, latch_x_act, decimal.
     */
    public void configureEncoder(boolean[] flags) throws IOException {
        if (flags.length != 11) throw new IllegalArgumentException("11 flags required");
        long v = 0;
        for (int i = 0; i < 11; i++) v |= flag(flags[i], 1L << i);
        writeReg(REG_ENCMODE, v);
    }

    /** {@code ENC_CONST}: signed 16.16 (or integer + 4-digit decimal fraction when {@code enc_sel_decimal} is set). */
    public void setEncoderConstant(double value) throws IOException {
        if (Math.abs(value) > 32767.9999) throw new IllegalArgumentException("value out of range");
        long raw;
        if ((shadow[REG_ENCMODE] & (1L << 10)) != 0) {
            double a = Math.abs(value);
            long whole = (long) Math.floor(a);
            raw = (whole << 16) | Math.round((a - whole) * 10000.0);
            if (value < 0) raw = -raw;
        } else {
            raw = Math.round(value * 65536.0);
        }
        writeReg(REG_ENC_CONST, raw & 0xFFFFFFFFL);
    }

    /** {@code X_ENC}. */
    public void setEncoderPosition(int counts) throws IOException {
        writeReg(REG_X_ENC, counts & 0xFFFFFFFFL);
    }

    /** {@code X_ENC} signed. */
    public int readEncoderPosition() throws IOException {
        return (int) readReg(REG_X_ENC);
    }

    /** {@code ENC_LATCH} signed. */
    public int readEncoderLatch() throws IOException {
        return (int) readReg(REG_ENC_LATCH);
    }

    /** {@code ENC_DEVIATION} 0…2²⁰−1 (0 = off). */
    public void setEncoderDeviation(long steps) throws IOException {
        threshold(REG_ENC_DEVIATION, steps);
    }

    /** {@code {n_event, deviation_warn}}. */
    public boolean[] readEncoderStatus() throws IOException {
        long s = readReg(REG_ENC_STATUS);
        return new boolean[] {bit(s, 0), bit(s, 1)};
    }

    /** Write 1 to the {@code ENC_STATUS} event bits. */
    public void clearEncoderStatus() throws IOException {
        writeReg(REG_ENC_STATUS, 3);
    }

    /** {@code SLAVECONF.SLAVEADDR} 0–253 (UART only); later datagrams use the new address. */
    public void setSlaveAddress(int slaveaddr) throws IOException {
        requireUart();
        if (slaveaddr < 0 || slaveaddr > 253) throw new IllegalArgumentException("address must be 0-253");
        writeReg(REG_SLAVECONF, slaveaddr | ((long) sendDelay << 8));
        address = slaveaddr;
    }

    /** {@code SLAVECONF.SENDDELAY} 0–15 (UART only; at least 2 on multi-node buses). */
    public void setSendDelay(int sd) throws IOException {
        requireUart();
        if (sd < 0 || sd > 15) throw new IllegalArgumentException("sendDelay must be 0-15");
        sendDelay = sd;
        writeReg(REG_SLAVECONF, (shadow[REG_SLAVECONF] & 0xFF) | ((long) sd << 8));
    }

    /** {@code OUTPUT} bit 0, the {@code NAO} polarity (UART only). */
    public void setNaoPolarity(boolean level) throws IOException {
        requireUart();
        writeReg(REG_OUTPUT, flag(level, 1));
    }

    /** {@code IFCNT} (UART only). */
    public int readIfcnt() throws IOException {
        requireUart();
        return (int) (readReg(REG_IFCNT) & 0xFF);
    }

    /** {@code MSCNT} 0–1023. */
    public int microstepPosition() throws IOException {
        return (int) (readReg(REG_MSCNT) & 0x3FF);
    }

    /** {@code DRV_STATUS.CS_ACTUAL} 0–31. */
    public int readCurrentScale() throws IOException {
        return (int) ((readReg(REG_DRV_STATUS) >> 16) & 0x1F);
    }

    /** Signed {@code {CUR_A, CUR_B}} from {@code MSCURACT}. */
    public int[] readCoilCurrents() throws IOException {
        long raw = readReg(REG_MSCURACT);
        return new int[] {signExtend(raw >> 16, 9), signExtend(raw, 9)};
    }

    /** {@code TSTEP} (20 bit). */
    public long readTstep() throws IOException {
        return readReg(REG_TSTEP) & 0xFFFFFL;
    }

    /** {@code {PWM_SCALE_SUM, PWM_SCALE_AUTO}}. */
    public int[] readPwmScale() throws IOException {
        long raw = readReg(REG_PWM_SCALE);
        return new int[] {(int) (raw & 0xFF), signExtend(raw >> 16, 9)};
    }

    /** {@code {PWM_OFS_AUTO, PWM_GRAD_AUTO}}. */
    public int[] readPwmAuto() throws IOException {
        long raw = readReg(REG_PWM_AUTO);
        return new int[] {(int) (raw & 0xFF), (int) ((raw >> 16) & 0xFF)};
    }

    /** Signed {@code {phase A, phase B}} from {@code OFFSET_READ}. */
    public int[] readOffset() throws IOException {
        long raw = readReg(REG_OFFSET_READ);
        return new int[] {(byte) (raw >> 8), (byte) raw};
    }

    /** Decoded {@code DRV_STATUS} (one read). */
    public Status readStatus() throws IOException {
        long s = readReg(REG_DRV_STATUS);
        return new Status((s >> 31) == 1, bit(s, 14), bit(s, 15), (int) ((s >> 16) & 0x1F), (int) (s & 0x3FF),
                bit(s, 24), bit(s, 26), bit(s, 25), (s & (3L << 27)) != 0, (s & (3L << 12)) != 0,
                (s & (3L << 29)) != 0);
    }

    /** Decoded {@code GSTAT} (does not clear). */
    public GlobalStatus readGlobalStatus() throws IOException {
        long g = readReg(REG_GSTAT);
        return new GlobalStatus(bit(g, 0), bit(g, 1), bit(g, 2));
    }

    /** Write {@code 0x07} to {@code GSTAT}. */
    public void clearGlobalStatus() throws IOException {
        writeReg(REG_GSTAT, 7);
    }

    /** Decoded {@code IOIN}. */
    public Ioin readIoin() throws IOException {
        long r = readReg(REG_IOIN);
        return new Ioin(r, bit(r, 0), bit(r, 1), bit(r, 2), bit(r, 3), bit(r, 4), bit(r, 5), bit(r, 6), bit(r, 7),
                (int) (r >> 24) & 0xFF);
    }
}
