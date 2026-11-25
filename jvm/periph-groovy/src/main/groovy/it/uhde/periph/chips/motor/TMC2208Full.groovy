package it.uhde.periph.chips.motor

import groovy.transform.CompileStatic
import groovy.transform.Canonical
import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.OutputPin

/**
 * TMC2208 full interface — extends {@link TMC2208Minimal} with raw register
 * access, independent hold current, microstep and chopper configuration,
 * StealthChop tuning, GPIO STEP/DIR pulsing and every status read-back.
 */
@CompileStatic
class TMC2208Full extends TMC2208Minimal {

    /** Decoded {@code DRV_STATUS}. */
    @Canonical
    static class Status {
        boolean stst
        boolean stealth
        int csActual
        boolean otpw
        boolean ot
        boolean shortGnd
        boolean shortSupply
        boolean openLoad
        boolean t120
        boolean t143
        boolean t150
        boolean t157
    }

    /** Decoded {@code GSTAT}. */
    @Canonical
    static class GlobalStatus {
        boolean reset
        boolean drvErr
        boolean uvCp
    }

    /** Decoded {@code IOIN} identity fields. */
    @Canonical
    static class Ioin {
        long raw
        boolean selA
        int version
    }

    /** StealthChop auto-tuning results. */
    @Canonical
    static class PwmAuto {
        int pwmScaleSum
        int pwmScaleAuto
        int pwmOfsAuto
        int pwmGradAuto
    }

    /** The two signed 9-bit {@code MSCURACT} values in register order. */
    @Canonical
    static class CoilCurrents {
        int low
        int high
    }

    private final OutputPin stepPin
    private final OutputPin dirPin

    /**
     * Construct the driver, probe the chip and apply the default configuration.
     *
     * @param connection UART connection to {@code PDN_UART}
     * @param rSenseOhm  external sense resistor in ohm
     * @param enPin      optional {@code ENN} output (active low), may be {@code null}
     * @param echo       true when TX and RX share {@code PDN_UART}
     * @param stepPin    optional {@code STEP} output, may be {@code null}
     * @param dirPin     optional {@code DIR} output, may be {@code null}
     * @param fclkHz     chip clock in Hz (12&nbsp;MHz internal)
     * @param tmc2224    true for the TMC2224 ({@code IOIN.SEL_A} = 0)
     * @throws IOException if the chip does not answer or fails verification
     */
    TMC2208Full(Connection connection, double rSenseOhm, OutputPin enPin, boolean echo,
                OutputPin stepPin, OutputPin dirPin, long fclkHz, boolean tmc2224) throws IOException {
        super(connection, rSenseOhm, enPin, echo, fclkHz, !tmc2224)
        this.stepPin = stepPin
        this.dirPin = dirPin
    }

    /** Construct with defaults: 0.11 ohm, no pins, echo on, 12 MHz, TMC2208. */
    TMC2208Full(Connection connection) throws IOException {
        this(connection, 0.11d, (OutputPin) null, true, (OutputPin) null, (OutputPin) null, 12_000_000L, false)
    }

    private static boolean readable(int r) {
        return [0x00, 0x01, 0x02, 0x05, 0x06, 0x07, 0x12, 0x6A, 0x6B, 0x6C, 0x6F, 0x70, 0x71, 0x72].contains(r)
    }

    private static boolean writable(int r) {
        return [0x00, 0x01, 0x03, 0x10, 0x11, 0x13, 0x22, 0x6C, 0x70].contains(r)
    }

    private static int signed(long v, int bits) {
        int x = (int) (v & ((1L << bits) - 1L))
        return (x & (1 << (bits - 1))) != 0 ? x - (1 << bits) : x
    }

    /**
     * @param reg readable register address
     * @return raw 32-bit value
     * @throws IllegalArgumentException for write-only or reserved registers
     */
    long readRegister(int reg) throws IOException {
        if (!readable(reg)) throw new IllegalArgumentException(String.format('register 0x%02X is not readable', reg))
        return readReg(reg)
    }

