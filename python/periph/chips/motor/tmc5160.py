import math
import time

_R = {
    'GCONF': 0x00, 'GSTAT': 0x01, 'IFCNT': 0x02, 'SLAVECONF': 0x03, 'IOIN': 0x04, 'OUTPUT': 0x04,
    'X_COMPARE': 0x05, 'OTP_READ': 0x07, 'SHORT_CONF': 0x09, 'DRV_CONF': 0x0A, 'GLOBALSCALER': 0x0B,
    'OFFSET_READ': 0x0C, 'IHOLD_IRUN': 0x10, 'TPOWERDOWN': 0x11, 'TSTEP': 0x12, 'TPWMTHRS': 0x13,
    'TCOOLTHRS': 0x14, 'THIGH': 0x15, 'RAMPMODE': 0x20, 'XACTUAL': 0x21, 'VACTUAL': 0x22,
    'VSTART': 0x23, 'A1': 0x24, 'V1': 0x25, 'AMAX': 0x26, 'VMAX': 0x27, 'DMAX': 0x28, 'D1': 0x2A,
    'VSTOP': 0x2B, 'TZEROWAIT': 0x2C, 'XTARGET': 0x2D, 'VDCMIN': 0x33, 'SW_MODE': 0x34,
    'RAMP_STAT': 0x35, 'XLATCH': 0x36, 'ENCMODE': 0x38, 'X_ENC': 0x39, 'ENC_CONST': 0x3A,
    'ENC_STATUS': 0x3B, 'ENC_LATCH': 0x3C, 'ENC_DEVIATION': 0x3D, 'MSLUT0': 0x60, 'MSLUTSEL': 0x68,
    'MSLUTSTART': 0x69, 'MSCNT': 0x6A, 'MSCURACT': 0x6B, 'CHOPCONF': 0x6C, 'COOLCONF': 0x6D,
    'DCCTRL': 0x6E, 'DRV_STATUS': 0x6F, 'PWMCONF': 0x70, 'PWM_SCALE': 0x71, 'PWM_AUTO': 0x72,
    'LOST_STEPS': 0x73,
}

# Registers the chip lets the host read back.
_READABLE = (0x00, 0x01, 0x02, 0x04, 0x07, 0x0C, 0x12, 0x20, 0x21, 0x22, 0x2D, 0x34, 0x35, 0x36,
             0x38, 0x39, 0x3B, 0x3C, 0x6A, 0x6B, 0x6C, 0x6F, 0x71, 0x72, 0x73)
# Registers the driver may write through write_register() (not OTP_PROG / FACTORY_CONF).
_WRITABLE = (0x00, 0x01, 0x03, 0x04, 0x05, 0x09, 0x0A, 0x0B, 0x10, 0x11, 0x13, 0x14, 0x15, 0x20,
             0x21, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2A, 0x2B, 0x2C, 0x2D, 0x33, 0x34, 0x35,
             0x38, 0x39, 0x3A, 0x3B, 0x3D, 0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
             0x69, 0x6C, 0x6D, 0x6E, 0x70)

_VERSION = 0x30
_V_FS = 0.325
_CHOPCONF_DEFAULT = 0x000100C3
_GCONF_EN_PWM_MODE = 1 << 2
_GCONF_SHAFT = 1 << 4
_GCONF_SMALL_HYSTERESIS = 1 << 14
_GCONF_STOP_ENABLE = 1 << 15
_GCONF_DIRECT_MODE = 1 << 16
_SW_SG_STOP = 1 << 10
_SW_SOFTSTOP = 1 << 11


def _crc8(data):
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


