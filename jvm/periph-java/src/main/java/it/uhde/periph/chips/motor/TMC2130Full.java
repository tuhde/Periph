package it.uhde.periph.chips.motor;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.OutputPin;

import java.io.IOException;

/**
 * TMC2130 full interface — extends {@link TMC2130Minimal} with raw register
 * access, independent hold current, microstep, chopper and StealthChop tuning,
 * StallGuard2 / CoolStep, DIAG routing, DcStep, direct coil-current mode, the
 * microstep table and every status read-back.
 */
public class TMC2130Full extends TMC2130Minimal {

    /**
     * Decoded {@code DRV_STATUS}.
     *
     * @param stst       standstill
     * @param fsactive   full-step operation active
     * @param csActual   actual current scale 0–31
     * @param sgResult   StallGuard2 result 0–1023
     * @param stallguard stall detected
     * @param otpw       over-temperature pre-warning
     * @param ot         over-temperature shutdown
     * @param shortGnd   short to GND on either phase
     * @param openLoad   open load on either phase
     */
    public record Status(boolean stst, boolean fsactive, int csActual, int sgResult, boolean stallguard,
                         boolean otpw, boolean ot, boolean shortGnd, boolean openLoad) {}

    /**
     * Decoded {@code GSTAT}.
     *
     * @param reset  IC was reset since the last read
     * @param drvErr driver shut down by over-temperature or short
     * @param uvCp   charge-pump undervoltage
     */
    public record GlobalStatus(boolean reset, boolean drvErr, boolean uvCp) {}

    /**
     * Decoded {@code IOIN}.
     *
     * @param raw     raw 32-bit word
     * @param step    state of STEP
     * @param dir     state of DIR
     * @param dcen    state of DCEN
     * @param dcin    state of DCIN
     * @param enn     state of ENN
     * @param dco     state of DCO
     * @param version silicon version ({@code 0x11})
     */
    public record Ioin(long raw, boolean step, boolean dir, boolean dcen, boolean dcin, boolean enn,
                       boolean dco, int version) {}

    /**
     * The two signed 9-bit {@code MSCURACT} values.
     *
     * @param curA bits 24:16 (co-sine)
     * @param curB bits 8:0 (sine)
     */
    public record CoilCurrents(int curA, int curB) {}

    private final InputPin diag0;
    private final InputPin diag1;

    /**
     * Construct the driver, probe the chip and apply the default configuration.
     *
     * @param connection SPI connection configured for Mode 3
     * @param stepPin    {@code STEP} output
     * @param dirPin     {@code DIR} output
     * @param rSenseOhm  external sense resistor in ohm
     * @param enPin      optional {@code ENN} output (active low), may be {@code null}
     * @param diag0      optional {@code DIAG0} input, may be {@code null}
     * @param diag1      optional {@code DIAG1} input, may be {@code null}
     * @throws IOException if the chip does not answer or fails verification
     */
    public TMC2130Full(Connection connection, OutputPin stepPin, OutputPin dirPin, double rSenseOhm,
                       OutputPin enPin, InputPin diag0, InputPin diag1) throws IOException {
        super(connection, stepPin, dirPin, rSenseOhm, enPin);
        this.diag0 = diag0;
        this.diag1 = diag1;
    }

    /** Construct with defaults: 0.11 ohm, no ENN / DIAG pins. */
    public TMC2130Full(Connection connection, OutputPin stepPin, OutputPin dirPin) throws IOException {
        this(connection, stepPin, dirPin, 0.11, null, null, null);
    }

    private static boolean readable(int r) {
        return switch (r) {
            case 0x00, 0x01, 0x04, 0x12, 0x2D, 0x6A, 0x6B, 0x6C, 0x6F, 0x71, 0x73 -> true;
            default -> false;
        };
    }

    private static boolean writable(int r) {
        return switch (r) {
            case 0x00, 0x10, 0x11, 0x13, 0x14, 0x15, 0x2D, 0x33, 0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66,
                 0x67, 0x68, 0x69, 0x6C, 0x6D, 0x6E, 0x70 -> true;
            default -> false;
        };
    }

