import math
import time

_REG_GCONF = 0x00
_REG_GSTAT = 0x01
_REG_IOIN = 0x04
_REG_IHOLD_IRUN = 0x10
_REG_TPOWERDOWN = 0x11
_REG_TSTEP = 0x12
_REG_TPWMTHRS = 0x13
_REG_TCOOLTHRS = 0x14
_REG_THIGH = 0x15
_REG_XDIRECT = 0x2D
_REG_VDCMIN = 0x33
_REG_MSLUT0 = 0x60
_REG_MSLUTSEL = 0x68
_REG_MSLUTSTART = 0x69
_REG_MSCNT = 0x6A
_REG_MSCURACT = 0x6B
_REG_CHOPCONF = 0x6C
_REG_COOLCONF = 0x6D
_REG_DCCTRL = 0x6E
_REG_DRV_STATUS = 0x6F
_REG_PWMCONF = 0x70
_REG_PWM_SCALE = 0x71
_REG_LOST_STEPS = 0x73

# Registers the chip lets the host read back.
_READABLE = (0x00, 0x01, 0x04, 0x12, 0x2D, 0x6A, 0x6B, 0x6C, 0x6F, 0x71, 0x73)
# Registers the driver may write through write_register().
_WRITABLE = (0x00, 0x10, 0x11, 0x13, 0x14, 0x15, 0x2D, 0x33, 0x60, 0x61, 0x62,
             0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6C, 0x6D, 0x6E, 0x70)

_V_FS_LOW_SENSE = 0.325
_V_FS_HIGH_SENSE = 0.180
_R_SENSE_PARASITIC = 0.020

_GCONF_I_SCALE_ANALOG = 1 << 0
_GCONF_INTERNAL_RSENSE = 1 << 1
_GCONF_EN_PWM_MODE = 1 << 2
_GCONF_SHAFT = 1 << 4
_GCONF_SMALL_HYSTERESIS = 1 << 14
_GCONF_STOP_ENABLE = 1 << 15
_GCONF_DIRECT_MODE = 1 << 16

_VERSION = 0x11
_CHOPCONF_DEFAULT = 0x140100C3
_PWMCONF_DEFAULT = 0x000401C8


def _sleep_ms(ms):
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(ms)
    else:
        time.sleep(ms / 1000.0)


def _sleep_s(seconds):
    if seconds <= 0:
        return
    if hasattr(time, 'sleep_us'):
        time.sleep_us(int(seconds * 1000000))
    else:
        time.sleep(seconds)


def _to_signed(value, bits):
    value &= (1 << bits) - 1
    if value & (1 << (bits - 1)):
        value -= 1 << bits
    return value