    /**
     * @param reg   writable register address
     * @param value 32-bit value
     * @throws IllegalArgumentException for read-only registers, {@code OTP_PROG} and {@code FACTORY_CONF}
     */
    void writeRegister(int reg, long value) throws IOException {
        if (!writable(reg)) throw new IllegalArgumentException(String.format('register 0x%02X is not writable', reg))
        writeReg(reg, value)
    }

    /**
     * @param rmsA hold current in A RMS
     * @throws IOException on UART error
     */
    void setHoldCurrent(double rmsA) throws IOException {
        holdCs = currentToCs(rmsA)
        writeIholdIrun()
    }

    /**
     * @param iholdDelay {@code IHOLDDELAY} 0–15
     * @param tpowerdown {@code TPOWERDOWN} 0–255
     * @throws IllegalArgumentException if out of range
     */
    void setCurrentDelay(int iholdDelay, int tpowerdown) throws IOException {
        if (iholdDelay < 0 || iholdDelay > 15 || tpowerdown < 0 || tpowerdown > 255) {
            throw new IllegalArgumentException('ihold_delay 0-15 / tpowerdown 0-255')
        }
        iholdDelayValue = iholdDelay
        writeIholdIrun()
        writeReg(REG_TPOWERDOWN, (long) tpowerdown)
    }

    /**
     * @param n microstep resolution: 1, 2, 4, 8, 16, 32, 64, 128 or 256
     * @throws IllegalArgumentException for any other value
     */
    void setMicrosteps(int n) throws IOException {
        int mres
        switch (n) {
            case 256: mres = 0; break
            case 128: mres = 1; break
            case 64: mres = 2; break
            case 32: mres = 3; break
            case 16: mres = 4; break
            case 8: mres = 5; break
            case 4: mres = 6; break
            case 2: mres = 7; break
            case 1: mres = 8; break
            default: throw new IllegalArgumentException('microsteps must be a power of two, 1-256')
        }
        modify(REG_CHOPCONF, 0xFL << 24, ((long) mres) << 24)
        stepRes = n
    }

    /** @param enabled {@code CHOPCONF.intpol} */
    void setInterpolation(boolean enabled) throws IOException {
        modify(REG_CHOPCONF, 1L << 28, enabled ? 1L << 28 : 0L)
    }

    /** @param spreadcycle true = SpreadCycle, false = StealthChop */
    void setChopperMode(boolean spreadcycle) throws IOException {
        modify(REG_GCONF, GCONF_EN_SPREADCYCLE, spreadcycle ? GCONF_EN_SPREADCYCLE : 0L)
    }

    /** @param tpwmthrs {@code TPWMTHRS} (20 bit); 0 disables the threshold */
    void setStealthchopThreshold(long tpwmthrs) throws IOException {
        writeReg(REG_TPWMTHRS, tpwmthrs & 0xFFFFFL)
    }

    /**
     * @param toff  off time 0–15 (0 disables the driver)
     * @param tbl   blank time select 0–3
     * @param hstrt hysteresis start 0–7
     * @param hend  raw hysteresis end field 0–15 (offset −3)
     */
    void configureChopper(int toff, int tbl, int hstrt, int hend) throws IOException {
        long mask = 0xFL | (3L << 15) | (7L << 4) | (0xFL << 7)
        long value = (((long) toff) & 0xFL) | ((((long) tbl) & 3L) << 15) | ((((long) hstrt) & 7L) << 4) |
                ((((long) hend) & 0xFL) << 7)
        if (toff != 0) toffValue = toff & 0xF
        modify(REG_CHOPCONF, mask, value)
    }

