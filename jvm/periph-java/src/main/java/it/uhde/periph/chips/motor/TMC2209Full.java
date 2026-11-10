package it.uhde.periph.chips.motor;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.OutputPin;

import java.io.IOException;

/**
 * TMC2209 full interface — extends {@link TMC2209Minimal} with raw register
 * access, independent hold current, microstep and chopper configuration,
 * StealthChop tuning, GPIO STEP/DIR pulsing and every status read-back.
 */
public class TMC2209Full extends TMC2209Minimal {

    /**
     * Decoded {@code DRV_STATUS}.
     *
     * @param stst        standstill
     * @param stealth     StealthChop active (false = SpreadCycle)
     * @param csActual    actual current scale 0–31
     * @param otpw        over-temperature pre-warning
     * @param ot          over-temperature shutdown
     * @param shortGnd    short to GND on either phase
     * @param shortSupply low-side short on either phase
     * @param openLoad    open load on either phase
     * @param t120        120 °C comparator
     * @param t143        143 °C comparator
     * @param t150        150 °C comparator
     * @param t157        157 °C comparator
     */
    public record Status(boolean stst, boolean stealth, int csActual, boolean otpw, boolean ot,
                         boolean shortGnd, boolean shortSupply, boolean openLoad,
                         boolean t120, boolean t143, boolean t150, boolean t157) {}

    /**
     * Decoded {@code GSTAT}.
     *
     * @param reset  IC was reset since the last clear
     * @param drvErr driver shut down by over-temperature or short
     * @param uvCp   charge-pump undervoltage
     */
    public record GlobalStatus(boolean reset, boolean drvErr, boolean uvCp) {}

    /**
     * Decoded {@code IOIN} identity fields.
     *
     * @param raw     raw 32-bit word
     * @param enn     state of {@code ENN}
     * @param ms1     state of {@code MS1} (address bit 0)
     * @param ms2     state of {@code MS2} (address bit 1)
     * @param diag    state of {@code DIAG}
     * @param pdnUart state of {@code PDN_UART}
     * @param step    state of {@code STEP}
     * @param spread  state of the {@code SPREAD} pin
     * @param dir     state of {@code DIR}
     * @param version silicon version ({@code 0x21})
     */
    public record Ioin(long raw, boolean enn, boolean ms1, boolean ms2, boolean diag, boolean pdnUart,
                       boolean step, boolean spread, boolean dir, int version) {}

    /**
     * StealthChop auto-tuning results.
     *
     * @param pwmScaleSum  {@code PWM_SCALE_SUM}
     * @param pwmScaleAuto {@code PWM_SCALE_AUTO} (signed 9 bit)
     * @param pwmOfsAuto   {@code PWM_OFS_AUTO}
     * @param pwmGradAuto  {@code PWM_GRAD_AUTO}
     */
    public record PwmAuto(int pwmScaleSum, int pwmScaleAuto, int pwmOfsAuto, int pwmGradAuto) {}

    /**
     * The two signed 9-bit {@code MSCURACT} values in register order.
     *
     * @param low  bits 8:0
     * @param high bits 24:16
     */
    public record CoilCurrents(int low, int high) {}

    private final OutputPin stepPin;
    private final OutputPin dirPin;
    private final InputPin diagPin;

    /**
     * Construct the driver, probe the chip and apply the default configuration.
     *
     * @param connection UART connection to {@code PDN_UART}
     * @param rSenseOhm  external sense resistor in ohm
     * @param address    UART node address 0–3
     * @param enPin      optional {@code ENN} output (active low), may be {@code null}
     * @param echo       true when TX and RX share {@code PDN_UART}
     * @param stepPin    optional {@code STEP} output, may be {@code null}
     * @param dirPin     optional {@code DIR} output, may be {@code null}
     * @param diagPin    optional {@code DIAG} input (stall / driver error), may be {@code null}
     * @param multiNode  true when several chips share the UART; init then writes
     *                   {@code NODECONF.SENDDELAY = 2} first
     * @param fclkHz     chip clock in Hz (12&nbsp;MHz internal)
     * @throws IOException              if the chip does not answer or fails verification
     * @throws IllegalArgumentException if {@code address} is not 0–3
     */
    public TMC2209Full(Connection connection, double rSenseOhm, int address, OutputPin enPin, boolean echo,
                       OutputPin stepPin, OutputPin dirPin, InputPin diagPin, boolean multiNode, long fclkHz)
            throws IOException {
        super(connection, rSenseOhm, address, enPin, echo, fclkHz, multiNode);
        this.stepPin = stepPin;
        this.dirPin = dirPin;
        this.diagPin = diagPin;
    }