    private static int signed(long v, int bits) {
        int x = (int) (v & ((1L << bits) - 1));
        return (x & (1 << (bits - 1))) != 0 ? x - (1 << bits) : x;
    }

    /**
     * @param reg readable register address
     * @return raw 32-bit value
     * @throws IOException              on SPI error
     * @throws IllegalArgumentException for write-only or reserved registers
     */
    public long readRegister(int reg) throws IOException {
        if (!readable(reg)) throw new IllegalArgumentException(String.format("register 0x%02X is not readable", reg));
        return readReg(reg);
    }

    /**
     * @param reg    writable register address
     * @param value  32-bit value
     * @param verify also check the mirror of the write
     * @throws IOException              on SPI error or mirror mismatch
     * @throws IllegalArgumentException for read-only or reserved registers
     */
    public void writeRegister(int reg, long value, boolean verify) throws IOException {
        if (!writable(reg)) throw new IllegalArgumentException(String.format("register 0x%02X is not writable", reg));
        writeReg(reg, value, verify);
    }

    /**
     * Set the standstill hold current independently of the run current.
     *
     * @param rmsA hold current in A RMS
     * @throws IOException on SPI error
     */
    public void setHoldCurrent(double rmsA) throws IOException {
        holdCs = currentToCs(rmsA, vsense)[0];
        writeIholdIrun(false);
    }

    /**
     * Set {@code IHOLDDELAY} and {@code TPOWERDOWN}.
     *
     * @param ihold     {@code IHOLDDELAY} 0–15
     * @param powerdown {@code TPOWERDOWN} 0–255
     * @throws IOException              on SPI error
     * @throws IllegalArgumentException if out of range
     */
    public void setCurrentDelay(int ihold, int powerdown) throws IOException {
        if (ihold < 0 || ihold > 15 || powerdown < 0 || powerdown > 255) {
            throw new IllegalArgumentException("ihold_delay 0-15 / tpowerdown 0-255");
        }
        iholdDelay = ihold;
        writeIholdIrun(false);
        writeReg(REG_TPOWERDOWN, powerdown, false);
    }

    /**
     * Select the microstep resolution.
     *
     * @param n 1, 2, 4, 8, 16, 32, 64, 128 or 256
     * @throws IOException              on SPI error
     * @throws IllegalArgumentException for any other value
     */
    public void setMicrosteps(int n) throws IOException {
        if (n < 1 || n > 256 || (n & (n - 1)) != 0) throw new IllegalArgumentException("microsteps must be a power of two, 1-256");
        modify(REG_CHOPCONF, 0xFL << 24, (long) (8 - Integer.numberOfTrailingZeros(n)) << 24);
        microsteps = n;
    }

    /**
     * @param enabled interpolate to 256 microsteps ({@code CHOPCONF.intpol})
     * @throws IOException on SPI error
     */
    public void setInterpolation(boolean enabled) throws IOException {
        modify(REG_CHOPCONF, 1L << 28, enabled ? (1L << 28) : 0);
    }

    /**
     * @param spreadcycle true for SpreadCycle only, false for StealthChop (switch at standstill)
     * @throws IOException on SPI error
     */
    public void setChopperMode(boolean spreadcycle) throws IOException {
        modify(REG_GCONF, GCONF_EN_PWM_MODE, spreadcycle ? 0 : GCONF_EN_PWM_MODE);
    }

    /**
     * @param tpwmthrs {@code TPWMTHRS} (20 bit); 0 disables
     * @throws IOException              on SPI error
     * @throws IllegalArgumentException if out of range
     */
    public void setStealthchopThreshold(long tpwmthrs) throws IOException {
        if (tpwmthrs < 0 || tpwmthrs > 0xFFFFF) throw new IllegalArgumentException("tpwmthrs 0-1048575");
        writeReg(REG_TPWMTHRS, tpwmthrs, false);
    }