    /** Write the StealthChop fields of {@code PWMCONF}. */
    void configureStealthchop(boolean autoscale, boolean autograd, int pwmOfs, int pwmGrad,
                              int pwmFreq, int pwmReg, int pwmLim) throws IOException {
        long mask = (0xFL << 28) | (0xFL << 24) | (1L << 19) | (1L << 18) | (3L << 16) | (0xFFL << 8) | 0xFFL
        long value = ((((long) pwmLim) & 0xFL) << 28) | ((((long) pwmReg) & 0xFL) << 24) |
                (autograd ? 1L << 19 : 0L) | (autoscale ? 1L << 18 : 0L) |
                ((((long) pwmFreq) & 3L) << 16) | ((((long) pwmGrad) & 0xFFL) << 8) | (((long) pwmOfs) & 0xFFL)
        modify(REG_PWMCONF, mask, value)
    }

    /**
     * @param mode {@code PWMCONF.freewheel} 0–3
     * @throws IllegalArgumentException if out of range
     */
    void setFreewheel(int mode) throws IOException {
        if (mode < 0 || mode > 3) throw new IllegalArgumentException('freewheel mode 0-3')
        modify(REG_PWMCONF, 3L << 20, ((long) mode) << 20)
    }

    /** @param highSensitivity true for the low 0.18&nbsp;V range (changes the current conversion) */
    void setVsense(boolean highSensitivity) throws IOException {
        modify(REG_CHOPCONF, 1L << 17, highSensitivity ? 1L << 17 : 0L)
        vsenseOn = highSensitivity
    }

    /** @param enabled {@code GCONF.internal_Rsense}; {@link #setCurrent} then throws */
    void setInternalRsense(boolean enabled) throws IOException {
        modify(REG_GCONF, GCONF_INTERNAL_RSENSE, enabled ? GCONF_INTERNAL_RSENSE : 0L)
        rsenseInternal = enabled
    }

    /** @param enabled {@code GCONF.I_scale_analog} */
    void setAnalogScaling(boolean enabled) throws IOException {
        modify(REG_GCONF, GCONF_I_SCALE_ANALOG, enabled ? GCONF_I_SCALE_ANALOG : 0L)
    }

    /** @param inverted {@code GCONF.shaft} */
    void setDirectionInverted(boolean inverted) throws IOException {
        modify(REG_GCONF, GCONF_SHAFT, inverted ? GCONF_SHAFT : 0L)
    }

    /**
     * @param mode 0 first microstep, 1 {@code otpw}, 2 step pulses
     * @throws IllegalArgumentException if out of range
     */
    void setIndexOutput(int mode) throws IOException {
        long bits
        switch (mode) {
            case 0: bits = 0L; break
            case 1: bits = GCONF_INDEX_OTPW; break
            case 2: bits = GCONF_INDEX_STEP; break
            default: throw new IllegalArgumentException('index mode 0-2')
        }
        modify(REG_GCONF, GCONF_INDEX_OTPW | GCONF_INDEX_STEP, bits)
    }

    /**
     * @param shortLs  keep the low-side short protection
     * @param shortGnd keep the short-to-GND protection
     */
    void setDriverProtection(boolean shortLs, boolean shortGnd) throws IOException {
        long value = (shortLs ? 0L : 1L << 31) | (shortGnd ? 0L : 1L << 30)
        modify(REG_CHOPCONF, (1L << 31) | (1L << 30), value)
    }

    /** @param enabled {@code CHOPCONF.dedge} */
    void setDoubleEdge(boolean enabled) throws IOException {
        modify(REG_CHOPCONF, 1L << 29, enabled ? 1L << 29 : 0L)
    }

    /**
     * @param n {@code NODECONF.SENDDELAY} 0–15
     * @throws IllegalArgumentException if out of range
     */
    void setSendDelay(int n) throws IOException {
        if (n < 0 || n > 15) throw new IllegalArgumentException('send delay 0-15')
        writeReg(REG_NODECONF, ((long) n) << 8)
    }