class TMC2130Minimal:
    """Trinamic TMC2130 stepper driver over SPI.

    Brings the driver up in SPI mode (internal reference, StealthChop below
    ``TPWMTHRS = 500`` and SpreadCycle above it, 16 microsteps with 256-step
    interpolation, 0.5 A RMS run current) and moves the motor with STEP/DIR
    pulses -- the chip has no internal step generator.

    The 40-bit datagram framing and the pipelined read protocol are handled
    here; the connection only needs plain ``write`` / ``write_read``
    (SPI Mode 3, MSB first).

    Args:
        connection: SPI connection configured for Mode 3 (CPOL=1, CPHA=1).
        step_pin: OutputPin wired to STEP.
        dir_pin: OutputPin wired to DIR.
        r_sense_ohm: External sense resistor value in ohm (0.11 on most
            breakout boards).
        en_pin: Optional OutputPin wired to ENN (active low). When given the
            driver stays disabled after construction until enable().

    Raises:
        OSError: If the chip does not answer, reports an unexpected identity
            or a write is not mirrored back.
    """

    FAULT_OVERTEMP_WARNING = 0x01
    FAULT_OVERTEMP = 0x02
    FAULT_SHORT_GND = 0x04
    FAULT_OPEN_LOAD = 0x10

    _fclk_hz = 12000000
    _auto_vsense = True

    def __init__(self, connection, step_pin, dir_pin, r_sense_ohm=0.11, en_pin=None):
        self._conn = connection
        self._step_pin = step_pin
        self._dir_pin = dir_pin
        self._r_sense = r_sense_ohm
        self._en_pin = en_pin
        self._shadow = {}
        self._microsteps = 16
        self._vsense = False
        self._internal_rsense = False
        self._toff = 3
        self._run_cs = 0
        self._hold_cs = 0
        self._ihold_delay = 6
        self._initialise()

    # ---- datagram layer -------------------------------------------------

    def _write_reg(self, reg, value, verify=False):
        value &= 0xFFFFFFFF
        self._conn.write(bytes([(reg | 0x80) & 0xFF,
                                (value >> 24) & 0xFF, (value >> 16) & 0xFF,
                                (value >> 8) & 0xFF, value & 0xFF]))
        self._shadow[reg] = value
        if verify:
            mirror = self._second_datagram()
            if mirror != value:
                raise OSError('TMC2130: write mirror mismatch (reg 0x%02X)' % reg)

    def _second_datagram(self):
        # A harmless GCONF read request; its reply is the previous datagram's
        # answer (pipelined protocol). Never GSTAT, which clears on read.
        reply = bytes(self._conn.write_read(bytes([_REG_GCONF]), 4))
        if len(reply) != 4:
            raise OSError('TMC2130: short read')
        return (reply[0] << 24) | (reply[1] << 16) | (reply[2] << 8) | reply[3]

    def _read_reg(self, reg):
        self._conn.write_read(bytes([reg & 0x7F]), 4)
        return self._second_datagram()

    def _modify(self, reg, mask, value):
        current = self._shadow.get(reg, 0)
        self._write_reg(reg, (current & ~mask) | (value & mask))

    # ---- initialisation -------------------------------------------------

    def _initialise(self):
        if self._en_pin is not None:
            self._en_pin.set(True)
        _sleep_ms(10)
        ioin = self._read_reg(_REG_IOIN)
        if ((ioin >> 24) & 0xFF) != _VERSION or not ioin & (1 << 6):
            raise OSError('TMC2130 not found (IOIN=0x%08X)' % ioin)
        self._read_reg(_REG_GSTAT)
        self._run_cs, vsense = self._current_to_cs(0.5)
        self._hold_cs = self._run_cs // 3
        self._vsense = vsense
        chop = _CHOPCONF_DEFAULT | ((1 << 17) if vsense else 0)
        self._write_reg(_REG_CHOPCONF, chop, True)
        self._write_ihold_irun(True)
        self._write_reg(_REG_TPOWERDOWN, 10, True)
        self._write_reg(_REG_GCONF, _GCONF_EN_PWM_MODE, True)
        self._write_reg(_REG_TPWMTHRS, 500, True)
        self._write_reg(_REG_PWMCONF, _PWMCONF_DEFAULT, True)
        if (self._read_reg(_REG_GCONF) != self._shadow[_REG_GCONF]
                or self._read_reg(_REG_CHOPCONF) != self._shadow[_REG_CHOPCONF]):
            raise OSError('TMC2130: GCONF/CHOPCONF readback mismatch')

    def _write_ihold_irun(self, verify=False):
        value = (self._hold_cs & 0x1F) | ((self._run_cs & 0x1F) << 8) \
            | ((self._ihold_delay & 0xF) << 16)
        self._write_reg(_REG_IHOLD_IRUN, value, verify)

    # ---- conversions ----------------------------------------------------

    def _cs_for(self, rms_a, vsense):
        v_fs = _V_FS_HIGH_SENSE if vsense else _V_FS_LOW_SENSE
        cs = int(round(rms_a * math.sqrt(2) * (self._r_sense + _R_SENSE_PARASITIC)
                       * 32 / v_fs)) - 1
        return max(0, min(31, cs))

    def _current_to_cs(self, rms_a, vsense=None):
        """Return (cs, vsense); selects vsense automatically when not given."""
        if self._internal_rsense:
            raise ValueError('set_current is unsupported with internal Rsense')
        if vsense is not None:
            return self._cs_for(rms_a, vsense), vsense
        if not self._auto_vsense:
            return self._cs_for(rms_a, self._vsense), self._vsense
        cs = self._cs_for(rms_a, False)
        if cs < 16:
            high = self._cs_for(rms_a, True)
            if high <= 31:
                return high, True
        return cs, False

    # ---- public API -----------------------------------------------------

    def enable(self):
        """Switch the power stage on and wait 100 ms for StealthChop to settle."""
        if self._en_pin is not None:
            self._en_pin.set(False)
        else:
            chop = self._shadow[_REG_CHOPCONF]
            self._write_reg(_REG_CHOPCONF, (chop & ~0xF) | self._toff)
        _sleep_ms(100)

    def disable(self):
        """Switch the power stage off; the motor freewheels."""
        if self._en_pin is not None:
            self._en_pin.set(True)
        else:
            chop = self._shadow[_REG_CHOPCONF]
            self._toff = chop & 0xF or self._toff
            self._write_reg(_REG_CHOPCONF, chop & ~0xF)

    def set_current(self, rms_a):
        """Set the run current; hold current becomes one third of it.

        Selects the sense-voltage range (CHOPCONF.vsense) automatically
        unless set_vsense() was called.

        Args:
            rms_a: Run current in A RMS (at most 1.4), quantised to the
                nearest scale step.

        Raises:
            ValueError: If rms_a is above 1.4 or the driver uses the
                internal sense resistors.
        """
        if rms_a > 1.4:
            raise ValueError('rms_a must be at most 1.4 A')
        cs, vsense = self._current_to_cs(rms_a)
        self._run_cs = cs
        self._hold_cs = cs // 3
        if vsense != self._vsense:
            self._vsense = vsense
            self._modify(_REG_CHOPCONF, 1 << 17, (1 << 17) if vsense else 0)
        self._write_ihold_irun()

    def move(self, full_steps, full_steps_per_s):
        """Move a number of full steps (blocking, software-timed).

        Args:
            full_steps: Full steps to move; the sign sets the direction.
            full_steps_per_s: Speed in full steps per second (> 0).

        Raises:
            ValueError: If full_steps_per_s is not positive.
        """
        if full_steps_per_s <= 0:
            raise ValueError('full_steps_per_s must be > 0')
        pulses = abs(int(full_steps)) * self._microsteps
        half = 1.0 / (2.0 * full_steps_per_s * self._microsteps)
        self._dir_pin.set(full_steps >= 0)
        for _ in range(pulses):
            self._step_pin.set(True)
            _sleep_s(half)
            self._step_pin.set(False)
            _sleep_s(half)

    def is_standstill(self):
        """Return True when the motor is at standstill (DRV_STATUS.stst).

        Returns:
            bool: Standstill flag.
        """
        return bool(self._read_reg(_REG_DRV_STATUS) & (1 << 31))

    def read_fault(self):
        """Read the driver fault flags.

        Returns:
            int: Bitmask of FAULT_* constants, 0 when no fault is present.
        """
        status = self._read_reg(_REG_DRV_STATUS)
        fault = 0
        if status & (1 << 26):
            fault |= self.FAULT_OVERTEMP_WARNING
        if status & (1 << 25):
            fault |= self.FAULT_OVERTEMP
        if status & (3 << 27):
            fault |= self.FAULT_SHORT_GND
        if status & (3 << 29):
            fault |= self.FAULT_OPEN_LOAD
        return fault