class TMC5160Minimal:
    """Trinamic TMC5160 stepper controller/driver over SPI or single-wire UART.

    Brings the chip up with the internal SixPoint ramp generator in positioning
    mode (256 microsteps, StealthChop below ``TPWMTHRS = 500``, 0.5 A RMS run
    current, 200 full steps/s, 400 full steps/s^2) and moves the motor with
    register writes -- no STEP pulses. Positions are in full steps, velocities
    in full steps per second, accelerations in full steps per second squared.

    The 40-bit SPI datagrams (pipelined replies) and the CRC-protected UART
    datagrams are built here; the connection only needs ``write`` /
    ``write_read``.

    Args:
        connection: SPI connection (Mode 3) or UART connection.
        bus_type: ``'spi'`` (default) or ``'uart'``.
        r_sense_ohm: External sense resistor in ohm (0.075 on common boards).
        en_pin: Optional OutputPin wired to DRV_ENN (active low). When given the
            driver stays disabled after construction until enable().
        address: UART node address 0-253 (UART only).
        echo: True when TX and RX share the single wire (UART only).
        multi_node: True when several chips share the UART; init then writes
            ``SLAVECONF.SENDDELAY = 2`` first (UART only).
        fclk_hz: Chip clock in Hz (12 MHz internal oscillator).

    Raises:
        ValueError: If ``bus_type`` is unknown.
        OSError: If the chip does not answer, is strapped for STEP/DIR operation
            or a write is not acknowledged.
    """

    FAULT_OVERTEMP_WARNING = 0x01
    FAULT_OVERTEMP = 0x02
    FAULT_SHORT_GND = 0x04
    FAULT_SHORT_SUPPLY = 0x08
    FAULT_OPEN_LOAD = 0x10

    def __init__(self, connection, bus_type='spi', r_sense_ohm=0.075, en_pin=None,
                 address=0, echo=True, multi_node=False, fclk_hz=12000000):
        if bus_type not in ('spi', 'uart'):
            raise ValueError("bus_type must be 'spi' or 'uart'")
        self._conn = connection
        self._uart = bus_type == 'uart'
        self._r_sense = r_sense_ohm
        self._en_pin = en_pin
        self._address = address
        self._echo = echo
        self._multi_node = multi_node
        self._fclk = fclk_hz
        self._shadow = {}
        self._usc = 256
        self._toff = 3
        self._rampmode = 0
        self._speed = 200.0
        self._accel = 400.0
        self._senddelay = 0
        self._last_move = (0.0, 200.0, 400.0)
        self._ifcnt_writes = 0
        self._initialise()

    # ---- transport layer ------------------------------------------------

    def _spi_second(self):
        reply = bytes(self._conn.write_read(bytes([_R['GCONF']]), 4))
        if len(reply) != 4:
            raise OSError('TMC5160: short read')
        return int.from_bytes(reply, 'big')

    def _uart_flush(self):
        flush = getattr(self._conn, 'flush', None)
        if flush is not None:
            try:
                flush()
            except OSError:
                pass
        _sleep_ms(2)

    def _uart_write(self, reg, value):
        body = bytes([0x05, self._address, (reg | 0x80) & 0xFF]) + value.to_bytes(4, 'big')
        datagram = body + bytes([_crc8(body)])
        self._conn.write(datagram)
        if self._echo and bytes(self._conn.read(8)) != datagram:
            raise OSError('TMC5160: write echo mismatch')

    def _uart_read(self, reg):
        request = bytes([0x05, self._address, reg & 0x7F])
        request += bytes([_crc8(request)])
        if self._echo:
            raw = bytes(self._conn.write_read(request, 12))
            if len(raw) != 12 or raw[:4] != request:
                raise OSError('TMC5160: read echo mismatch')
            reply = raw[4:]
        else:
            reply = bytes(self._conn.write_read(request, 8))
            if len(reply) != 8:
                raise OSError('TMC5160: short read')
        if reply[0] != 0x05 or reply[1] != 0xFF or reply[2] != (reg & 0x7F):
            raise OSError('TMC5160: malformed reply')
        if _crc8(reply[:7]) != reply[7]:
            raise OSError('TMC5160: reply CRC error')
        return int.from_bytes(reply[3:7], 'big')

    def _write_reg(self, reg, value, verify=False):
        value &= 0xFFFFFFFF
        if self._uart:
            before = self._read_reg(_R['IFCNT']) & 0xFF if verify else 0
            for attempt in range(2):
                try:
                    self._uart_write(reg, value)
                    break
                except OSError:
                    if attempt:
                        raise
                    self._uart_flush()
            self._ifcnt_writes += 1
            if verify and (self._read_reg(_R['IFCNT']) & 0xFF) != (before + 1) & 0xFF:
                raise OSError('TMC5160: IFCNT mismatch (write not acknowledged)')
        else:
            self._conn.write(bytes([(reg | 0x80) & 0xFF]) + value.to_bytes(4, 'big'))
            if verify and self._spi_second() != value:
                raise OSError('TMC5160: write mirror mismatch (reg 0x%02X)' % reg)
        self._shadow[reg] = value

    def _read_reg(self, reg):
        if self._uart:
            for attempt in range(2):
                try:
                    return self._uart_read(reg)
                except OSError:
                    if attempt:
                        raise
                    self._uart_flush()
        self._conn.write_read(bytes([reg & 0x7F]), 4)
        return self._spi_second()

    def _modify(self, reg, mask, value):
        current = self._shadow.get(reg, 0)
        self._write_reg(reg, (current & ~mask) | (value & mask))

    # ---- initialisation -------------------------------------------------

    def _initialise(self):
        if self._en_pin is not None:
            self._en_pin.set(True)
        _sleep_ms(10)
        ifcnt_base = self._read_reg(_R['IFCNT']) & 0xFF if self._uart else 0
        ioin = self._read_reg(_R['IOIN'])
        if ((ioin >> 24) & 0xFF) != _VERSION:
            raise OSError('TMC5160 not found (IOIN=0x%08X)' % ioin)
        if ioin & (1 << 6):
            raise OSError('TMC5160 is strapped for STEP/DIR operation (SD_MODE = 1)')
        self._ifcnt_writes = 0
        v = self._uart
        if v and self._multi_node:
            self._senddelay = 2
            self._write_reg(_R['SLAVECONF'], 2 << 8, True)
        self._read_reg(_R['GSTAT'])
        self._write_reg(_R['GSTAT'], 0x07, not v)
        self._write_reg(_R['GLOBALSCALER'], 0, not v)
        self._apply_current(min(0.5, _V_FS / (self._r_sense * math.sqrt(2))), not v)
        self._write_reg(_R['TPOWERDOWN'], 10, not v)
        self._write_reg(_R['CHOPCONF'], _CHOPCONF_DEFAULT, not v)
        self._write_reg(_R['GCONF'], _GCONF_EN_PWM_MODE, not v)
        self._write_reg(_R['TPWMTHRS'], 500, not v)
        self._write_reg(_R['RAMPMODE'], 0, not v)
        self._write_reg(_R['VSTART'], 0, not v)
        self._write_reg(_R['V1'], 0, not v)
        self._write_reg(_R['VSTOP'], 10, not v)
        self._write_reg(_R['TZEROWAIT'], 0, not v)
        self.set_speed(200)
        self.set_acceleration(400)
        if v:
            if (self._read_reg(_R['IFCNT']) & 0xFF) != (ifcnt_base + self._ifcnt_writes) & 0xFF:
                raise OSError('TMC5160: IFCNT mismatch (writes not acknowledged)')
        if (self._read_reg(_R['GCONF']) != self._shadow[_R['GCONF']]
                or self._read_reg(_R['CHOPCONF']) != self._shadow[_R['CHOPCONF']]):
            raise OSError('TMC5160: GCONF/CHOPCONF readback mismatch')
        self._rampmode = 0

    # ---- conversions ----------------------------------------------------

    def _v_reg(self, v):
        return int(round(v * self._usc * 16777216.0 / self._fclk))

    def _a_reg(self, a):
        return int(round(a * self._usc * 2199023255552.0 / (self._fclk * self._fclk)))

    def _apply_current(self, rms_a, verify=False):
        i_max = _V_FS / (self._r_sense * math.sqrt(2))
        r = rms_a / i_max
        if r > 1 or r < 1.0 / 64:
            raise ValueError('current outside %.3f..%.3f A RMS for this sense resistor'
                             % (i_max / 64, i_max))
        if r >= 0.5:
            irun = 31
            scaler = int(round(256 * r))
        else:
            scaler = 128
            irun = max(0, min(31, int(round(64 * r)) - 1))
        self._write_reg(_R['GLOBALSCALER'], 0 if scaler >= 256 else scaler, verify)
        self._irun = irun
        self._ihold = irun // 3
        self._ihold_delay = 6
        self._write_ihold_irun(verify)

    def _write_ihold_irun(self, verify=False):
        self._write_reg(_R['IHOLD_IRUN'], (self._ihold & 0x1F) | ((self._irun & 0x1F) << 8)
                        | ((self._ihold_delay & 0xF) << 16), verify)

    def _check_ramp(self, name, value, limit):
        if value < 0 or value > limit:
            raise ValueError('%s out of range' % name)
        return value

    # ---- public API -----------------------------------------------------

    def enable(self):
        """Switch the power stage on and wait 150 ms for StealthChop to settle."""
        if self._en_pin is not None:
            self._en_pin.set(False)
        else:
            chop = self._shadow[_R['CHOPCONF']]
            self._write_reg(_R['CHOPCONF'], (chop & ~0xF) | self._toff)
        _sleep_ms(150)

    def disable(self):
        """Switch the power stage off; the motor outputs float."""
        if self._en_pin is not None:
            self._en_pin.set(True)
        else:
            chop = self._shadow[_R['CHOPCONF']]
            self._toff = chop & 0xF or self._toff
            self._write_reg(_R['CHOPCONF'], chop & ~0xF)

    def set_current(self, rms_a):
        """Set the run current; hold current becomes one third of it.

        Sets GLOBALSCALER and IHOLD_IRUN. Call before enable(); a call while
        enabled simply re-tunes StealthChop.

        Args:
            rms_a: Run current in A RMS.

        Raises:
            ValueError: If above the sense-resistor maximum or below 1/64 of it.
        """
        self._apply_current(rms_a)

    def set_speed(self, full_steps_per_s):
        """Set VMAX, the target velocity of positioning moves and velocity mode.

        Args:
            full_steps_per_s: Speed in full steps per second (> 0).

        Raises:
            ValueError: If not positive or out of the register range.
        """
        if full_steps_per_s <= 0:
            raise ValueError('speed must be > 0')
        reg = self._check_ramp('VMAX', self._v_reg(full_steps_per_s), (1 << 23) - 512)
        self._speed = float(full_steps_per_s)
        self._speed_reg = reg
        if self._rampmode == 0:
            self._write_reg(_R['VMAX'], reg)

    def set_acceleration(self, full_steps_per_s2):
        """Set a single trapezoid: A1 = AMAX = DMAX = D1 (with V1 = 0).

        Args:
            full_steps_per_s2: Acceleration in full steps per second squared (> 0).

        Raises:
            ValueError: If not positive or out of the register range.
        """
        if full_steps_per_s2 <= 0:
            raise ValueError('acceleration must be > 0')
        reg = self._check_ramp('acceleration', self._a_reg(full_steps_per_s2), 0xFFFF)
        if reg < 1:
            raise ValueError('acceleration too small')
        self._accel = float(full_steps_per_s2)
        for name in ('A1', 'AMAX', 'DMAX', 'D1'):
            self._write_reg(_R[name], reg)

    def move_to(self, position):
        """Start a positioning move to an absolute position (non-blocking).

        Args:
            position: Target in full steps.
        """
        target = int(round(position * self._usc))
        self._last_move = (abs(position - self.position()), self._speed, self._accel)
        self._write_reg(_R['VMAX'], self._speed_reg)
        self._write_reg(_R['RAMPMODE'], 0)
        self._rampmode = 0
        self._write_reg(_R['XTARGET'], target)

    def move(self, full_steps, full_steps_per_s=None):
        """Blocking relative move; returns when the target is reached.

        Args:
            full_steps: Distance in full steps (sign = direction).
            full_steps_per_s: Optional speed override (restored afterwards).

        Raises:
            TimeoutError: If the target is not reached in time.
        """
        saved = self._speed
        if full_steps_per_s is not None:
            self.set_speed(full_steps_per_s)
        try:
            self.move_to(self.position() + full_steps)
            self.wait_until_reached()
        finally:
            if full_steps_per_s is not None:
                self.set_speed(saved)

    def run(self, full_steps_per_s):
        """Velocity mode: ramp to a velocity (sign = direction); 0 ramps to standstill.

        Args:
            full_steps_per_s: Velocity in full steps per second.
        """
        reg = self._check_ramp('VMAX', self._v_reg(abs(full_steps_per_s)), (1 << 23) - 512)
        mode = 2 if full_steps_per_s < 0 else 1
        self._write_reg(_R['RAMPMODE'], mode)
        self._rampmode = mode
        self._write_reg(_R['VMAX'], reg)

    def stop(self):
        """Controlled stop: velocity mode with VMAX = 0 (decelerates with AMAX)."""
        self._write_reg(_R['RAMPMODE'], 1)
        self._rampmode = 1
        self._write_reg(_R['VMAX'], 0)

    def position(self):
        """Return the actual position.

        Returns:
            float: XACTUAL in full steps.
        """
        return _to_signed(self._read_reg(_R['XACTUAL']), 32) / float(self._usc)

    def set_position(self, position):
        """Define the current position (no motion): writes XACTUAL and XTARGET.

        Args:
            position: New position value in full steps.
        """
        value = int(round(position * self._usc))
        self._write_reg(_R['XACTUAL'], value)
        self._write_reg(_R['XTARGET'], value)

    def velocity(self):
        """Return the actual velocity.

        Returns:
            float: VACTUAL in full steps per second, signed.
        """
        raw = _to_signed(self._read_reg(_R['VACTUAL']), 24)
        return raw * (self._fclk / 2.0 / 8388608.0) / self._usc

    def is_position_reached(self):
        """Return RAMP_STAT.position_reached.

        Returns:
            bool: True when XACTUAL equals XTARGET.
        """
        return bool(self._read_reg(_R['RAMP_STAT']) & (1 << 9))

    def wait_until_reached(self, timeout_s=None):
        """Poll RAMP_STAT until the move is done.

        Waits for position_reached in positioning mode, vzero in velocity mode.

        Args:
            timeout_s: Timeout in seconds; None estimates it from the last move.

        Returns:
            bool: True once reached.

        Raises:
            TimeoutError: On expiry.
        """
        if timeout_s is None:
            dist, speed, accel = self._last_move
            timeout_s = 2 * dist / speed + 2 * speed / accel + 1.0
        bit = (1 << 9) if self._rampmode == 0 else (1 << 10)
        _sleep_ms(1)
        waited = 0.0
        while True:
            if self._read_reg(_R['RAMP_STAT']) & bit:
                return True
            if waited >= timeout_s:
                raise TimeoutError('TMC5160: target not reached')
            _sleep_ms(5)
            waited += 0.005

    def is_standstill(self):
        """Return True when the motor is at standstill (DRV_STATUS.stst).

        Returns:
            bool: Standstill flag.
        """
        return bool(self._read_reg(_R['DRV_STATUS']) & (1 << 31))

    def read_fault(self):
        """Read the driver fault flags.

        Returns:
            int: Bitmask of FAULT_* constants, 0 when no fault is present.
        """
        s = self._read_reg(_R['DRV_STATUS'])
        fault = 0
        if s & (1 << 26):
            fault |= self.FAULT_OVERTEMP_WARNING
        if s & (1 << 25):
            fault |= self.FAULT_OVERTEMP
        if s & (3 << 27):
            fault |= self.FAULT_SHORT_GND
        if s & (3 << 12):
            fault |= self.FAULT_SHORT_SUPPLY
        if s & (3 << 29):
            fault |= self.FAULT_OPEN_LOAD
        return fault