    /**
     * @param thigh {@code THIGH} (20 bit)
     * @throws IOException              on SPI error
     * @throws IllegalArgumentException if out of range
     */
    public void setHighVelocityThreshold(long thigh) throws IOException {
        if (thigh < 0 || thigh > 0xFFFFF) throw new IllegalArgumentException("thigh 0-1048575");
        writeReg(REG_THIGH, thigh, false);
    }

    /**
     * Write the SpreadCycle ({@code chm = 0}) fields of {@code CHOPCONF}.
     *
     * @param toffV off time 0–15 (0 disables the driver)
     * @param tbl   blank time select 0–3
     * @param hstrt hysteresis start 0–7
     * @param hend  raw hysteresis end field 0–15
     * @throws IOException              on SPI error
     * @throws IllegalArgumentException if a field is out of range
     */
    public void configureChopper(int toffV, int tbl, int hstrt, int hend) throws IOException {
        if (toffV < 0 || toffV > 15 || tbl < 0 || tbl > 3 || hstrt < 0 || hstrt > 7 || hend < 0 || hend > 15) {
            throw new IllegalArgumentException("chopper field out of range");
        }
        if (toffV != 0) toff = toffV;
        long mask = 0xFL | (3L << 15) | (7L << 4) | (0xFL << 7) | (1L << 14) | (1L << 11);
        modify(REG_CHOPCONF, mask, toffV | ((long) tbl << 15) | ((long) hstrt << 4) | ((long) hend << 7));
    }

    /**
     * Write the constant-off-time ({@code chm = 1}) fields of {@code CHOPCONF}.
     *
     * @param toffV   off time 0–15
     * @param tbl     blank time select 0–3
     * @param tfd     fast decay time 0–15
     * @param offset  sine wave offset −3…12
     * @param disfdcc disable current-comparator fast-decay termination
     * @param rndtf   random off-time modulation
     * @throws IOException              on SPI error
     * @throws IllegalArgumentException if a field is out of range
     */
    public void configureConstantOffTime(int toffV, int tbl, int tfd, int offset, boolean disfdcc,
                                         boolean rndtf) throws IOException {
        if (toffV < 0 || toffV > 15 || tbl < 0 || tbl > 3 || tfd < 0 || tfd > 15 || offset < -3 || offset > 12) {
            throw new IllegalArgumentException("chopper field out of range");
        }
        if (toffV != 0) toff = toffV;
        long mask = 0xFL | (3L << 15) | (7L << 4) | (0xFL << 7) | (1L << 14) | (1L << 13) | (1L << 12) | (1L << 11);
        long v = toffV | ((long) tbl << 15) | ((long) (tfd & 7) << 4) | ((long) (offset + 3) << 7) | (1L << 14)
                | (rndtf ? (1L << 13) : 0) | (disfdcc ? (1L << 12) : 0) | ((long) ((tfd >> 3) & 1) << 11);
        modify(REG_CHOPCONF, mask, v);
    }

    /**
     * @param vhighchm switch to {@code chm = 1} above {@code THIGH}
     * @param vhighfs  switch to full-step operation above {@code THIGH}
     * @throws IOException on SPI error
     */
    public void configureHighVelocity(boolean vhighchm, boolean vhighfs) throws IOException {
        modify(REG_CHOPCONF, (1L << 19) | (1L << 18), (vhighchm ? (1L << 19) : 0) | (vhighfs ? (1L << 18) : 0));
    }

    /**
     * @param sync {@code CHOPCONF.SYNC} 0–15 (chopSync2); 0 disables
     * @throws IOException              on SPI error
     * @throws IllegalArgumentException if out of range
     */
    public void setChopperSync(int sync) throws IOException {
        if (sync < 0 || sync > 15) throw new IllegalArgumentException("sync 0-15");
        modify(REG_CHOPCONF, 0xFL << 20, (long) sync << 20);
    }

