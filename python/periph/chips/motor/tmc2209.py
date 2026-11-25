import math
import time

_SYNC = 0x05
_MASTER_ADDR = 0xFF

_REG_GCONF = 0x00
_REG_GSTAT = 0x01
_REG_IFCNT = 0x02
_REG_NODECONF = 0x03
_REG_OTP_READ = 0x05
_REG_IOIN = 0x06
_REG_IHOLD_IRUN = 0x10
_REG_TPOWERDOWN = 0x11
_REG_TSTEP = 0x12
_REG_TPWMTHRS = 0x13
_REG_TCOOLTHRS = 0x14
_REG_VACTUAL = 0x22
_REG_SGTHRS = 0x40
_REG_SG_RESULT = 0x41
_REG_COOLCONF = 0x42
_REG_MSCNT = 0x6A
_REG_MSCURACT = 0x6B
_REG_CHOPCONF = 0x6C
_REG_DRV_STATUS = 0x6F
_REG_PWMCONF = 0x70
_REG_PWM_SCALE = 0x71
_REG_PWM_AUTO = 0x72

# Registers the chip lets the host read back (everything else is write-only
# or reserved).
_READABLE = (0x00, 0x01, 0x02, 0x05, 0x06, 0x07, 0x12, 0x41, 0x6A, 0x6B, 0x6C,
             0x6F, 0x70, 0x71, 0x72)
# Registers the driver may write through write_register().
_WRITABLE = (0x00, 0x01, 0x03, 0x10, 0x11, 0x13, 0x14, 0x22, 0x40, 0x42,
             0x6C, 0x70)

_V_FS_LOW_SENSE = 0.180
_V_FS_HIGH_SENSE = 0.325
_R_SENSE_PARASITIC = 0.020

_GCONF_I_SCALE_ANALOG = 1 << 0
_GCONF_INTERNAL_RSENSE = 1 << 1
_GCONF_EN_SPREADCYCLE = 1 << 2
_GCONF_SHAFT = 1 << 3
_GCONF_INDEX_OTPW = 1 << 4
_GCONF_INDEX_STEP = 1 << 5
_GCONF_PDN_DISABLE = 1 << 6
_GCONF_MSTEP_REG_SELECT = 1 << 7
_GCONF_MULTISTEP_FILT = 1 << 8

_VERSION = 0x21

_PWMCONF_DEFAULT = 0xC10D0024


def _crc8(data):
    """CRC-8 (polynomial 0x07, init 0, LSB-first) over a datagram body."""
    crc = 0
    for byte in data:
        for _ in range(8):
            if ((crc >> 7) ^ (byte & 1)):
                crc = ((crc << 1) ^ 0x07) & 0xFF
            else:
                crc = (crc << 1) & 0xFF
            byte >>= 1
    return crc


def _sleep_ms(ms):
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(ms)
    else:
        time.sleep(ms / 1000.0)


def _to_signed(value, bits):
    value &= (1 << bits) - 1
    if value & (1 << (bits - 1)):
        value -= 1 << bits
    return value