class TMC2130Full(TMC2130Minimal):
    """Complete TMC2130 driver: every configurable feature.

    Adds raw register access, independent hold current, microstep, chopper
    and StealthChop tuning, StallGuard2 / CoolStep, DIAG routing, DcStep,
    direct coil-current mode, the microstep table and all status read-backs.

    Args:
        connection: SPI connection configured for Mode 3.
        step_pin: OutputPin wired to STEP.
        dir_pin: OutputPin wired to DIR.
        r_sense_ohm: External sense resistor value in ohm.
        en_pin: Optional OutputPin wired to ENN (active low).
        diag0_pin: Optional InputPin wired to DIAG0.
        diag1_pin: Optional InputPin wired to DIAG1.
        fclk_hz: Chip clock in Hz (12 MHz internal; the external clock
            frequency if CLK is driven).
    """

    def __init__(self, connection, step_pin, dir_pin, r_sense_ohm=0.11,
                 en_pin=None, diag0_pin=None, diag1_pin=None, fclk_hz=12000000):
        self._diag_pins = (diag0_pin, diag1_pin)
        self._fclk_hz = fclk_hz
        super().__init__(connection, step_pin, dir_pin, r_sense_ohm, en_pin)

    def read_register(self, reg):
        """Read a raw 32-bit register (pipelined two-datagram read).

        Args:
            reg: Register address of a readable register.

        Returns:
            int: Raw register value.

        Raises:
            ValueError: If the register is write-only or reserved.
        """
        if reg not in _READABLE:
            raise ValueError('register 0x%02X is not readable' % reg)
        return self._read_reg(reg)

    def write_register(self, reg, value, verify=False):
        """Write a raw 32-bit register.

        Args:
            reg: Register address of a writable register.
            value: 32-bit value.
            verify: True to also check the mirror of the write.

        Raises:
            ValueError: If the register is read-only or reserved.
            OSError: If verify is set and the mirror does not match.
        """
        if reg not in _WRITABLE:
            raise ValueError('register 0x%02X is not writable' % reg)
        self._write_reg(reg, value, verify)

    def set_hold_current(self, rms_a):
        """Set the standstill hold current independently of the run current.

        Args:
            rms_a: Hold current in A RMS.
        """
        self._hold_cs, _ = self._current_to_cs(rms_a, self._vsense)
        self._write_ihold_irun()

    def set_current_delay(self, ihold_delay, tpowerdown):
        """Set the power-down timing.

        Args:
            ihold_delay: IHOLDDELAY 0-15 (units of 2^18 clocks per step).
            tpowerdown: TPOWERDOWN 0-255 (units of 2^18 clocks).

        Raises:
            ValueError: If an argument is out of range.
        """
        if not 0 <= ihold_delay <= 15 or not 0 <= tpowerdown <= 255:
            raise ValueError('ihold_delay 0-15 / tpowerdown 0-255')
        self._ihold_delay = ihold_delay
        self._write_ihold_irun()
        self._write_reg(_REG_TPOWERDOWN, tpowerdown)

    def set_microsteps(self, n):
        """Select the microstep resolution.

        Args:
            n: 1, 2, 4, 8, 16, 32, 64, 128 or 256.

        Raises:
            ValueError: For any other value.
        """
        if n not in (1, 2, 4, 8, 16, 32, 64, 128, 256):
            raise ValueError('microsteps must be a power of two, 1-256')
        mres = 8 - (n.bit_length() - 1)
        self._modify(_REG_CHOPCONF, 0xF << 24, mres << 24)
        self._microsteps = n

    def set_interpolation(self, enabled):
        """Enable 256-microstep interpolation (CHOPCONF.intpol).

        Args:
            enabled: True to interpolate.
        """
        self._modify(_REG_CHOPCONF, 1 << 28, (1 << 28) if enabled else 0)

    def set_chopper_mode(self, spreadcycle):
        """Choose SpreadCycle (True) or StealthChop (False); switch at standstill.

        Args:
            spreadcycle: True for SpreadCycle only.
        """
        self._modify(_REG_GCONF, _GCONF_EN_PWM_MODE,
                     0 if spreadcycle else _GCONF_EN_PWM_MODE)

    def set_stealthchop_threshold(self, tpwmthrs):
        """Set the StealthChop upper velocity threshold.

        Args:
            tpwmthrs: TPWMTHRS (20 bit); 0 disables the threshold.

        Raises:
            ValueError: If out of range.
        """
        if not 0 <= tpwmthrs <= 0xFFFFF:
            raise ValueError('tpwmthrs 0-1048575')
        self._write_reg(_REG_TPWMTHRS, tpwmthrs)

    def set_high_velocity_threshold(self, thigh):
        """Set the THIGH velocity threshold.

        Args:
            thigh: THIGH (20 bit).

        Raises:
            ValueError: If out of range.
        """
        if not 0 <= thigh <= 0xFFFFF:
            raise ValueError('thigh 0-1048575')
        self._write_reg(_REG_THIGH, thigh)

    def configure_chopper(self, toff, tbl, hstrt, hend):
        """Write the SpreadCycle (chm = 0) fields of CHOPCONF.

        Args:
            toff: Off time 0-15 (0 disables the driver).
            tbl: Blank time select 0-3.
            hstrt: Hysteresis start 0-7.
            hend: Raw hysteresis end field 0-15 (offset -3).

        Raises:
            ValueError: If a field is out of range.
        """
        if not (0 <= toff <= 15 and 0 <= tbl <= 3 and 0 <= hstrt <= 7 and 0 <= hend <= 15):
            raise ValueError('chopper field out of range')
        mask = 0xF | (3 << 15) | (7 << 4) | (0xF << 7) | (1 << 14) | (1 << 11)
        value = toff | (tbl << 15) | (hstrt << 4) | (hend << 7)
        self._toff = toff or self._toff
        self._modify(_REG_CHOPCONF, mask, value)

    def configure_constant_off_time(self, toff, tbl, tfd, offset, disfdcc, rndtf):
        """Write the constant-off-time (chm = 1) fields of CHOPCONF.

        Args:
            toff: Off time 0-15 (0 disables the driver).
            tbl: Blank time select 0-3.
            tfd: Fast decay time 0-15.
            offset: Sine wave offset -3..12.
            disfdcc: True to disable current-comparator fast-decay termination.
            rndtf: True for random off-time modulation.

        Raises:
            ValueError: If a field is out of range.
        """
        if not (0 <= toff <= 15 and 0 <= tbl <= 3 and 0 <= tfd <= 15 and -3 <= offset <= 12):
            raise ValueError('chopper field out of range')
        mask = 0xF | (3 << 15) | (7 << 4) | (0xF << 7) | (1 << 14) | (1 << 13) \
            | (1 << 12) | (1 << 11)
        value = toff | (tbl << 15) | ((tfd & 7) << 4) | ((offset + 3) << 7) | (1 << 14) \
            | ((1 << 13) if rndtf else 0) | ((1 << 12) if disfdcc else 0) \
            | (((tfd >> 3) & 1) << 11)
        self._toff = toff or self._toff
        self._modify(_REG_CHOPCONF, mask, value)

    def configure_high_velocity(self, vhighchm, vhighfs):
        """Select the behaviour above THIGH.

        Args:
            vhighchm: True to switch to chm = 1 above THIGH.
            vhighfs: True to switch to full-step operation above THIGH.
        """
        self._modify(_REG_CHOPCONF, (1 << 19) | (1 << 18),
                     ((1 << 19) if vhighchm else 0) | ((1 << 18) if vhighfs else 0))

    def set_chopper_sync(self, sync):
        """Set CHOPCONF.SYNC (chopSync2).

        Args:
            sync: 0-15; 0 disables.

        Raises:
            ValueError: If out of range.
        """
        if not 0 <= sync <= 15:
            raise ValueError('sync 0-15')
        self._modify(_REG_CHOPCONF, 0xF << 20, sync << 20)

    def configure_stealthchop(self, autoscale, symmetric, pwm_ampl, pwm_grad, pwm_freq):
        """Write the StealthChop fields of PWMCONF (freewheel is kept).

        Args:
            autoscale: Automatic current control.
            symmetric: Force symmetric PWM cycles.
            pwm_ampl: Amplitude 0-255.
            pwm_grad: Gradient 0-255.
            pwm_freq: PWM frequency select 0-3.

        Raises:
            ValueError: If a field is out of range.
        """
        if not (0 <= pwm_ampl <= 255 and 0 <= pwm_grad <= 255 and 0 <= pwm_freq <= 3):
            raise ValueError('stealthchop field out of range')
        mask = (1 << 19) | (1 << 18) | (3 << 16) | (0xFF << 8) | 0xFF
        value = ((1 << 19) if symmetric else 0) | ((1 << 18) if autoscale else 0) \
            | (pwm_freq << 16) | (pwm_grad << 8) | pwm_ampl
        self._modify(_REG_PWMCONF, mask, value)

    def set_freewheel(self, mode):
        """Select the standstill mode used when IHOLD is 0.

        Args:
            mode: 0 normal, 1 freewheel, 2 low-side short, 3 high-side short.

        Raises:
            ValueError: If mode is not 0-3.
        """
        if not 0 <= mode <= 3:
            raise ValueError('freewheel mode 0-3')
        self._modify(_REG_PWMCONF, 3 << 20, mode << 20)

    def set_vsense(self, high_sensitivity):
        """Select the sense voltage range and disable automatic selection.

        Args:
            high_sensitivity: True for the 0.18 V range.
        """
        self._modify(_REG_CHOPCONF, 1 << 17, (1 << 17) if high_sensitivity else 0)
        self._vsense = bool(high_sensitivity)
        self._auto_vsense = False

    def set_internal_rsense(self, enabled):
        """Use the internal sense resistors (set_current then raises).

        Args:
            enabled: True for internal Rsense.
        """
        self._modify(_REG_GCONF, _GCONF_INTERNAL_RSENSE,
                     _GCONF_INTERNAL_RSENSE if enabled else 0)
        self._internal_rsense = bool(enabled)

    def set_analog_scaling(self, enabled):
        """Take the current reference from AIN_IREF (True) or the internal one.

        Args:
            enabled: True for AIN_IREF scaling.
        """
        self._modify(_REG_GCONF, _GCONF_I_SCALE_ANALOG,
                     _GCONF_I_SCALE_ANALOG if enabled else 0)

    def set_direction_inverted(self, inverted):
        """Invert the motor direction (GCONF.shaft).

        Args:
            inverted: True to invert.
        """
        self._modify(_REG_GCONF, _GCONF_SHAFT, _GCONF_SHAFT if inverted else 0)

    def configure_diag(self, diag0_error=False, diag0_otpw=False, diag0_stall=False,
                       diag1_stall=False, diag1_index=False, diag1_onstate=False,
                       diag0_pushpull=False, diag1_pushpull=False):
        """Route events to the DIAG0 / DIAG1 pins (GCONF bits 5-13).

        Args:
            diag0_error: DIAG0 on driver error.
            diag0_otpw: DIAG0 on over-temperature pre-warning.
            diag0_stall: DIAG0 on stall.
            diag1_stall: DIAG1 on stall.
            diag1_index: DIAG1 on index position.
            diag1_onstate: DIAG1 while the chopper is on.
            diag0_pushpull: DIAG0 push-pull (active high) instead of open drain.
            diag1_pushpull: DIAG1 push-pull instead of open drain.
        """
        bits = [diag0_error, diag0_otpw, diag0_stall, diag1_stall, diag1_index,
                diag1_onstate]
        value = 0
        for i, flag in enumerate(bits):
            if flag:
                value |= 1 << (5 + i)
        if diag0_pushpull:
            value |= 1 << 12
        if diag1_pushpull:
            value |= 1 << 13
        self._modify(_REG_GCONF, 0x3FE0 & ~(1 << 11), value)

    def set_double_edge(self, enabled):
        """Step on both STEP edges (CHOPCONF.dedge).

        Args:
            enabled: True for both edges.
        """
        self._modify(_REG_CHOPCONF, 1 << 29, (1 << 29) if enabled else 0)

    def set_short_protection(self, short_gnd):
        """Enable or disable the short-to-GND protection.

        Args:
            short_gnd: True to keep the protection (diss2g = 0).
        """
        self._modify(_REG_CHOPCONF, 1 << 30, 0 if short_gnd else (1 << 30))

    def set_small_hysteresis(self, enabled):
        """Use 1/32 instead of 1/16 comparison hysteresis (GCONF.small_hysteresis).

        Args:
            enabled: True for 1/32.
        """
        self._modify(_REG_GCONF, _GCONF_SMALL_HYSTERESIS,
                     _GCONF_SMALL_HYSTERESIS if enabled else 0)

    def set_emergency_stop(self, enabled):
        """Let DCIN stop the sequencer (GCONF.stop_enable).

        Args:
            enabled: True to enable.
        """
        self._modify(_REG_GCONF, _GCONF_STOP_ENABLE,
                     _GCONF_STOP_ENABLE if enabled else 0)

    def set_stall_threshold(self, sgt):
        """Set the StallGuard2 threshold, keeping the other COOLCONF fields.

        Args:
            sgt: -64..63; lower is more sensitive.

        Raises:
            ValueError: If out of range.
        """
        if not -64 <= sgt <= 63:
            raise ValueError('sgt -64..63')
        self._modify(_REG_COOLCONF, 0x7F << 16, (sgt & 0x7F) << 16)

    def set_stallguard_filter(self, enabled):
        """Enable the StallGuard2 filter (COOLCONF.sfilt).

        Args:
            enabled: True to update once per four full steps.
        """
        self._modify(_REG_COOLCONF, 1 << 24, (1 << 24) if enabled else 0)

    def set_coolstep_threshold(self, tcoolthrs):
        """Set the lower velocity limit for CoolStep and the DIAG stall output.

        Args:
            tcoolthrs: TCOOLTHRS (20 bit); 0 disables.

        Raises:
            ValueError: If out of range.
        """
        if not 0 <= tcoolthrs <= 0xFFFFF:
            raise ValueError('tcoolthrs 0-1048575')
        self._write_reg(_REG_TCOOLTHRS, tcoolthrs)

    def configure_coolstep(self, semin, semax, seup, sedn, seimin):
        """Write the CoolStep fields of COOLCONF (sgt / sfilt are kept).

        Args:
            semin: Lower threshold 0-15; 0 disables CoolStep.
            semax: Upper threshold offset 0-15.
            seup: Current up step width 0-3 (1, 2, 4, 8).
            sedn: Current down step speed 0-3.
            seimin: 0 for half IRUN minimum, 1 for quarter.

        Raises:
            ValueError: If a field is out of range.
        """
        if (not 0 <= semin <= 15 or not 0 <= semax <= 15
                or not 0 <= seup <= 3 or not 0 <= sedn <= 3
                or seimin not in (0, 1, True, False)):
            raise ValueError('COOLCONF field out of range')
        mask = 0xF | (3 << 5) | (0xF << 8) | (3 << 13) | (1 << 15)
        value = semin | (seup << 5) | (semax << 8) | (sedn << 13) \
            | ((1 << 15) if seimin else 0)
        self._modify(_REG_COOLCONF, mask, value)

    def read_stallguard(self):
        """Return the StallGuard2 result.

        Returns:
            int: SG_RESULT 0-1023 (0 = highest load).
        """
        return self._read_reg(_REG_DRV_STATUS) & 0x3FF

    def is_stalled(self):
        """Return the DRV_STATUS stall flag.

        Returns:
            bool: True when a stall is detected.
        """
        return bool(self._read_reg(_REG_DRV_STATUS) & (1 << 24))

    def read_diag(self, n):
        """Return the level of a DIAG pin.

        Args:
            n: 0 for DIAG0, 1 for DIAG1.

        Returns:
            bool: True when the pin is high.

        Raises:
            ValueError: If n is not 0 or 1.
            RuntimeError: If that pin was not wired.
        """
        if n not in (0, 1):
            raise ValueError('n must be 0 or 1')
        pin = self._diag_pins[n]
        if pin is None:
            raise RuntimeError('read_diag() requires diag%d_pin' % n)
        return bool(pin.is_high())

    def step(self, count, delay_s):
        """Emit STEP pulses (no microstep conversion).

        Args:
            count: Number of pulses; negative reverses.
            delay_s: Time between edges in seconds.
        """
        self._dir_pin.set(count >= 0)
        for _ in range(abs(count)):
            self._step_pin.set(True)
            _sleep_s(delay_s)
            self._step_pin.set(False)
            _sleep_s(delay_s)

    def set_direct_mode(self, enabled):
        """Switch coil-current direct mode on or off (GCONF.direct_mode).

        Args:
            enabled: True to write coil currents via set_coil_currents().
        """
        self._modify(_REG_GCONF, _GCONF_DIRECT_MODE,
                     _GCONF_DIRECT_MODE if enabled else 0)

    def set_coil_currents(self, coil_a, coil_b):
        """Write XDIRECT (scaled by IHOLD).

        Args:
            coil_a: Signed coil A current -255..255.
            coil_b: Signed coil B current -255..255.

        Raises:
            RuntimeError: If direct mode is not enabled.
            ValueError: If a value is out of range.
        """
        if not self._shadow.get(_REG_GCONF, 0) & _GCONF_DIRECT_MODE:
            raise RuntimeError('set_coil_currents() requires direct mode')
        if not (-255 <= coil_a <= 255 and -255 <= coil_b <= 255):
            raise ValueError('coil currents -255..255')
        self._write_reg(_REG_XDIRECT, (coil_a & 0x1FF) | ((coil_b & 0x1FF) << 16))

    def set_dcstep(self, vdcmin, dc_time, dc_sg):
        """Configure DcStep.

        Args:
            vdcmin: VDCMIN 0-2^23-1; non-zero enables DcStep by velocity.
            dc_time: DCCTRL.DC_TIME 0-1023.
            dc_sg: DCCTRL.DC_SG 0-255; 0 disables DcStep stall detection.

        Raises:
            ValueError: If a value is out of range.
        """
        if not (0 <= vdcmin <= 0x7FFFFF and 0 <= dc_time <= 1023 and 0 <= dc_sg <= 255):
            raise ValueError('DcStep field out of range')
        self._write_reg(_REG_VDCMIN, vdcmin)
        self._write_reg(_REG_DCCTRL, dc_time | (dc_sg << 16))

    def write_microstep_table(self, mslut, mslutsel, mslutstart):
        """Program the microstep table; call at MSCNT = 0.

        Args:
            mslut: List of 8 32-bit MSLUT words.
            mslutsel: MSLUTSEL word.
            mslutstart: MSLUTSTART word.

        Raises:
            ValueError: If mslut does not hold 8 words.
        """
        if len(mslut) != 8:
            raise ValueError('mslut needs 8 words')
        for i, word in enumerate(mslut):
            self._write_reg(_REG_MSLUT0 + i, word)
        self._write_reg(_REG_MSLUTSEL, mslutsel)
        self._write_reg(_REG_MSLUTSTART, mslutstart)

    def microstep_position(self):
        """Return the microstep counter.

        Returns:
            int: MSCNT 0-1023.
        """
        return self._read_reg(_REG_MSCNT) & 0x3FF

    def read_current_scale(self):
        """Return the actual current scale.

        Returns:
            int: DRV_STATUS.CS_ACTUAL 0-31.
        """
        return (self._read_reg(_REG_DRV_STATUS) >> 16) & 0x1F

    def read_coil_currents(self):
        """Return the two sine-table coil current values.

        Returns:
            tuple: Signed (CUR_A, CUR_B) from MSCURACT.
        """
        raw = self._read_reg(_REG_MSCURACT)
        return (_to_signed(raw >> 16, 9), _to_signed(raw, 9))

    def read_tstep(self):
        """Return the time between microsteps.

        Returns:
            int: TSTEP in clock periods (20 bit).
        """
        return self._read_reg(_REG_TSTEP) & 0xFFFFF

    def read_pwm_scale(self):
        """Return the actual StealthChop amplitude scaler.

        Returns:
            int: PWM_SCALE 0-255.
        """
        return self._read_reg(_REG_PWM_SCALE) & 0xFF

    def read_lost_steps(self):
        """Return the steps skipped in DcStep mode.

        Returns:
            int: LOST_STEPS (20 bit, wraps).
        """
        return self._read_reg(_REG_LOST_STEPS) & 0xFFFFF

    def read_status(self):
        """Read DRV_STATUS decoded into a dict.

        Returns:
            dict: stst, fsactive, cs_actual, sg_result, stallguard, otpw, ot,
            short_gnd, open_load.
        """
        s = self._read_reg(_REG_DRV_STATUS)
        return {
            'stst': bool(s & (1 << 31)),
            'fsactive': bool(s & (1 << 15)),
            'cs_actual': (s >> 16) & 0x1F,
            'sg_result': s & 0x3FF,
            'stallguard': bool(s & (1 << 24)),
            'otpw': bool(s & (1 << 26)),
            'ot': bool(s & (1 << 25)),
            'short_gnd': bool(s & (3 << 27)),
            'open_load': bool(s & (3 << 29)),
        }

    def read_global_status(self):
        """Read GSTAT. The chip clears the flags on read.

        Returns:
            dict: reset, drv_err, uv_cp.
        """
        g = self._read_reg(_REG_GSTAT)
        return {'reset': bool(g & 1), 'drv_err': bool(g & 2), 'uv_cp': bool(g & 4)}

    def read_ioin(self):
        """Read IOIN.

        Returns:
            dict: raw word, step, dir, dcen, dcin, enn, dco, version.
        """
        raw = self._read_reg(_REG_IOIN)
        return {'raw': raw, 'step': bool(raw & 1), 'dir': bool(raw & 2),
                'dcen': bool(raw & 4), 'dcin': bool(raw & 8),
                'enn': bool(raw & 16), 'dco': bool(raw & 32),
                'version': (raw >> 24) & 0xFF}