    /** Construct with defaults: 0.11 ohm, address 0, no pins, echo on, 12 MHz. */
    public TMC2209Full(Connection connection) throws IOException {
        this(connection, 0.11, 0, null, true, null, null, null, false, 12_000_000L);
    }

    private static boolean readable(int r) {
        return switch (r) {
            case 0x00, 0x01, 0x02, 0x05, 0x06, 0x07, 0x12, 0x41, 0x6A, 0x6B, 0x6C, 0x6F, 0x70, 0x71, 0x72 -> true;
            default -> false;
        };
    }

    private static boolean writable(int r) {
        return switch (r) {
            case 0x00, 0x01, 0x03, 0x10, 0x11, 0x13, 0x14, 0x22, 0x40, 0x42, 0x6C, 0x70 -> true;
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
     * @throws IOException              on UART error
     * @throws IllegalArgumentException for write-only or reserved registers
     */
    public long readRegister(int reg) throws IOException {
        if (!readable(reg)) throw new IllegalArgumentException(String.format("register 0x%02X is not readable", reg));
        return readReg(reg);
    }

    /**
     * @param reg   writable register address
     * @param value 32-bit value
     * @throws IOException              on UART error
     * @throws IllegalArgumentException for read-only registers, {@code OTP_PROG} and {@code FACTORY_CONF}
     */
    public void writeRegister(int reg, long value) throws IOException {
        if (!writable(reg)) throw new IllegalArgumentException(String.format("register 0x%02X is not writable", reg));
        writeReg(reg, value);
    }

    /**
     * @param rmsA hold current in A RMS
     * @throws IOException on UART error
     */
    public void setHoldCurrent(double rmsA) throws IOException {
        holdCs = currentToCs(rmsA);
        writeIholdIrun();
    }

    /**
     * @param iholdDelay {@code IHOLDDELAY} 0–15
     * @param tpowerdown {@code TPOWERDOWN} 0–255
     * @throws IOException              on UART error
     * @throws IllegalArgumentException if out of range
     */
    public void setCurrentDelay(int iholdDelay, int tpowerdown) throws IOException {
        if (iholdDelay < 0 || iholdDelay > 15 || tpowerdown < 0 || tpowerdown > 255) {
            throw new IllegalArgumentException("ihold_delay 0-15 / tpowerdown 0-255");
        }
        this.iholdDelay = iholdDelay;
        writeIholdIrun();
        writeReg(REG_TPOWERDOWN, tpowerdown);
    }

    /**
     * @param n microstep resolution: 1, 2, 4, 8, 16, 32, 64, 128 or 256
     * @throws IOException              on UART error
     * @throws IllegalArgumentException for any other value
     */
    public void setMicrosteps(int n) throws IOException {
        int mres = switch (n) {
            case 256 -> 0;
            case 128 -> 1;
            case 64 -> 2;
            case 32 -> 3;
            case 16 -> 4;
            case 8 -> 5;
            case 4 -> 6;
            case 2 -> 7;
            case 1 -> 8;
            default -> throw new IllegalArgumentException("microsteps must be a power of two, 1-256");
        };
        modify(REG_CHOPCONF, 0xFL << 24, (long) mres << 24);
        microsteps = n;
    }

    /**
     * @param enabled {@code CHOPCONF.intpol}
     * @throws IOException on UART error
     */
    public void setInterpolation(boolean enabled) throws IOException {
        modify(REG_CHOPCONF, 1L << 28, enabled ? 1L << 28 : 0);
    }

    /**
     * @param spreadcycle true = SpreadCycle, false = StealthChop
     * @throws IOException on UART error
     */
    public void setChopperMode(boolean spreadcycle) throws IOException {
        modify(REG_GCONF, GCONF_EN_SPREADCYCLE, spreadcycle ? GCONF_EN_SPREADCYCLE : 0);
    }

    /**
     * @param tpwmthrs {@code TPWMTHRS} (20 bit); 0 disables the threshold
     * @throws IOException on UART error
     */
    public void setStealthchopThreshold(long tpwmthrs) throws IOException {
        writeReg(REG_TPWMTHRS, tpwmthrs & 0xFFFFFL);
    }

    /**
     * @param toff  off time 0–15 (0 disables the driver)
     * @param tbl   blank time select 0–3
     * @param hstrt hysteresis start 0–7
     * @param hend  raw hysteresis end field 0–15 (offset −3)
     * @throws IOException on UART error
     */
    public void configureChopper(int toff, int tbl, int hstrt, int hend) throws IOException {
        long mask = 0xFL | (3L << 15) | (7L << 4) | (0xFL << 7);
        long value = (toff & 0xFL) | ((tbl & 3L) << 15) | ((hstrt & 7L) << 4) | ((hend & 0xFL) << 7);
        if (toff != 0) this.toff = toff & 0xF;
        modify(REG_CHOPCONF, mask, value);
    }

    /**
     * Write the StealthChop fields of {@code PWMCONF}.
     *
     * @param autoscale automatic current control
     * @param autograd  automatic gradient tuning
     * @param pwmOfs    amplitude offset 0–255
     * @param pwmGrad   amplitude gradient 0–255
     * @param pwmFreq   PWM frequency select 0–3
     * @param pwmReg    regulation gradient 1–15
     * @param pwmLim    amplitude limit 0–15
     * @throws IOException on UART error
     */
    public void configureStealthchop(boolean autoscale, boolean autograd, int pwmOfs, int pwmGrad,
                                     int pwmFreq, int pwmReg, int pwmLim) throws IOException {
        long mask = (0xFL << 28) | (0xFL << 24) | (1L << 19) | (1L << 18) | (3L << 16) | (0xFFL << 8) | 0xFFL;
        long value = ((pwmLim & 0xFL) << 28) | ((pwmReg & 0xFL) << 24) | (autograd ? 1L << 19 : 0)
                | (autoscale ? 1L << 18 : 0) | ((pwmFreq & 3L) << 16) | ((pwmGrad & 0xFFL) << 8) | (pwmOfs & 0xFFL);
        modify(REG_PWMCONF, mask, value);
    }

    /**
     * @param mode {@code PWMCONF.freewheel} 0–3
     * @throws IOException              on UART error
     * @throws IllegalArgumentException if out of range
     */
    public void setFreewheel(int mode) throws IOException {
        if (mode < 0 || mode > 3) throw new IllegalArgumentException("freewheel mode 0-3");
        modify(REG_PWMCONF, 3L << 20, (long) mode << 20);
    }

    /**
     * @param highSensitivity true for the low 0.18&nbsp;V range (changes the current conversion)
     * @throws IOException on UART error
     */
    public void setVsense(boolean highSensitivity) throws IOException {
        modify(REG_CHOPCONF, 1L << 17, highSensitivity ? 1L << 17 : 0);
        vsense = highSensitivity;
    }

    /**
     * @param enabled {@code GCONF.internal_Rsense}; {@link #setCurrent} then throws
     * @throws IOException on UART error
     */
    public void setInternalRsense(boolean enabled) throws IOException {
        modify(REG_GCONF, GCONF_INTERNAL_RSENSE, enabled ? GCONF_INTERNAL_RSENSE : 0);
        internalRsense = enabled;
    }

    /**
     * @param enabled {@code GCONF.I_scale_analog}
     * @throws IOException on UART error
     */
    public void setAnalogScaling(boolean enabled) throws IOException {
        modify(REG_GCONF, GCONF_I_SCALE_ANALOG, enabled ? GCONF_I_SCALE_ANALOG : 0);
    }

    /**
     * @param inverted {@code GCONF.shaft}
     * @throws IOException on UART error
     */
    public void setDirectionInverted(boolean inverted) throws IOException {
        modify(REG_GCONF, GCONF_SHAFT, inverted ? GCONF_SHAFT : 0);
    }

    /**
     * @param mode 0 first microstep, 1 {@code otpw}, 2 step pulses
     * @throws IOException              on UART error
     * @throws IllegalArgumentException if out of range
     */
    public void setIndexOutput(int mode) throws IOException {
        long bits = switch (mode) {
            case 0 -> 0;
            case 1 -> GCONF_INDEX_OTPW;
            case 2 -> GCONF_INDEX_STEP;
            default -> throw new IllegalArgumentException("index mode 0-2");
        };
        modify(REG_GCONF, GCONF_INDEX_OTPW | GCONF_INDEX_STEP, bits);
    }

    /**
     * @param shortLs  keep the low-side short protection
     * @param shortGnd keep the short-to-GND protection
     * @throws IOException on UART error
     */
    public void setDriverProtection(boolean shortLs, boolean shortGnd) throws IOException {
        long value = (shortLs ? 0 : 1L << 31) | (shortGnd ? 0 : 1L << 30);
        modify(REG_CHOPCONF, (1L << 31) | (1L << 30), value);
    }

    /**
     * @param enabled {@code CHOPCONF.dedge}
     * @throws IOException on UART error
     */
    public void setDoubleEdge(boolean enabled) throws IOException {
        modify(REG_CHOPCONF, 1L << 29, enabled ? 1L << 29 : 0);
    }

    /**
     * @param n {@code NODECONF.SENDDELAY} 0–15
     * @throws IOException              on UART error
     * @throws IllegalArgumentException if out of range
     */
    public void setSendDelay(int n) throws IOException {
        if (n < 0 || n > 15) throw new IllegalArgumentException("send delay 0-15");
        writeReg(REG_NODECONF, (long) n << 8);
    }

    /**
     * Set the StallGuard4 threshold ({@code SGTHRS}); a stall is signalled when
     * {@code SG_RESULT <= 2 * sgthrs}.
     *
     * @param sgthrs 0–255, higher is more sensitive
     * @throws IOException              on UART error
     * @throws IllegalArgumentException if out of range
     */
    public void setStallThreshold(int sgthrs) throws IOException {
        if (sgthrs < 0 || sgthrs > 255) throw new IllegalArgumentException("sgthrs 0-255");
        writeReg(REG_SGTHRS, sgthrs);
    }

    /**
     * Set the lower velocity limit for CoolStep and the {@code DIAG} stall output.
     *
     * @param tcoolthrs {@code TCOOLTHRS} (20 bit); 0 disables
     * @throws IOException              on UART error
     * @throws IllegalArgumentException if out of range
     */
    public void setCoolstepThreshold(long tcoolthrs) throws IOException {
        if (tcoolthrs < 0 || tcoolthrs > 0xFFFFF) throw new IllegalArgumentException("tcoolthrs 0-1048575");
        writeReg(REG_TCOOLTHRS, tcoolthrs);
    }

    /**
     * Write {@code COOLCONF}.
     *
     * @param semin  lower threshold 0–15; 0 disables CoolStep
     * @param semax  upper threshold offset 0–15
     * @param seup   current up step width 0–3 (1, 2, 4, 8)
     * @param sedn   current down step speed 0–3
     * @param seimin 0 for half IRUN minimum, 1 for quarter
     * @throws IOException              on UART error
     * @throws IllegalArgumentException if a field is out of range
     */
    public void configureCoolstep(int semin, int semax, int seup, int sedn, int seimin) throws IOException {
        if (semin < 0 || semin > 15 || semax < 0 || semax > 15 || seup < 0 || seup > 3
                || sedn < 0 || sedn > 3 || seimin < 0 || seimin > 1) {
            throw new IllegalArgumentException("COOLCONF field out of range");
        }
        writeReg(REG_COOLCONF, semin | ((long) seup << 5) | ((long) semax << 8) | ((long) sedn << 13)
                | ((long) seimin << 15));
    }

    /**
     * @return {@code SG_RESULT} 0–510 (higher means less load)
     * @throws IOException on UART error
     */
    public int readStallguard() throws IOException {
        return (int) (readReg(REG_SG_RESULT) & 0x3FF);
    }

    /**
     * @return {@code DIAG} pin level (stall or driver error)
     * @throws IOException           on pin error
     * @throws IllegalStateException if no DIAG pin was given
     */
    public boolean isStalled() throws IOException {
        if (diagPin == null) throw new IllegalStateException("isStalled() requires a DIAG pin");
        return diagPin.isHigh();
    }

    /**
     * Emit STEP pulses on the GPIO pin.
     *
     * @param count  number of pulses; negative reverses (needs a DIR pin)
     * @param delayS high and low time of each pulse in seconds
     * @throws IOException           on UART error
     * @throws IllegalStateException if no STEP pin (or no DIR pin for a negative count) was given
     */
    public void step(int count, double delayS) throws IOException {
        if (stepPin == null) throw new IllegalStateException("step() requires a STEP pin");
        if (count < 0 && dirPin == null) throw new IllegalStateException("negative count requires a DIR pin");
        if (dirPin != null) dirPin.set(count >= 0);
        long nanos = (long) (delayS * 1e9);
        for (int i = 0; i < Math.abs(count); i++) {
            stepPin.set(true);
            sleepNanos(nanos);
            stepPin.set(false);
            sleepNanos(nanos);
        }
    }

    private static void sleepNanos(long nanos) {
        try {
            Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @return {@code MSCNT} 0–1023
     * @throws IOException on UART error
     */
    public int microstepPosition() throws IOException {
        return (int) (readReg(REG_MSCNT) & 0x3FF);
    }

    /**
     * @return {@code DRV_STATUS.CS_ACTUAL} 0–31
     * @throws IOException on UART error
     */
    public int readCurrentScale() throws IOException {
        return (int) ((readReg(REG_DRV_STATUS) >> 16) & 0x1F);
    }

    /**
     * @return signed {@code MSCURACT} values in register order
     * @throws IOException on UART error
     */
    public CoilCurrents readCoilCurrents() throws IOException {
        long raw = readReg(REG_MSCURACT);
        return new CoilCurrents(signed(raw, 9), signed(raw >> 16, 9));
    }

    /**
     * @return {@code TSTEP} in clock periods (20 bit)
     * @throws IOException on UART error
     */
    public long readTstep() throws IOException {
        return readReg(REG_TSTEP) & 0xFFFFFL;
    }

    /**
     * @return t120..t157 as bits 0–3
     * @throws IOException on UART error
     */
    public int readTemperatureFlags() throws IOException {
        return (int) ((readReg(REG_DRV_STATUS) >> 8) & 0xF);
    }

    /**
     * @return decoded {@code DRV_STATUS}
     * @throws IOException on UART error
     */
    public Status readStatus() throws IOException {
        long s = readReg(REG_DRV_STATUS);
        return new Status((s & (1L << 31)) != 0, (s & (1L << 30)) != 0, (int) ((s >> 16) & 0x1F),
                (s & 0x01) != 0, (s & 0x02) != 0, (s & 0x0C) != 0, (s & 0x30) != 0, (s & 0xC0) != 0,
                (s & (1L << 8)) != 0, (s & (1L << 9)) != 0, (s & (1L << 10)) != 0, (s & (1L << 11)) != 0);
    }

    /**
     * @param clear write the flags back (write-1-to-clear)
     * @return decoded {@code GSTAT}
     * @throws IOException on UART error
     */
    public GlobalStatus readGlobalStatus(boolean clear) throws IOException {
        long g = readReg(REG_GSTAT);
        if (clear) writeReg(REG_GSTAT, 0x07);
        return new GlobalStatus((g & 1) != 0, (g & 2) != 0, (g & 4) != 0);
    }

    /**
     * @return {@code IFCNT} write counter 0–255
     * @throws IOException on UART error
     */
    public int readIfcnt() throws IOException {
        return (int) (readReg(REG_IFCNT) & 0xFF);
    }

    /**
     * @return {@code IOIN} raw word, decoded pin states and {@code VERSION}
     * @throws IOException on UART error
     */
    public Ioin readIoin() throws IOException {
        long raw = readReg(REG_IOIN);
        return new Ioin(raw, (raw & 1) != 0, (raw & 4) != 0, (raw & 8) != 0, (raw & 0x10) != 0,
                (raw & 0x40) != 0, (raw & 0x80) != 0, (raw & 0x100) != 0, (raw & 0x200) != 0,
                (int) ((raw >> 24) & 0xFF));
    }

    /**
     * @return {@code OTP_READ} 24-bit word
     * @throws IOException on UART error
     */
    public long readOtp() throws IOException {
        return readReg(REG_OTP_READ) & 0xFFFFFFL;
    }

    /**
     * @return {@code PWM_SCALE} / {@code PWM_AUTO} results
     * @throws IOException on UART error
     */
    public PwmAuto readPwmAuto() throws IOException {
        long scale = readReg(REG_PWM_SCALE);
        long auto = readReg(REG_PWM_AUTO);
        return new PwmAuto((int) (scale & 0xFF), signed(scale >> 16, 9), (int) (auto & 0xFF), (int) ((auto >> 16) & 0xFF));
    }
}