    /**
     * Write the StealthChop fields of {@code PWMCONF} ({@code freewheel} is kept).
     *
     * @param autoscale automatic current control
     * @param symmetric force symmetric PWM cycles
     * @param pwmAmpl   amplitude 0–255
     * @param pwmGrad   gradient 0–255
     * @param pwmFreq   PWM frequency select 0–3
     * @throws IOException              on SPI error
     * @throws IllegalArgumentException if a field is out of range
     */
    public void configureStealthchop(boolean autoscale, boolean symmetric, int pwmAmpl, int pwmGrad,
                                     int pwmFreq) throws IOException {
        if (pwmAmpl < 0 || pwmAmpl > 255 || pwmGrad < 0 || pwmGrad > 255 || pwmFreq < 0 || pwmFreq > 3) {
            throw new IllegalArgumentException("stealthchop field out of range");
        }
        long mask = (1L << 19) | (1L << 18) | (3L << 16) | (0xFFL << 8) | 0xFFL;
        long v = (symmetric ? (1L << 19) : 0) | (autoscale ? (1L << 18) : 0) | ((long) pwmFreq << 16)
                | ((long) pwmGrad << 8) | pwmAmpl;
        modify(REG_PWMCONF, mask, v);
    }

    /**
     * @param mode standstill mode when {@code IHOLD} is 0: 0 normal, 1 freewheel, 2 low-side short, 3 high-side short
     * @throws IOException              on SPI error
     * @throws IllegalArgumentException if not 0–3
     */
    public void setFreewheel(int mode) throws IOException {
        if (mode < 0 || mode > 3) throw new IllegalArgumentException("freewheel mode 0-3");
        modify(REG_PWMCONF, 3L << 20, (long) mode << 20);
    }

    /**
     * Select the sense range and disable the automatic selection in {@link #setCurrent}.
     *
     * @param highSensitivity true for the 0.18 V range
     * @throws IOException on SPI error
     */
    public void setVsense(boolean highSensitivity) throws IOException {
        modify(REG_CHOPCONF, 1L << 17, highSensitivity ? (1L << 17) : 0);
        vsense = highSensitivity;
        autoVsense = false;
    }

    /**
     * @param enabled use the internal sense resistors ({@code setCurrent} then throws)
     * @throws IOException on SPI error
     */
    public void setInternalRsense(boolean enabled) throws IOException {
        modify(REG_GCONF, GCONF_INTERNAL_RSENSE, enabled ? GCONF_INTERNAL_RSENSE : 0);
        internalRsense = enabled;
    }

    /**
     * @param enabled take the current reference from {@code AIN_IREF}
     * @throws IOException on SPI error
     */
    public void setAnalogScaling(boolean enabled) throws IOException {
        modify(REG_GCONF, GCONF_I_SCALE_ANALOG, enabled ? GCONF_I_SCALE_ANALOG : 0);
    }

    /**
     * @param inverted invert the motor direction ({@code GCONF.shaft})
     * @throws IOException on SPI error
     */
    public void setDirectionInverted(boolean inverted) throws IOException {
        modify(REG_GCONF, GCONF_SHAFT, inverted ? GCONF_SHAFT : 0);
    }

    /**
     * Route events to the DIAG pins ({@code GCONF} bits 5–13).
     *
     * @param diag0Error     DIAG0 on driver error
     * @param diag0Otpw      DIAG0 on over-temperature pre-warning
     * @param diag0Stall     DIAG0 on stall
     * @param diag1Stall     DIAG1 on stall
     * @param diag1Index     DIAG1 on index position
     * @param diag1Onstate   DIAG1 while the chopper is on
     * @param diag0Pushpull  DIAG0 push-pull (active high)
     * @param diag1Pushpull  DIAG1 push-pull (active high)
     * @throws IOException on SPI error
     */
    public void configureDiag(boolean diag0Error, boolean diag0Otpw, boolean diag0Stall, boolean diag1Stall,
                              boolean diag1Index, boolean diag1Onstate, boolean diag0Pushpull,
                              boolean diag1Pushpull) throws IOException {
        long v = (diag0Error ? 1L << 5 : 0) | (diag0Otpw ? 1L << 6 : 0) | (diag0Stall ? 1L << 7 : 0)
                | (diag1Stall ? 1L << 8 : 0) | (diag1Index ? 1L << 9 : 0) | (diag1Onstate ? 1L << 10 : 0)
                | (diag0Pushpull ? 1L << 12 : 0) | (diag1Pushpull ? 1L << 13 : 0);
        modify(REG_GCONF, 0x3FE0L & ~(1L << 11), v);
    }