class TMC5160Full(TMC5160Minimal):
    """Complete TMC5160 driver: every configurable feature.

    Adds raw register access, the full SixPoint ramp, reference switches,
    chopper / StealthChop tuning, StallGuard2 / CoolStep, DcStep, the ABN
    encoder, gate-driver configuration and all status read-backs.

    Args:
        connection: SPI connection (Mode 3) or UART connection.
        bus_type: ``'spi'`` or ``'uart'``.
        r_sense_ohm: External sense resistor in ohm.
        en_pin: Optional OutputPin wired to DRV_ENN (active low).
        address: UART node address 0-253.
        echo: True when TX and RX share the single wire (UART).
        multi_node: True for several chips on one UART.
        fclk_hz: Chip clock in Hz.
        diag0_pin: Optional InputPin wired to DIAG0 (SPI only).
        diag1_pin: Optional InputPin wired to DIAG1 (SPI only).

    Raises:
        ValueError: If a DIAG pin is given on the UART bus.
    """

    def __init__(self, connection, bus_type='spi', r_sense_ohm=0.075, en_pin=None,
                 address=0, echo=True, multi_node=False, fclk_hz=12000000,
                 diag0_pin=None, diag1_pin=None):
        if bus_type == 'uart' and (diag0_pin is not None or diag1_pin is not None):
            raise ValueError('DIAG pins are not available on the UART bus')
        self._diag_pins = (diag0_pin, diag1_pin)
        super().__init__(connection, bus_type, r_sense_ohm, en_pin, address, echo,
                         multi_node, fclk_hz)

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

    def write_register(self, reg, value, verify=False):
        """Write a raw 32-bit register.

        Args:
            reg: Register address of a writable register.
            value: 32-bit value.
            verify: True for the SPI mirror / UART IFCNT acknowledge check.

        Raises:
            ValueError: If the register is read-only or reserved.
        """
        if reg not in _WRITABLE:
            raise ValueError('register 0x%02X is not writable' % reg)
        self._write_reg(reg, value, verify)

    # ---- current ----

    def set_hold_current(self, rms_a):
        """Set the standstill hold current independently of the run current.

        Args:
            rms_a: Hold current in A RMS.

        Raises:
            ValueError: If above the present run current.
        """
        gs = self._shadow.get(_R['GLOBALSCALER'], 0) or 256
        i_max = _V_FS / (self._r_sense * math.sqrt(2))
        full = gs / 256.0 * i_max
        ihold = int(round(rms_a / full * 32)) - 1
        if rms_a <= 0 or ihold > self._irun:
            raise ValueError('hold current above run current')
        self._ihold = max(0, ihold)
        self._write_ihold_irun()

    def set_global_scaler(self, value):
        """Write the raw GLOBALSCALER.

        Args:
            value: 0 or 32-256 (256 is written as 0).

        Raises:
            ValueError: For 1-31 or above 256.
        """
        if not (value == 0 or 32 <= value <= 256):
            raise ValueError('GLOBALSCALER must be 0 or 32..256')
        self._write_reg(_R['GLOBALSCALER'], 0 if value == 256 else value)

    def set_current_scale(self, irun, ihold, ihold_delay):
        """Write the raw IHOLD_IRUN fields.

        Args:
            irun: Run current scale 0-31.
            ihold: Hold current scale 0-31.
            ihold_delay: IHOLDDELAY 0-15.

        Raises:
            ValueError: If out of range.
        """
        if not (0 <= irun <= 31 and 0 <= ihold <= 31 and 0 <= ihold_delay <= 15):
            raise ValueError('IHOLD_IRUN field out of range')
        self._irun, self._ihold, self._ihold_delay = irun, ihold, ihold_delay
        self._write_ihold_irun()

    def set_power_down_delay(self, tpowerdown):
        """Write TPOWERDOWN (values below 2 break StealthChop auto-tuning).

        Args:
            tpowerdown: 0-255.

        Raises:
            ValueError: If out of range.
        """
        if not 0 <= tpowerdown <= 255:
            raise ValueError('tpowerdown 0-255')
        self._write_reg(_R['TPOWERDOWN'], tpowerdown)

    # ---- motion ----

    def configure_ramp(self, vstart, a1, v1, amax, vmax, dmax, d1, vstop):
        """Program the full SixPoint ramp in physical units.

        Args:
            vstart: Start velocity, full steps/s.
            a1: First acceleration, full steps/s^2.
            v1: A1/D1 threshold velocity, full steps/s; 0 disables A1/D1.
            amax: Second acceleration (and velocity-mode acceleration).
            vmax: Target velocity, full steps/s.
            dmax: Deceleration above V1.
            d1: Deceleration below V1 (> 0).
            vstop: Stop velocity, full steps/s (> 0).

        Raises:
            ValueError: If a value is out of range, d1 or vstop is 0, or vstop < vstart.
        """
        self.set_ramp_raw(self._v_reg(vstart), self._a_reg(a1), self._v_reg(v1),
                          self._a_reg(amax), self._v_reg(vmax), self._a_reg(dmax),
                          self._a_reg(d1), self._v_reg(vstop))
        self._speed = float(vmax)
        self._accel = float(amax)

    def set_ramp_raw(self, vstart, a1, v1, amax, vmax, dmax, d1, vstop):
        """Program the full SixPoint ramp in raw register units.

        Args:
            vstart: VSTART (18 bit).
            a1: A1 (16 bit).
            v1: V1 (20 bit).
            amax: AMAX (16 bit).
            vmax: VMAX (23 bit, at most 2^23 - 512).
            dmax: DMAX (16 bit).
            d1: D1 (1-16 bit).
            vstop: VSTOP (1-18 bit).

        Raises:
            ValueError: If a value is out of range or vstop < vstart.
        """
        self._check_ramp('VSTART', vstart, (1 << 18) - 1)
        self._check_ramp('A1', a1, 0xFFFF)
        self._check_ramp('V1', v1, (1 << 20) - 1)
        self._check_ramp('AMAX', amax, 0xFFFF)
        self._check_ramp('VMAX', vmax, (1 << 23) - 512)
        self._check_ramp('DMAX', dmax, 0xFFFF)
        self._check_ramp('D1', d1, 0xFFFF)
        self._check_ramp('VSTOP', vstop, (1 << 18) - 1)
        if d1 < 1 or vstop < 1:
            raise ValueError('D1 and VSTOP must be at least 1')
        if vstop < vstart:
            raise ValueError('VSTOP must be >= VSTART')
        for name, val in (('VSTART', vstart), ('A1', a1), ('V1', v1), ('AMAX', amax),
                          ('VMAX', vmax), ('DMAX', dmax), ('D1', d1), ('VSTOP', vstop)):
            self._write_reg(_R[name], val)
        self._speed_reg = vmax

    def set_zero_wait(self, seconds):
        """Write TZEROWAIT.

        Args:
            seconds: Wait after ramp-down to zero (0 to about 2.8 s at 12 MHz).

        Raises:
            ValueError: If out of range.
        """
        self._write_reg(_R['TZEROWAIT'], self._check_ramp(
            'TZEROWAIT', int(round(seconds * self._fclk / 512)), 0xFFFF))

    def set_ramp_mode(self, mode):
        """Write RAMPMODE.

        Args:
            mode: 0 position, 1 velocity +, 2 velocity -, 3 hold.

        Raises:
            ValueError: If not 0-3.
        """
        if not 0 <= mode <= 3:
            raise ValueError('mode 0-3')
        self._write_reg(_R['RAMPMODE'], mode)
        self._rampmode = mode

    def hold(self):
        """Hold mode: keep the present velocity until a stop event."""
        self.set_ramp_mode(3)

    def set_target_raw(self, xtarget):
        """Write XTARGET in microsteps (starts positioning in mode 0).

        Args:
            xtarget: Signed 32-bit target.
        """
        self._write_reg(_R['XTARGET'], xtarget)

    def read_ramp_status(self):
        """Read RAMP_STAT decoded into a dict.

        Returns:
            dict: status_stop_l, status_stop_r, status_latch_l, status_latch_r,
            event_stop_l, event_stop_r, event_stop_sg, event_pos_reached,
            velocity_reached, position_reached, vzero, t_zerowait_active,
            second_move, status_sg.
        """
        s = self._read_reg(_R['RAMP_STAT'])
        names = ('status_stop_l', 'status_stop_r', 'status_latch_l', 'status_latch_r',
                 'event_stop_l', 'event_stop_r', 'event_stop_sg', 'event_pos_reached',
                 'velocity_reached', 'position_reached', 'vzero', 't_zerowait_active',
                 'second_move', 'status_sg')
        return {n: bool(s & (1 << i)) for i, n in enumerate(names)}

    def clear_ramp_events(self):
        """Write 1 to the clear-on-write bits of RAMP_STAT."""
        self._write_reg(_R['RAMP_STAT'], (1 << 2) | (1 << 3) | (1 << 6) | (1 << 7) | (1 << 12))

    def set_position_compare(self, position):
        """Write X_COMPARE; DIAG1 goes high while XACTUAL equals it.

        Args:
            position: Position in full steps.
        """
        self._write_reg(_R['X_COMPARE'], int(round(position * self._usc)))

    # ---- reference switches ----

    def configure_switches(self, stop_left=False, stop_right=False, left_active_low=False,
                           right_active_low=False, swap=False, soft_stop=False):
        """Write SW_MODE bits 0-4 and 11 (latch and sg_stop bits are kept).

        Args:
            stop_left: Stop automatically on the left switch.
            stop_right: Stop automatically on the right switch.
            left_active_low: Left switch is low-active.
            right_active_low: Right switch is low-active.
            swap: Swap REFL and REFR.
            soft_stop: Use soft stop (deceleration ramp).

        Raises:
            ValueError: If soft stop is requested while sg_stop is enabled.
        """
        sw = self._shadow.get(_R['SW_MODE'], 0)
        if soft_stop and sw & _SW_SG_STOP:
            raise ValueError('soft stop cannot be combined with sg_stop')
        value = (int(stop_left) | (int(stop_right) << 1) | (int(left_active_low) << 2)
                 | (int(right_active_low) << 3) | (int(swap) << 4)
                 | ((1 << 11) if soft_stop else 0))
        self._modify(_R['SW_MODE'], 0x1F | _SW_SOFTSTOP, value)

    def configure_latch(self, left_active=False, left_inactive=False, right_active=False,
                        right_inactive=False, latch_encoder=False):
        """Write the SW_MODE latch bits 5-9.

        Args:
            left_active: Latch on an active-going REFL edge.
            left_inactive: Latch on an inactive-going REFL edge.
            right_active: Latch on an active-going REFR edge.
            right_inactive: Latch on an inactive-going REFR edge.
            latch_encoder: Also latch the encoder position.
        """
        value = (int(left_active) << 5) | (int(left_inactive) << 6) | (int(right_active) << 7) \
            | (int(right_inactive) << 8) | (int(latch_encoder) << 9)
        self._modify(_R['SW_MODE'], 0x1F << 5, value)

    def read_latched_position(self):
        """Return the position latched on a switch event.

        Returns:
            float: XLATCH in full steps.
        """
        return _to_signed(self._read_reg(_R['XLATCH']), 32) / float(self._usc)

    def read_switches(self):
        """Return the reference-switch states.

        Returns:
            tuple: (status_stop_l, status_stop_r).
        """
        s = self._read_reg(_R['RAMP_STAT'])
        return (bool(s & 1), bool(s & 2))

    # ---- chopper / StealthChop ----

    def set_microsteps(self, n):
        """Select the microstep resolution (the ramp registers are not rewritten).

        Args:
            n: 1, 2, 4, 8, 16, 32, 64, 128 or 256.

        Raises:
            ValueError: For any other value.
        """
        if n not in (1, 2, 4, 8, 16, 32, 64, 128, 256):
            raise ValueError('microsteps must be a power of two, 1-256')
        self._modify(_R['CHOPCONF'], 0xF << 24, (8 - (n.bit_length() - 1)) << 24)
        self._usc = n

    def set_interpolation(self, enabled):
        """Set CHOPCONF.intpol.

        Args:
            enabled: True to interpolate (useful for external STEP/DIR only).
        """
        self._modify(_R['CHOPCONF'], 1 << 28, (1 << 28) if enabled else 0)

    def set_chopper_mode(self, spreadcycle):
        """Choose SpreadCycle (True) or StealthChop (False); switch at standstill.

        Args:
            spreadcycle: True for SpreadCycle only.
        """
        self._modify(_R['GCONF'], _GCONF_EN_PWM_MODE, 0 if spreadcycle else _GCONF_EN_PWM_MODE)

    def _threshold(self, name, value):
        if not 0 <= value <= 0xFFFFF:
            raise ValueError('%s 0-1048575' % name)
        self._write_reg(_R[name], value)

    def set_stealthchop_threshold(self, tpwmthrs):
        """Write TPWMTHRS (0 disables).

        Args:
            tpwmthrs: 20-bit threshold.

        Raises:
            ValueError: If out of range.
        """
        self._threshold('TPWMTHRS', tpwmthrs)

    def set_high_velocity_threshold(self, thigh):
        """Write THIGH.

        Args:
            thigh: 20-bit threshold.

        Raises:
            ValueError: If out of range.
        """
        self._threshold('THIGH', thigh)

    def configure_chopper(self, toff, tbl, hstrt, hend, tpfd=0):
        """Write the SpreadCycle (chm = 0) fields of CHOPCONF.

        Args:
            toff: Off time 0-15 (0 disables the driver).
            tbl: Blank time select 0-3.
            hstrt: Hysteresis start 0-7.
            hend: Raw hysteresis end field 0-15.
            tpfd: Passive fast decay time 0-15.

        Raises:
            ValueError: If a field is out of range.
        """
        if not (0 <= toff <= 15 and 0 <= tbl <= 3 and 0 <= hstrt <= 7 and 0 <= hend <= 15
                and 0 <= tpfd <= 15):
            raise ValueError('chopper field out of range')
        self._toff = toff or self._toff
        mask = 0xF | (3 << 15) | (7 << 4) | (0xF << 7) | (0xF << 20) | (1 << 14) | (1 << 11)
        self._modify(_R['CHOPCONF'], mask,
                     toff | (tbl << 15) | (hstrt << 4) | (hend << 7) | (tpfd << 20))

    def configure_constant_off_time(self, toff, tbl, tfd, offset, disfdcc, tpfd=0):
        """Write the constant-off-time (chm = 1) fields of CHOPCONF.

        Args:
            toff: Off time 0-15.
            tbl: Blank time select 0-3.
            tfd: Fast decay time 0-15.
            offset: Sine wave offset -3..12.
            disfdcc: True to disable current-comparator fast-decay termination.
            tpfd: Passive fast decay time 0-15.

        Raises:
            ValueError: If a field is out of range.
        """
        if not (0 <= toff <= 15 and 0 <= tbl <= 3 and 0 <= tfd <= 15 and -3 <= offset <= 12
                and 0 <= tpfd <= 15):
            raise ValueError('chopper field out of range')
        self._toff = toff or self._toff
        mask = 0xF | (3 << 15) | (7 << 4) | (0xF << 7) | (0xF << 20) | (1 << 14) | (1 << 12) | (1 << 11)
        value = toff | (tbl << 15) | ((tfd & 7) << 4) | ((offset + 3) << 7) | (tpfd << 20) \
            | (1 << 14) | ((1 << 12) if disfdcc else 0) | (((tfd >> 3) & 1) << 11)
        self._modify(_R['CHOPCONF'], mask, value)

    def configure_high_velocity(self, vhighchm, vhighfs):
        """Set CHOPCONF.vhighchm / vhighfs.

        Args:
            vhighchm: Switch to chm = 1 above THIGH.
            vhighfs: Switch to full-step operation above THIGH.
        """
        self._modify(_R['CHOPCONF'], (1 << 19) | (1 << 18),
                     ((1 << 19) if vhighchm else 0) | ((1 << 18) if vhighfs else 0))

    def configure_stealthchop(self, autoscale, autograd, pwm_ofs, pwm_grad, pwm_freq,
                              pwm_reg, pwm_lim):
        """Write the StealthChop fields of PWMCONF (freewheel is kept).

        Args:
            autoscale: Automatic current control.
            autograd: Automatic gradient tuning.
            pwm_ofs: Amplitude offset 0-255.
            pwm_grad: Amplitude gradient 0-255.
            pwm_freq: PWM frequency select 0-3.
            pwm_reg: Regulation gradient 1-15.
            pwm_lim: Amplitude limit 0-15.

        Raises:
            ValueError: If a field is out of range.
        """
        if not (0 <= pwm_ofs <= 255 and 0 <= pwm_grad <= 255 and 0 <= pwm_freq <= 3
                and 1 <= pwm_reg <= 15 and 0 <= pwm_lim <= 15):
            raise ValueError('stealthchop field out of range')
        mask = (0xF << 28) | (0xF << 24) | (1 << 19) | (1 << 18) | (3 << 16) | (0xFF << 8) | 0xFF
        value = (pwm_lim << 28) | (pwm_reg << 24) | ((1 << 19) if autograd else 0) \
            | ((1 << 18) if autoscale else 0) | (pwm_freq << 16) | (pwm_grad << 8) | pwm_ofs
        # PWMCONF is write-only; start from its reset value on first use.
        self._shadow.setdefault(_R['PWMCONF'], 0xC40C001E)
        self._modify(_R['PWMCONF'], mask, value)

    def set_freewheel(self, mode):
        """Set PWMCONF.freewheel (the standstill mode when IHOLD is 0).

        Args:
            mode: 0 normal, 1 freewheel, 2 low-side short, 3 high-side short.

        Raises:
            ValueError: If not 0-3.
        """
        if not 0 <= mode <= 3:
            raise ValueError('freewheel mode 0-3')
        self._shadow.setdefault(_R['PWMCONF'], 0xC40C001E)
        self._modify(_R['PWMCONF'], 3 << 20, mode << 20)

    def set_short_protection(self, short_gnd, short_supply):
        """Set CHOPCONF.diss2g / diss2vs.

        Args:
            short_gnd: True keeps the short-to-GND protection.
            short_supply: True keeps the short-to-supply protection.
        """
        self._modify(_R['CHOPCONF'], (1 << 30) | (1 << 31),
                     (0 if short_gnd else 1 << 30) | (0 if short_supply else 1 << 31))

    def set_double_edge(self, enabled):
        """Set CHOPCONF.dedge (external STEP/DIR only).

        Args:
            enabled: True for both STEP edges.
        """
        self._modify(_R['CHOPCONF'], 1 << 29, (1 << 29) if enabled else 0)

    # ---- GCONF ----

    def set_direction_inverted(self, inverted):
        """Set GCONF.shaft.

        Args:
            inverted: True to invert the motor direction.
        """
        self._modify(_R['GCONF'], _GCONF_SHAFT, _GCONF_SHAFT if inverted else 0)

    def set_fast_standstill(self, enabled):
        """Set GCONF.faststandstill.

        Args:
            enabled: True for the short standstill timeout.
        """
        self._modify(_R['GCONF'], 1 << 1, (1 << 1) if enabled else 0)

    def set_recalibrate(self, enabled):
        """Set GCONF.recalibrate.

        Args:
            enabled: True for zero-crossing recalibration while disabled.
        """
        self._modify(_R['GCONF'], 1, 1 if enabled else 0)

    def set_small_hysteresis(self, enabled):
        """Set GCONF.small_hysteresis.

        Args:
            enabled: True for 1/32 instead of 1/16.
        """
        self._modify(_R['GCONF'], _GCONF_SMALL_HYSTERESIS, _GCONF_SMALL_HYSTERESIS if enabled else 0)

    def set_emergency_stop(self, enabled):
        """Set GCONF.stop_enable (ENCA_DCIN high stops the sequencer).

        Args:
            enabled: True to enable.
        """
        self._modify(_R['GCONF'], _GCONF_STOP_ENABLE, _GCONF_STOP_ENABLE if enabled else 0)

    def configure_diag(self, diag0_step_output=False, diag1_dir_output=False,
                       diag0_pushpull=False, diag1_pushpull=False):
        """Set GCONF bits 7, 8, 12, 13 (SD_MODE = 0 meanings).

        Args:
            diag0_step_output: DIAG0 is a half-frequency STEP output instead of the interrupt.
            diag1_dir_output: DIAG1 is a DIR output instead of position-compare.
            diag0_pushpull: DIAG0 push-pull (active high).
            diag1_pushpull: DIAG1 push-pull (active high).
        """
        value = (int(diag0_step_output) << 7) | (int(diag1_dir_output) << 8) \
            | (int(diag0_pushpull) << 12) | (int(diag1_pushpull) << 13)
        self._modify(_R['GCONF'], (1 << 7) | (1 << 8) | (1 << 12) | (1 << 13), value)

    # ---- stall detection / CoolStep ----

    def set_stall_threshold(self, sgt):
        """Set COOLCONF.sgt, keeping the other COOLCONF fields.

        Args:
            sgt: -64..63; lower is more sensitive.

        Raises:
            ValueError: If out of range.
        """
        if not -64 <= sgt <= 63:
            raise ValueError('sgt -64..63')
        self._modify(_R['COOLCONF'], 0x7F << 16, (sgt & 0x7F) << 16)

    def set_stallguard_filter(self, enabled):
        """Set COOLCONF.sfilt.

        Args:
            enabled: True to update once per four full steps.
        """
        self._modify(_R['COOLCONF'], 1 << 24, (1 << 24) if enabled else 0)

    def set_coolstep_threshold(self, tcoolthrs):
        """Write TCOOLTHRS (0 disables CoolStep and the stall functions).

        Args:
            tcoolthrs: 20-bit threshold.

        Raises:
            ValueError: If out of range.
        """
        self._threshold('TCOOLTHRS', tcoolthrs)

    def configure_coolstep(self, semin, semax, seup, sedn, seimin):
        """Write the CoolStep fields of COOLCONF (sgt / sfilt are kept).

        Args:
            semin: Lower threshold 0-15; 0 disables CoolStep.
            semax: Upper threshold offset 0-15.
            seup: Current up step width 0-3.
            sedn: Current down step speed 0-3.
            seimin: 0 for half IRUN minimum, 1 for quarter.

        Raises:
            ValueError: If a field is out of range.
        """
        if (not 0 <= semin <= 15 or not 0 <= semax <= 15 or not 0 <= seup <= 3
                or not 0 <= sedn <= 3 or seimin not in (0, 1, True, False)):
            raise ValueError('COOLCONF field out of range')
        mask = 0xF | (3 << 5) | (0xF << 8) | (3 << 13) | (1 << 15)
        value = semin | (seup << 5) | (semax << 8) | (sedn << 13) | ((1 << 15) if seimin else 0)
        self._modify(_R['COOLCONF'], mask, value)

    def enable_stall_stop(self, enabled):
        """Set SW_MODE.sg_stop: stop the motor on a StallGuard2 event.

        Args:
            enabled: True to enable (needs TCOOLTHRS; not during spin-up).

        Raises:
            ValueError: If soft stop is enabled.
        """
        if enabled and self._shadow.get(_R['SW_MODE'], 0) & _SW_SOFTSTOP:
            raise ValueError('sg_stop cannot be combined with soft stop')
        self._modify(_R['SW_MODE'], _SW_SG_STOP, _SW_SG_STOP if enabled else 0)

    def read_stallguard(self):
        """Return the StallGuard2 result.

        Returns:
            int: SG_RESULT 0-1023 (0 = highest load).
        """
        return self._read_reg(_R['DRV_STATUS']) & 0x3FF

    def is_stalled(self):
        """Return DRV_STATUS.StallGuard.

        Returns:
            bool: True when a stall is detected.
        """
        return bool(self._read_reg(_R['DRV_STATUS']) & (1 << 24))

    def read_diag(self, n):
        """Return the level of a DIAG pin.

        Args:
            n: 0 for DIAG0, 1 for DIAG1.

        Returns:
            bool: True when the pin is high.

        Raises:
            ValueError: If n is not 0 or 1.
            RuntimeError: If the pin is not wired or the bus is UART.
        """
        if n not in (0, 1):
            raise ValueError('n must be 0 or 1')
        pin = self._diag_pins[n]
        if pin is None:
            raise RuntimeError('read_diag() requires diag%d_pin on the SPI bus' % n)
        return bool(pin.is_high())

    # ---- DcStep / direct mode / table ----

    def set_dcstep(self, vdcmin, dc_time, dc_sg):
        """Configure DcStep.

        Args:
            vdcmin: VDCMIN 0-2^23-1.
            dc_time: DCCTRL.DC_TIME 0-1023.
            dc_sg: DCCTRL.DC_SG 0-255.

        Raises:
            ValueError: If out of range.
        """
        if not (0 <= vdcmin <= 0x7FFFFF and 0 <= dc_time <= 1023 and 0 <= dc_sg <= 255):
            raise ValueError('DcStep field out of range')
        self._write_reg(_R['VDCMIN'], vdcmin)
        self._write_reg(_R['DCCTRL'], dc_time | (dc_sg << 16))

    def set_direct_mode(self, enabled):
        """Set GCONF.direct_mode (the ramp generator is bypassed while set).

        Args:
            enabled: True for direct coil currents via XDIRECT.
        """
        self._modify(_R['GCONF'], _GCONF_DIRECT_MODE, _GCONF_DIRECT_MODE if enabled else 0)
        if not enabled:
            self._write_reg(_R['XTARGET'], self._shadow.get(_R['XTARGET'], 0))

    def set_coil_currents(self, coil_a, coil_b):
        """Write XDIRECT (shares the XTARGET address).

        Args:
            coil_a: Signed coil A current -255..255.
            coil_b: Signed coil B current -255..255.

        Raises:
            RuntimeError: If direct mode is not enabled.
            ValueError: If out of range.
        """
        if not self._shadow.get(_R['GCONF'], 0) & _GCONF_DIRECT_MODE:
            raise RuntimeError('set_coil_currents() requires direct mode')
        if not (-255 <= coil_a <= 255 and -255 <= coil_b <= 255):
            raise ValueError('coil currents -255..255')
        self._write_reg(_R['XTARGET'], (coil_a & 0x1FF) | ((coil_b & 0x1FF) << 16))

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
            self._write_reg(_R['MSLUT0'] + i, word)
        self._write_reg(_R['MSLUTSEL'], mslutsel)
        self._write_reg(_R['MSLUTSTART'], mslutstart)

    # ---- driver hardware ----

    def configure_gate_driver(self, bbmtime, bbmclks, otselect, drvstrength, filt_isense):
        """Write DRV_CONF. Board-specific: wrong values can destroy the MOSFETs.

        Args:
            bbmtime: Analog break-before-make 0-24.
            bbmclks: Digital break-before-make 0-15 clocks.
            otselect: Over-temperature level 0-3.
            drvstrength: Gate-driver current 0-3.
            filt_isense: Sense-amplifier filter 0-3.

        Raises:
            ValueError: If a field is out of range.
        """
        if not (0 <= bbmtime <= 24 and 0 <= bbmclks <= 15 and 0 <= otselect <= 3
                and 0 <= drvstrength <= 3 and 0 <= filt_isense <= 3):
            raise ValueError('DRV_CONF field out of range')
        self._write_reg(_R['DRV_CONF'], bbmtime | (bbmclks << 8) | (otselect << 16)
                        | (drvstrength << 18) | (filt_isense << 20))

    def configure_short_detection(self, s2vs_level, s2g_level, shortfilter, shortdelay):
        """Write SHORT_CONF. Board-specific.

        Args:
            s2vs_level: Short-to-supply level 4-15.
            s2g_level: Short-to-GND level 2-15.
            shortfilter: Spike filter 0-3.
            shortdelay: 0 or 1.

        Raises:
            ValueError: If a field is out of range.
        """
        if not (4 <= s2vs_level <= 15 and 2 <= s2g_level <= 15 and 0 <= shortfilter <= 3
                and shortdelay in (0, 1, True, False)):
            raise ValueError('SHORT_CONF field out of range')
        self._write_reg(_R['SHORT_CONF'], s2vs_level | (s2g_level << 8) | (shortfilter << 16)
                        | (int(shortdelay) << 18))

    # ---- encoder ----

    def configure_encoder(self, pol_a, pol_b, pol_n, ignore_ab, clr_cont, clr_once, pos_edge,
                          neg_edge, clr_enc_x, latch_x_act, decimal):
        """Write ENCMODE (all bool).

        Args:
            pol_a: Required A polarity for an N event.
            pol_b: Required B polarity for an N event.
            pol_n: Active polarity of N.
            ignore_ab: Ignore A/B polarity for the N event.
            clr_cont: Latch on every N event.
            clr_once: Latch on the next N event only.
            pos_edge: N edge select (with neg_edge).
            neg_edge: N edge select (with pos_edge).
            clr_enc_x: Latch and clear X_ENC.
            latch_x_act: Also latch XACTUAL.
            decimal: ENC_CONST fraction counts in 1/10000 instead of 1/65536.
        """
        flags = (pol_a, pol_b, pol_n, ignore_ab, clr_cont, clr_once, pos_edge, neg_edge,
                 clr_enc_x, latch_x_act, decimal)
        value = 0
        for i, flag in enumerate(flags):
            if flag:
                value |= 1 << i
        self._write_reg(_R['ENCMODE'], value)

    def set_encoder_constant(self, value):
        """Write ENC_CONST (signed 16.16, or integer + 4-digit decimal fraction).

        Args:
            value: Accumulation constant, +-32767.9999.

        Raises:
            ValueError: If out of range.
        """
        if abs(value) > 32767.9999:
            raise ValueError('encoder constant out of range')
        if self._shadow.get(_R['ENCMODE'], 0) & (1 << 10):
            whole = int(abs(value))
            raw = (whole << 16) | int(round((abs(value) - whole) * 10000))
            raw = (-raw) & 0xFFFFFFFF if value < 0 else raw
        else:
            raw = int(round(value * 65536)) & 0xFFFFFFFF
        self._write_reg(_R['ENC_CONST'], raw)

    def set_encoder_position(self, counts):
        """Write X_ENC.

        Args:
            counts: Signed 32-bit encoder position.
        """
        self._write_reg(_R['X_ENC'], counts)

    def read_encoder_position(self):
        """Return X_ENC.

        Returns:
            int: Signed encoder position.
        """
        return _to_signed(self._read_reg(_R['X_ENC']), 32)

    def read_encoder_latch(self):
        """Return ENC_LATCH.

        Returns:
            int: Signed latched encoder position.
        """
        return _to_signed(self._read_reg(_R['ENC_LATCH']), 32)

    def set_encoder_deviation(self, steps):
        """Write ENC_DEVIATION (0 = off).

        Args:
            steps: 0-2^20-1.

        Raises:
            ValueError: If out of range.
        """
        if not 0 <= steps <= 0xFFFFF:
            raise ValueError('steps 0-1048575')
        self._write_reg(_R['ENC_DEVIATION'], steps)

    def read_encoder_status(self):
        """Read ENC_STATUS.

        Returns:
            dict: n_event, deviation_warn.
        """
        s = self._read_reg(_R['ENC_STATUS'])
        return {'n_event': bool(s & 1), 'deviation_warn': bool(s & 2)}

    def clear_encoder_status(self):
        """Write 1 to the ENC_STATUS event bits."""
        self._write_reg(_R['ENC_STATUS'], 0x03)

    # ---- UART only ----

    def _require_uart(self):
        if not self._uart:
            raise RuntimeError('only available on the UART bus')

    def set_slave_address(self, slaveaddr):
        """Write SLAVECONF.SLAVEADDR (UART only); later datagrams use the new address.

        Args:
            slaveaddr: 0-253.

        Raises:
            RuntimeError: On the SPI bus.
            ValueError: If out of range.
        """
        self._require_uart()
        if not 0 <= slaveaddr <= 253:
            raise ValueError('slaveaddr 0-253')
        self._write_reg(_R['SLAVECONF'], slaveaddr | (self._senddelay << 8))
        self._address = slaveaddr

    def set_send_delay(self, sd):
        """Write SLAVECONF.SENDDELAY (UART only; at least 2 on multi-node buses).

        Args:
            sd: 0-15.

        Raises:
            RuntimeError: On the SPI bus.
            ValueError: If out of range.
        """
        self._require_uart()
        if not 0 <= sd <= 15:
            raise ValueError('send delay 0-15')
        self._senddelay = sd
        self._write_reg(_R['SLAVECONF'], (self._shadow.get(_R['SLAVECONF'], 0) & 0xFF) | (sd << 8))

    def set_nao_polarity(self, level):
        """Write OUTPUT bit 0, the SDO_CFG0 / NAO polarity (UART only).

        Args:
            level: True for the reset level (high).

        Raises:
            RuntimeError: On the SPI bus.
        """
        self._require_uart()
        self._write_reg(_R['OUTPUT'], 1 if level else 0)

    def read_ifcnt(self):
        """Return the UART write-datagram counter (UART only).

        Returns:
            int: IFCNT 0-255.

        Raises:
            RuntimeError: On the SPI bus.
        """
        self._require_uart()
        return self._read_reg(_R['IFCNT']) & 0xFF

    # ---- status ----

    def microstep_position(self):
        """Return the microstep counter.

        Returns:
            int: MSCNT 0-1023.
        """
        return self._read_reg(_R['MSCNT']) & 0x3FF

    def read_current_scale(self):
        """Return the actual current scale.

        Returns:
            int: DRV_STATUS.CS_ACTUAL 0-31.
        """
        return (self._read_reg(_R['DRV_STATUS']) >> 16) & 0x1F

    def read_coil_currents(self):
        """Return the two sine-table coil current values.

        Returns:
            tuple: Signed (CUR_A, CUR_B) from MSCURACT.
        """
        raw = self._read_reg(_R['MSCURACT'])
        return (_to_signed(raw >> 16, 9), _to_signed(raw, 9))

    def read_tstep(self):
        """Return the time between microsteps.

        Returns:
            int: TSTEP in clock periods (20 bit).
        """
        return self._read_reg(_R['TSTEP']) & 0xFFFFF

    def read_pwm_scale(self):
        """Return the StealthChop amplitude regulator state.

        Returns:
            tuple: (PWM_SCALE_SUM 0-255, PWM_SCALE_AUTO signed).
        """
        raw = self._read_reg(_R['PWM_SCALE'])
        return (raw & 0xFF, _to_signed(raw >> 16, 9))

    def read_pwm_auto(self):
        """Return the StealthChop auto-tuning results.

        Returns:
            tuple: (PWM_OFS_AUTO, PWM_GRAD_AUTO).
        """
        raw = self._read_reg(_R['PWM_AUTO'])
        return (raw & 0xFF, (raw >> 16) & 0xFF)

    def read_offset(self):
        """Return the offset-calibration result.

        Returns:
            tuple: Signed (phase A, phase B) from OFFSET_READ.
        """
        raw = self._read_reg(_R['OFFSET_READ'])
        return (_to_signed(raw >> 8, 8), _to_signed(raw, 8))

    def read_status(self):
        """Read DRV_STATUS decoded into a dict.

        Returns:
            dict: stst, stealth, fsactive, cs_actual, sg_result, stallguard, otpw,
            ot, short_gnd, short_supply, open_load.
        """
        s = self._read_reg(_R['DRV_STATUS'])
        return {
            'stst': bool(s & (1 << 31)), 'stealth': bool(s & (1 << 14)),
            'fsactive': bool(s & (1 << 15)), 'cs_actual': (s >> 16) & 0x1F,
            'sg_result': s & 0x3FF, 'stallguard': bool(s & (1 << 24)),
            'otpw': bool(s & (1 << 26)), 'ot': bool(s & (1 << 25)),
            'short_gnd': bool(s & (3 << 27)), 'short_supply': bool(s & (3 << 12)),
            'open_load': bool(s & (3 << 29)),
        }

    def read_global_status(self):
        """Read GSTAT (does not clear it).

        Returns:
            dict: reset, drv_err, uv_cp.
        """
        g = self._read_reg(_R['GSTAT'])
        return {'reset': bool(g & 1), 'drv_err': bool(g & 2), 'uv_cp': bool(g & 4)}

    def clear_global_status(self):
        """Write 0x07 to GSTAT to clear the flags."""
        self._write_reg(_R['GSTAT'], 0x07)

    def read_ioin(self):
        """Read IOIN.

        Returns:
            dict: raw, refl, refr, encb, enca, drv_enn, encn, sd_mode, swcomp_in, version.
        """
        raw = self._read_reg(_R['IOIN'])
        return {'raw': raw, 'refl': bool(raw & 1), 'refr': bool(raw & 2),
                'encb': bool(raw & 4), 'enca': bool(raw & 8), 'drv_enn': bool(raw & 16),
                'encn': bool(raw & 32), 'sd_mode': bool(raw & 64),
                'swcomp_in': bool(raw & 128), 'version': (raw >> 24) & 0xFF}