    /**
     * Emit STEP pulses on the GPIO pin.
     *
     * @param count  number of pulses; negative reverses (needs a DIR pin)
     * @param delayS high and low time of each pulse in seconds
     * @throws IllegalStateException if no STEP pin (or no DIR pin for a negative count) was given
     */
    void step(int count, double delayS) throws IOException {
        if (stepPin == null) throw new IllegalStateException('step() requires a STEP pin')
        if (count < 0 && dirPin == null) throw new IllegalStateException('negative count requires a DIR pin')
        if (dirPin != null) dirPin.set(count >= 0)
        long nanos = (long) (delayS * 1e9d)
        for (int i = 0; i < Math.abs(count); i++) {
            stepPin.set(true)
            Thread.sleep((long) (nanos / 1_000_000L), (int) (nanos % 1_000_000L))
            stepPin.set(false)
            Thread.sleep((long) (nanos / 1_000_000L), (int) (nanos % 1_000_000L))
        }
    }

    /** @return {@code MSCNT} 0–1023 */
    int microstepPosition() throws IOException {
        return (int) (readReg(REG_MSCNT) & 0x3FFL)
    }

    /** @return {@code DRV_STATUS.CS_ACTUAL} 0–31 */
    int readCurrentScale() throws IOException {
        return (int) ((readReg(REG_DRV_STATUS) >> 16) & 0x1FL)
    }

    /** @return signed {@code MSCURACT} values in register order */
    CoilCurrents readCoilCurrents() throws IOException {
        long raw = readReg(REG_MSCURACT)
        return new CoilCurrents(signed(raw, 9), signed(raw >> 16, 9))
    }

    /** @return {@code TSTEP} in clock periods (20 bit) */
    long readTstep() throws IOException {
        return readReg(REG_TSTEP) & 0xFFFFFL
    }

    /** @return t120..t157 as bits 0–3 */
    int readTemperatureFlags() throws IOException {
        return (int) ((readReg(REG_DRV_STATUS) >> 8) & 0xFL)
    }

    /** @return decoded {@code DRV_STATUS} */
    Status readStatus() throws IOException {
        long s = readReg(REG_DRV_STATUS)
        return new Status((s & (1L << 31)) != 0L, (s & (1L << 30)) != 0L, (int) ((s >> 16) & 0x1FL),
                (s & 0x01L) != 0L, (s & 0x02L) != 0L, (s & 0x0CL) != 0L, (s & 0x30L) != 0L, (s & 0xC0L) != 0L,
                (s & (1L << 8)) != 0L, (s & (1L << 9)) != 0L, (s & (1L << 10)) != 0L, (s & (1L << 11)) != 0L)
    }

    /**
     * @param clear write the flags back (write-1-to-clear)
     * @return decoded {@code GSTAT}
     */
    GlobalStatus readGlobalStatus(boolean clear) throws IOException {
        long g = readReg(REG_GSTAT)
        if (clear) writeReg(REG_GSTAT, 0x07L)
        return new GlobalStatus((g & 1L) != 0L, (g & 2L) != 0L, (g & 4L) != 0L)
    }

    /** @return decoded {@code GSTAT} without clearing it */
    GlobalStatus readGlobalStatus() throws IOException {
        return readGlobalStatus(false)
    }

    /** @return {@code IFCNT} write counter 0–255 */
    int readIfcnt() throws IOException {
        return (int) (readReg(REG_IFCNT) & 0xFFL)
    }

    /** @return {@code IOIN} raw word, {@code SEL_A} and {@code VERSION} */
    Ioin readIoin() throws IOException {
        long raw = readReg(REG_IOIN)
        return new Ioin(raw, (raw & (1L << 8)) != 0L, (int) ((raw >> 24) & 0xFFL))
    }

    /** @return {@code OTP_READ} 24-bit word */
    long readOtp() throws IOException {
        return readReg(REG_OTP_READ) & 0xFFFFFFL
    }

    /** @return {@code PWM_SCALE} / {@code PWM_AUTO} results */
    PwmAuto readPwmAuto() throws IOException {
        long scale = readReg(REG_PWM_SCALE)
        long auto = readReg(REG_PWM_AUTO)
        return new PwmAuto((int) (scale & 0xFFL), signed(scale >> 16, 9), (int) (auto & 0xFFL), (int) ((auto >> 16) & 0xFFL))
    }
}