    /**
     * @param enabled step on both STEP edges ({@code CHOPCONF.dedge})
     * @throws IOException on SPI error
     */
    public void setDoubleEdge(boolean enabled) throws IOException {
        modify(REG_CHOPCONF, 1L << 29, enabled ? (1L << 29) : 0);
    }

    /**
     * @param shortGnd true keeps the short-to-GND protection
     * @throws IOException on SPI error
     */
    public void setShortProtection(boolean shortGnd) throws IOException {
        modify(REG_CHOPCONF, 1L << 30, shortGnd ? 0 : (1L << 30));
    }

    /**
     * @param enabled use 1/32 instead of 1/16 comparison hysteresis
     * @throws IOException on SPI error
     */
    public void setSmallHysteresis(boolean enabled) throws IOException {
        modify(REG_GCONF, GCONF_SMALL_HYSTERESIS, enabled ? GCONF_SMALL_HYSTERESIS : 0);
    }

    /**
     * @param enabled let {@code DCIN} stop the sequencer ({@code GCONF.stop_enable})
     * @throws IOException on SPI error
     */
    public void setEmergencyStop(boolean enabled) throws IOException {
        modify(REG_GCONF, GCONF_STOP_ENABLE, enabled ? GCONF_STOP_ENABLE : 0);
    }

    /**
     * Set {@code COOLCONF.sgt}, keeping the other {@code COOLCONF} fields.
     *
     * @param sgt StallGuard2 threshold −64…63 (lower is more sensitive)
     * @throws IOException              on SPI error
     * @throws IllegalArgumentException if out of range
     */
    public void setStallThreshold(int sgt) throws IOException {
        if (sgt < -64 || sgt > 63) throw new IllegalArgumentException("sgt -64..63");
        modify(REG_COOLCONF, 0x7FL << 16, (long) (sgt & 0x7F) << 16);
    }

    /**
     * @param enabled {@code COOLCONF.sfilt}
     * @throws IOException on SPI error
     */
    public void setStallguardFilter(boolean enabled) throws IOException {
        modify(REG_COOLCONF, 1L << 24, enabled ? (1L << 24) : 0);
    }

    /**
     * @param tcoolthrs {@code TCOOLTHRS} (20 bit); 0 disables
     * @throws IOException              on SPI error
     * @throws IllegalArgumentException if out of range
     */
    public void setCoolstepThreshold(long tcoolthrs) throws IOException {
        if (tcoolthrs < 0 || tcoolthrs > 0xFFFFF) throw new IllegalArgumentException("tcoolthrs 0-1048575");
        writeReg(REG_TCOOLTHRS, tcoolthrs, false);
    }

    /**
     * Write the CoolStep fields of {@code COOLCONF} ({@code sgt} / {@code sfilt} are kept).
     *
     * @param semin  lower threshold 0–15; 0 disables CoolStep
     * @param semax  upper threshold offset 0–15
     * @param seup   current up step width 0–3 (1, 2, 4, 8)
     * @param sedn   current down step speed 0–3
     * @param seimin 0 for half IRUN minimum, 1 for quarter
     * @throws IOException              on SPI error
     * @throws IllegalArgumentException if a field is out of range
     */
    public void configureCoolstep(int semin, int semax, int seup, int sedn, int seimin) throws IOException {
        if (semin < 0 || semin > 15 || semax < 0 || semax > 15 || seup < 0 || seup > 3 || sedn < 0 || sedn > 3
                || seimin < 0 || seimin > 1) {
            throw new IllegalArgumentException("COOLCONF field out of range");
        }
        long mask = 0xFL | (3L << 5) | (0xFL << 8) | (3L << 13) | (1L << 15);
        modify(REG_COOLCONF, mask, semin | ((long) seup << 5) | ((long) semax << 8) | ((long) sedn << 13)
                | ((long) seimin << 15));
    }