class TMC2209Minimal:
    """Trinamic TMC2209 stepper driver over single-wire UART.

    Brings the driver up in UART mode (``pdn_disable``, microstep resolution
    from ``CHOPCONF.MRES``), StealthChop, 16 microsteps with 256-step
    interpolation and 0.5 A RMS run current, then lets the internal step
    generator (``VACTUAL``) spin the motor -- no STEP pulses required.

    The datagram framing (sync byte, CRC8, host echo) is handled here; the
    connection only needs plain ``write`` / ``read`` / ``write_read``.

    Args:
        connection: UART connection to the chip's PDN_UART pin.
        r_sense_ohm: External sense resistor value in ohm (0.11 on most
            breakout boards).
        address: UART node address 0-3, as set by the MS1_AD0 / MS2_AD1 pins.
        en_pin: Optional OutputPin wired to ENN (active low). When given the
            driver stays disabled after construction until enable().
        echo: True (default) when TX and RX share PDN_UART so every sent byte
            is echoed back; False for separate RX/TX paths.

    Raises:
        ValueError: If address is not 0-3.
        OSError: If the chip does not answer or reports an unexpected
            identity or the write counter does not advance.
    """

    FAULT_OVERTEMP_WARNING = 0x01
    FAULT_OVERTEMP = 0x02
    FAULT_SHORT_GND = 0x04
    FAULT_SHORT_SUPPLY = 0x08
    FAULT_OPEN_LOAD = 0x10

    _fclk_hz = 12000000
    _multi_node = False
    _internal_rsense = False

    def __init__(self, connection, r_sense_ohm=0.11, address=0, en_pin=None,
                 echo=True):
        if not 0 <= address <= 3:
            raise ValueError('address must be 0-3')
        self._address = address
        self._conn = connection
        self._r_sense = r_sense_ohm
        self._en_pin = en_pin
        self._echo = echo
        self._shadow = {}
        self._tx_count = 0
        self._microsteps = 16
        self._vsense = False
        self._toff = 3
        self._run_cs = 0
        self._hold_cs = 0
        self._ihold_delay = 6
        self._initialise()

    # ---- datagram layer -------------------------------------------------

    def _recover(self):
        flush = getattr(self._conn, 'flush', None)
        if flush is not None:
            try:
                flush()
            except OSError:
                pass
        _sleep_ms(2)

    def _write_datagram(self, reg, value):
        body = bytes([_SYNC, self._address, (reg | 0x80) & 0xFF,
                      (value >> 24) & 0xFF, (value >> 16) & 0xFF,
                      (value >> 8) & 0xFF, value & 0xFF])
        datagram = body + bytes([_crc8(body)])
        self._conn.write(datagram)
        if self._echo:
            echoed = bytes(self._conn.read(8))
            if echoed != datagram:
                raise OSError('TMC2209: write echo mismatch')

    def _read_datagram(self, reg):
        request = bytes([_SYNC, self._address, reg & 0x7F])
        request += bytes([_crc8(request)])
        if self._echo:
            raw = bytes(self._conn.write_read(request, 12))
            if len(raw) != 12:
                raise OSError('TMC2209: short read')
            if raw[:4] != request:
                raise OSError('TMC2209: read echo mismatch')
            reply = raw[4:]
        else:
            reply = bytes(self._conn.write_read(request, 8))
            if len(reply) != 8:
                raise OSError('TMC2209: short read')
        if (reply[0] != _SYNC or reply[1] != _MASTER_ADDR
                or reply[2] != (reg & 0x7F)):
            raise OSError('TMC2209: malformed reply')
        if _crc8(reply[:7]) != reply[7]:
            raise OSError('TMC2209: reply CRC error')
        return ((reply[3] << 24) | (reply[4] << 16)
                | (reply[5] << 8) | reply[6])

    def _write_reg(self, reg, value):
        value &= 0xFFFFFFFF
        for attempt in range(2):
            try:
                self._write_datagram(reg, value)
                break
            except OSError:
                if attempt:
                    raise
                self._recover()
        self._tx_count += 1
        self._shadow[reg] = value

    def _read_reg(self, reg):
        for attempt in range(2):
            try:
                return self._read_datagram(reg)
            except OSError:
                if attempt:
                    raise
                self._recover()

    def _verify_writes(self, baseline, count):
        ifcnt = self._read_reg(_REG_IFCNT) & 0xFF
        if ifcnt != (baseline + count) & 0xFF:
            raise OSError('TMC2209: IFCNT mismatch (writes not acknowledged)')

    # ---- initialisation -------------------------------------------------

    def _gconf_init(self):
        return (_GCONF_I_SCALE_ANALOG | _GCONF_PDN_DISABLE
                | _GCONF_MSTEP_REG_SELECT | _GCONF_MULTISTEP_FILT)

    def _initialise(self):
        if self._en_pin is not None:
            self._en_pin.set(True)
        _sleep_ms(10)
        baseline = self._read_reg(_REG_IFCNT) & 0xFF
        ioin = self._read_reg(_REG_IOIN)
        version = (ioin >> 24) & 0xFF
        if version != _VERSION:
            raise OSError('TMC2209 not found (IOIN=0x%08X)' % ioin)
        count = 6
        if self._multi_node:
            self._write_reg(_REG_NODECONF, 2 << 8)
            count = 7
        self._write_reg(_REG_GCONF, self._gconf_init())
        self._write_reg(_REG_GSTAT, 0x07)
        self._run_cs = self._current_to_cs(0.5)
        self._hold_cs = self._current_to_cs(0.5 / 3)
        self._write_ihold_irun()
        self._write_reg(_REG_TPOWERDOWN, 20)
        chop = self._read_reg(_REG_CHOPCONF)
        chop &= ~(0xF | (3 << 15) | (7 << 4) | (0xF << 7) | (0xF << 24)
                  | (1 << 17))
        chop |= 3 | (2 << 15) | (5 << 4) | (4 << 24) | (1 << 28)
        self._write_reg(_REG_CHOPCONF, chop)
        self._write_reg(_REG_PWMCONF, _PWMCONF_DEFAULT)
        self._verify_writes(baseline, count)

    def _write_ihold_irun(self):
        value = (self._hold_cs & 0x1F) | ((self._run_cs & 0x1F) << 8) \
            | ((self._ihold_delay & 0xF) << 16)
        self._write_reg(_REG_IHOLD_IRUN, value)

    # ---- conversions ----------------------------------------------------

    def _current_to_cs(self, rms_a):
        if self._internal_rsense:
            raise ValueError('set_current is unsupported with internal Rsense')
        v_fs = _V_FS_LOW_SENSE if self._vsense else _V_FS_HIGH_SENSE
        cs = int(round(rms_a * math.sqrt(2) * (self._r_sense + _R_SENSE_PARASITIC)
                       * 32 / v_fs)) - 1
        return max(0, min(31, cs))

    # ---- public API -----------------------------------------------------

    def enable(self):
        """Switch the power stage on (ENN low, or TOFF restored without a pin)."""
        if self._en_pin is not None:
            self._en_pin.set(False)
        else:
            chop = self._shadow[_REG_CHOPCONF]
            self._write_reg(_REG_CHOPCONF, (chop & ~0xF) | self._toff)

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

        Args:
            rms_a: Run current in A RMS (at most 1.4), quantised to the
                nearest of 32 scale steps.

        Raises:
            ValueError: If the driver uses the internal sense resistors.
        """
        self._run_cs = self._current_to_cs(rms_a)
        self._hold_cs = self._current_to_cs(rms_a / 3.0)
        self._write_ihold_irun()

    def run(self, steps_per_s):
        """Move with the internal step generator (VACTUAL).

        Args:
            steps_per_s: Full steps per second at the configured microstepping;
                the sign gives the direction, 0 stops.
        """
        vactual = int(round(steps_per_s * self._microsteps
                            * 16777216.0 / self._fclk_hz))
        vactual = max(-8388607, min(8388607, vactual))
        self._write_reg(_REG_VACTUAL, vactual & 0xFFFFFF)

    def stop(self):
        """Stop the internal step generator (VACTUAL = 0)."""
        self._write_reg(_REG_VACTUAL, 0)

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
        if status & 0x01:
            fault |= self.FAULT_OVERTEMP_WARNING
        if status & 0x02:
            fault |= self.FAULT_OVERTEMP
        if status & 0x0C:
            fault |= self.FAULT_SHORT_GND
        if status & 0x30:
            fault |= self.FAULT_SHORT_SUPPLY
        if status & 0xC0:
            fault |= self.FAULT_OPEN_LOAD
        return fault


class TMC2209Full(TMC2209Minimal):
    """Complete TMC2209 driver: every configurable feature.

    Adds raw register access, independent hold current, microstep and chopper
    configuration, StealthChop tuning, GPIO STEP/DIR pulsing and the full set
    of status read-backs.

    Args:
        connection: UART connection to the chip's PDN_UART pin.
        r_sense_ohm: External sense resistor value in ohm.
        address: UART node address 0-3.
        en_pin: Optional OutputPin wired to ENN (active low).
        echo: True when TX and RX share PDN_UART.
        step_pin: Optional OutputPin wired to STEP.
        dir_pin: Optional OutputPin wired to DIR.
        diag_pin: Optional InputPin wired to DIAG (stall / driver error).
        multi_node: True when several chips share the UART; init then
            writes NODECONF.SENDDELAY = 2 first.
        fclk_hz: Chip clock in Hz (12 MHz internal; the external clock
            frequency if CLK is driven).
    """

    def __init__(self, connection, r_sense_ohm=0.11, address=0, en_pin=None,
                 echo=True, step_pin=None, dir_pin=None, diag_pin=None,
                 multi_node=False, fclk_hz=12000000):
        self._step_pin = step_pin
        self._dir_pin = dir_pin
        self._diag_pin = diag_pin
        self._multi_node = multi_node
        self._fclk_hz = fclk_hz
        super().__init__(connection, r_sense_ohm, address, en_pin, echo)

    def read_register(self, reg):
        """Read a raw 32-bit register.

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

    def write_register(self, reg, value):
        """Write a raw 32-bit register.

        Args:
            reg: Register address of a writable register.
            value: 32-bit value.

        Raises:
            ValueError: If the register is read-only, reserved or never
                written by this library (OTP_PROG, FACTORY_CONF).
        """
        if reg not in _WRITABLE:
            raise ValueError('register 0x%02X is not writable' % reg)
        self._write_reg(reg, value)

    def _modify(self, reg, mask, value):
        current = self._shadow[reg]
        self._write_reg(reg, (current & ~mask) | (value & mask))

    def set_hold_current(self, rms_a):
        """Set the standstill hold current independently of the run current.

        Args:
            rms_a: Hold current in A RMS.
        """
        self._hold_cs = self._current_to_cs(rms_a)
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
        """Choose SpreadCycle (True) or StealthChop (False).

        Args:
            spreadcycle: True for SpreadCycle.
        """
        self._modify(_REG_GCONF, _GCONF_EN_SPREADCYCLE,
                     _GCONF_EN_SPREADCYCLE if spreadcycle else 0)

    def set_stealthchop_threshold(self, tpwmthrs):
        """Set the StealthChop upper velocity threshold.

        Args:
            tpwmthrs: TPWMTHRS (20 bit); 0 disables the threshold.
        """
        self._write_reg(_REG_TPWMTHRS, tpwmthrs & 0xFFFFF)

    def configure_chopper(self, toff, tbl, hstrt, hend):
        """Write the chopper timing fields of CHOPCONF.

        Args:
            toff: Off time 0-15 (0 disables the driver).
            tbl: Blank time select 0-3.
            hstrt: Hysteresis start 0-7.
            hend: Raw hysteresis end field 0-15 (offset -3).
        """
        mask = 0xF | (3 << 15) | (7 << 4) | (0xF << 7)
        value = (toff & 0xF) | ((tbl & 3) << 15) | ((hstrt & 7) << 4) \
            | ((hend & 0xF) << 7)
        self._toff = toff or self._toff
        self._modify(_REG_CHOPCONF, mask, value)

    def configure_stealthchop(self, autoscale, autograd, pwm_ofs, pwm_grad,
                              pwm_freq, pwm_reg, pwm_lim):
        """Write the StealthChop fields of PWMCONF.

        Args:
            autoscale: Automatic current control.
            autograd: Automatic gradient tuning.
            pwm_ofs: Amplitude offset 0-255.
            pwm_grad: Amplitude gradient 0-255.
            pwm_freq: PWM frequency select 0-3.
            pwm_reg: Regulation gradient 1-15.
            pwm_lim: Amplitude limit 0-15.
        """
        mask = (0xF << 28) | (0xF << 24) | (1 << 19) | (1 << 18) \
            | (3 << 16) | (0xFF << 8) | 0xFF
        value = ((pwm_lim & 0xF) << 28) | ((pwm_reg & 0xF) << 24) \
            | ((1 << 19) if autograd else 0) | ((1 << 18) if autoscale else 0) \
            | ((pwm_freq & 3) << 16) | ((pwm_grad & 0xFF) << 8) | (pwm_ofs & 0xFF)
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
        """Select the sense voltage range (also changes the current scaling).

        Args:
            high_sensitivity: True for the low 0.18 V range.
        """
        self._modify(_REG_CHOPCONF, 1 << 17, (1 << 17) if high_sensitivity else 0)
        self._vsense = bool(high_sensitivity)

    def set_internal_rsense(self, enabled):
        """Use the internal sense resistors (set_current then raises).

        Args:
            enabled: True for internal Rsense.
        """
        self._modify(_REG_GCONF, _GCONF_INTERNAL_RSENSE,
                     _GCONF_INTERNAL_RSENSE if enabled else 0)
        self._internal_rsense = bool(enabled)

    def set_analog_scaling(self, enabled):
        """Take the current reference from VREF (True) or the internal one.

        Args:
            enabled: True for VREF scaling.
        """
        self._modify(_REG_GCONF, _GCONF_I_SCALE_ANALOG,
                     _GCONF_I_SCALE_ANALOG if enabled else 0)

    def set_direction_inverted(self, inverted):
        """Invert the motor direction (GCONF.shaft).

        Args:
            inverted: True to invert.
        """
        self._modify(_REG_GCONF, _GCONF_SHAFT, _GCONF_SHAFT if inverted else 0)

    def set_index_output(self, mode):
        """Select what the INDEX pin shows.

        Args:
            mode: 0 first microstep, 1 over-temperature pre-warning,
                2 internal generator step pulses.

        Raises:
            ValueError: If mode is not 0-2.
        """
        if mode not in (0, 1, 2):
            raise ValueError('index mode 0-2')
        bits = {0: 0, 1: _GCONF_INDEX_OTPW, 2: _GCONF_INDEX_STEP}[mode]
        self._modify(_REG_GCONF, _GCONF_INDEX_OTPW | _GCONF_INDEX_STEP, bits)

    def set_driver_protection(self, short_ls, short_gnd):
        """Enable or disable the short-circuit protections.

        Args:
            short_ls: True to keep low-side short protection (diss2vs = 0).
            short_gnd: True to keep short-to-GND protection (diss2g = 0).
        """
        value = (0 if short_ls else 1 << 31) | (0 if short_gnd else 1 << 30)
        self._modify(_REG_CHOPCONF, (1 << 31) | (1 << 30), value)

    def set_double_edge(self, enabled):
        """Step on both STEP edges (CHOPCONF.dedge).

        Args:
            enabled: True for both edges.
        """
        self._modify(_REG_CHOPCONF, 1 << 29, (1 << 29) if enabled else 0)

    def set_send_delay(self, n):
        """Set NODECONF.SENDDELAY, the delay before read replies.

        Args:
            n: 0-15 (pairs share one delay of 8 + 16 * (n // 2) bit times).

        Raises:
            ValueError: If n is out of range.
        """
        if not 0 <= n <= 15:
            raise ValueError('send delay 0-15')
        self._write_reg(_REG_NODECONF, n << 8)

    def set_stall_threshold(self, sgthrs):
        """Set the StallGuard4 threshold (SGTHRS).

        Args:
            sgthrs: 0-255; higher is more sensitive. A stall is signalled
                when SG_RESULT <= 2 * sgthrs.

        Raises:
            ValueError: If out of range.
        """
        if not 0 <= sgthrs <= 255:
            raise ValueError('sgthrs 0-255')
        self._write_reg(_REG_SGTHRS, sgthrs)

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
        """Write COOLCONF.

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
        value = (semin & 0xF) | ((seup & 3) << 5) | ((semax & 0xF) << 8) \
            | ((sedn & 3) << 13) | ((1 << 15) if seimin else 0)
        self._write_reg(_REG_COOLCONF, value)

    def read_stallguard(self):
        """Return the StallGuard4 result.

        Returns:
            int: SG_RESULT 0-510 (higher means less load).
        """
        return self._read_reg(_REG_SG_RESULT) & 0x3FF

    def is_stalled(self):
        """Return the DIAG pin level (stall or driver error).

        Returns:
            bool: True when DIAG is high; also true on a driver error.

        Raises:
            RuntimeError: If no diag_pin was given.
        """
        if self._diag_pin is None:
            raise RuntimeError('is_stalled() requires diag_pin')
        return bool(self._diag_pin.is_high())

    def step(self, count, delay_s):
        """Emit STEP pulses on the GPIO pin.

        Args:
            count: Number of pulses; negative reverses (needs dir_pin).
            delay_s: High and low time of each pulse in seconds.

        Raises:
            RuntimeError: If no step_pin was given.
            ValueError: If count is negative and no dir_pin was given.
        """
        if self._step_pin is None:
            raise RuntimeError('step() requires step_pin')
        if count < 0 and self._dir_pin is None:
            raise ValueError('negative count requires dir_pin')
        if self._dir_pin is not None:
            self._dir_pin.set(count >= 0)
        for _ in range(abs(count)):
            self._step_pin.set(True)
            time.sleep(delay_s)
            self._step_pin.set(False)
            time.sleep(delay_s)

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
            tuple: Signed 9-bit values (bits 8:0, bits 24:16) of MSCURACT.
        """
        raw = self._read_reg(_REG_MSCURACT)
        return (_to_signed(raw, 9), _to_signed(raw >> 16, 9))

    def read_tstep(self):
        """Return the time between microsteps.

        Returns:
            int: TSTEP in clock periods (20 bit).
        """
        return self._read_reg(_REG_TSTEP) & 0xFFFFF

    def read_temperature_flags(self):
        """Return the temperature comparator flags.

        Returns:
            int: Bit 0 t120, bit 1 t143, bit 2 t150, bit 3 t157.
        """
        return (self._read_reg(_REG_DRV_STATUS) >> 8) & 0xF

    def read_status(self):
        """Read DRV_STATUS decoded into a dict.

        Returns:
            dict: stst, stealth, cs_actual, otpw, ot, short_gnd, short_supply,
            open_load, t120, t143, t150, t157.
        """
        s = self._read_reg(_REG_DRV_STATUS)
        return {
            'stst': bool(s & (1 << 31)),
            'stealth': bool(s & (1 << 30)),
            'cs_actual': (s >> 16) & 0x1F,
            'otpw': bool(s & 0x01),
            'ot': bool(s & 0x02),
            'short_gnd': bool(s & 0x0C),
            'short_supply': bool(s & 0x30),
            'open_load': bool(s & 0xC0),
            't120': bool(s & (1 << 8)),
            't143': bool(s & (1 << 9)),
            't150': bool(s & (1 << 10)),
            't157': bool(s & (1 << 11)),
        }

    def read_global_status(self, clear=False):
        """Read GSTAT, optionally clearing the flags.

        Args:
            clear: True to write the flags back (write-1-to-clear).

        Returns:
            dict: reset, drv_err, uv_cp.
        """
        g = self._read_reg(_REG_GSTAT)
        if clear:
            self._write_reg(_REG_GSTAT, 0x07)
        return {'reset': bool(g & 1), 'drv_err': bool(g & 2),
                'uv_cp': bool(g & 4)}

    def read_ifcnt(self):
        """Return the UART write-datagram counter.

        Returns:
            int: IFCNT 0-255.
        """
        return self._read_reg(_REG_IFCNT) & 0xFF

    def read_ioin(self):
        """Read IOIN.

        Returns:
            dict: raw word, enn, ms1, ms2, diag, pdn_uart, step, spread,
            dir, version.
        """
        raw = self._read_reg(_REG_IOIN)
        return {'raw': raw, 'enn': bool(raw & 1), 'ms1': bool(raw & 4),
                'ms2': bool(raw & 8), 'diag': bool(raw & 0x10),
                'pdn_uart': bool(raw & 0x40), 'step': bool(raw & 0x80),
                'spread': bool(raw & 0x100), 'dir': bool(raw & 0x200),
                'version': (raw >> 24) & 0xFF}

    def read_otp(self):
        """Return the OTP image.

        Returns:
            int: OTP_READ 24-bit word.
        """
        return self._read_reg(_REG_OTP_READ) & 0xFFFFFF

    def read_pwm_auto(self):
        """Read the StealthChop auto-tuning results.

        Returns:
            dict: pwm_scale_sum, pwm_scale_auto (signed), pwm_ofs_auto,
            pwm_grad_auto.
        """
        scale = self._read_reg(_REG_PWM_SCALE)
        auto = self._read_reg(_REG_PWM_AUTO)
        return {'pwm_scale_sum': scale & 0xFF,
                'pwm_scale_auto': _to_signed(scale >> 16, 9),
                'pwm_ofs_auto': auto & 0xFF,
                'pwm_grad_auto': (auto >> 16) & 0xFF}