    /**
     * @return {@code SG_RESULT} 0–1023 (0 = highest load)
     * @throws IOException on SPI error
     */
    public int readStallguard() throws IOException {
        return (int) (readReg(REG_DRV_STATUS) & 0x3FF);
    }

    /**
     * @return {@code DRV_STATUS.StallGuard}
     * @throws IOException on SPI error
     */
    public boolean isStalled() throws IOException {
        return (readReg(REG_DRV_STATUS) & (1L << 24)) != 0;
    }

    /**
     * @param n 0 for {@code DIAG0}, 1 for {@code DIAG1}
     * @return pin level
     * @throws IOException              on pin error
     * @throws IllegalArgumentException if n is not 0 or 1
     * @throws IllegalStateException    if that pin was not wired
     */
    public boolean readDiag(int n) throws IOException {
        if (n != 0 && n != 1) throw new IllegalArgumentException("n must be 0 or 1");
        InputPin pin = n == 0 ? diag0 : diag1;
        if (pin == null) throw new IllegalStateException("readDiag() requires a DIAG" + n + " pin");
        return pin.isHigh();
    }

    /**
     * Emit STEP pulses (no microstep conversion).
     *
     * @param count  pulses; negative reverses
     * @param delayS time between edges in seconds
     * @throws IOException on pin error
     */
    public void step(int count, double delayS) throws IOException {
        long nanos = (long) (delayS * 1e9);
        dirPin.set(count >= 0);
        for (int i = 0; i < Math.abs(count); i++) {
            stepPin.set(true);
            sleepNanos(nanos);
            stepPin.set(false);
            sleepNanos(nanos);
        }
    }

    /**
     * @param enabled write coil currents via {@code XDIRECT} ({@code GCONF.direct_mode})
     * @throws IOException on SPI error
     */
    public void setDirectMode(boolean enabled) throws IOException {
        modify(REG_GCONF, GCONF_DIRECT_MODE, enabled ? GCONF_DIRECT_MODE : 0);
    }

    /**
     * Write {@code XDIRECT} (scaled by {@code IHOLD}).
     *
     * @param coilA signed coil A current −255…255
     * @param coilB signed coil B current −255…255
     * @throws IOException              on SPI error
     * @throws IllegalStateException    if direct mode is not enabled
     * @throws IllegalArgumentException if out of range
     */
    public void setCoilCurrents(int coilA, int coilB) throws IOException {
        if ((gconf & GCONF_DIRECT_MODE) == 0) throw new IllegalStateException("setCoilCurrents() requires direct mode");
        if (coilA < -255 || coilA > 255 || coilB < -255 || coilB > 255) {
            throw new IllegalArgumentException("coil currents -255..255");
        }
        writeReg(REG_XDIRECT, (coilA & 0x1FFL) | ((coilB & 0x1FFL) << 16), false);
    }

    /**
     * Configure DcStep.
     *
     * @param vdcmin  {@code VDCMIN} 0…2²³−1; non-zero enables DcStep by velocity
     * @param dcTime  {@code DCCTRL.DC_TIME} 0–1023
     * @param dcSg    {@code DCCTRL.DC_SG} 0–255; 0 disables DcStep stall detection
     * @throws IOException              on SPI error
     * @throws IllegalArgumentException if out of range
     */
    public void setDcstep(long vdcmin, int dcTime, int dcSg) throws IOException {
        if (vdcmin < 0 || vdcmin > 0x7FFFFF || dcTime < 0 || dcTime > 1023 || dcSg < 0 || dcSg > 255) {
            throw new IllegalArgumentException("DcStep field out of range");
        }
        writeReg(REG_VDCMIN, vdcmin, false);
        writeReg(REG_DCCTRL, dcTime | ((long) dcSg << 16), false);
    }

    /**
     * Program the microstep table; call at {@code MSCNT = 0}.
     *
     * @param mslut      8 {@code MSLUT} words
     * @param mslutsel   {@code MSLUTSEL} word
     * @param mslutstart {@code MSLUTSTART} word
     * @throws IOException              on SPI error
     * @throws IllegalArgumentException if {@code mslut} does not hold 8 words
     */
    public void writeMicrostepTable(long[] mslut, long mslutsel, long mslutstart) throws IOException {
        if (mslut.length != 8) throw new IllegalArgumentException("mslut needs 8 words");
        for (int i = 0; i < 8; i++) writeReg(REG_MSLUT0 + i, mslut[i], false);
        writeReg(REG_MSLUTSEL, mslutsel, false);
        writeReg(REG_MSLUTSTART, mslutstart, false);
    }

    /**
     * @return {@code MSCNT} 0–1023
     * @throws IOException on SPI error
     */
    public int microstepPosition() throws IOException {
        return (int) (readReg(REG_MSCNT) & 0x3FF);
    }

    /**
     * @return {@code DRV_STATUS.CS_ACTUAL} 0–31
     * @throws IOException on SPI error
     */
    public int readCurrentScale() throws IOException {
        return (int) ((readReg(REG_DRV_STATUS) >> 16) & 0x1F);
    }

    /**
     * @return signed {@code (CUR_A, CUR_B)} from {@code MSCURACT}
     * @throws IOException on SPI error
     */
    public CoilCurrents readCoilCurrents() throws IOException {
        long raw = readReg(REG_MSCURACT);
        return new CoilCurrents(signed(raw >> 16, 9), signed(raw, 9));
    }

    /**
     * @return {@code TSTEP} (20 bit)
     * @throws IOException on SPI error
     */
    public long readTstep() throws IOException {
        return readReg(REG_TSTEP) & 0xFFFFF;
    }

    /**
     * @return {@code PWM_SCALE} 0–255
     * @throws IOException on SPI error
     */
    public int readPwmScale() throws IOException {
        return (int) (readReg(REG_PWM_SCALE) & 0xFF);
    }

    /**
     * @return {@code LOST_STEPS} (20 bit, wraps)
     * @throws IOException on SPI error
     */
    public long readLostSteps() throws IOException {
        return readReg(REG_LOST_STEPS) & 0xFFFFF;
    }

    /**
     * @return decoded {@code DRV_STATUS} (one read)
     * @throws IOException on SPI error
     */
    public Status readStatus() throws IOException {
        long s = readReg(REG_DRV_STATUS);
        return new Status((s & (1L << 31)) != 0, (s & (1L << 15)) != 0, (int) ((s >> 16) & 0x1F), (int) (s & 0x3FF),
                (s & (1L << 24)) != 0, (s & (1L << 26)) != 0, (s & (1L << 25)) != 0, (s & (3L << 27)) != 0,
                (s & (3L << 29)) != 0);
    }

    /**
     * @return decoded {@code GSTAT}; the chip clears the flags on read
     * @throws IOException on SPI error
     */
    public GlobalStatus readGlobalStatus() throws IOException {
        long g = readReg(REG_GSTAT);
        return new GlobalStatus((g & 1) != 0, (g & 2) != 0, (g & 4) != 0);
    }

    /**
     * @return decoded {@code IOIN}
     * @throws IOException on SPI error
     */
    public Ioin readIoin() throws IOException {
        long raw = readReg(REG_IOIN);
        return new Ioin(raw, (raw & 1) != 0, (raw & 2) != 0, (raw & 4) != 0, (raw & 8) != 0, (raw & 16) != 0,
                (raw & 32) != 0, (int) ((raw >> 24) & 0xFF));
    }
}
