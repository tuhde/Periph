"""LSM303DLHC — 3-axis accelerometer and 3-axis magnetometer e-compass (STMicroelectronics).

The LSM303DLHC module is two independent I²C slaves on one bus: the
accelerometer at 0x19 and the magnetometer at 0x1E. The driver treats them as
one logical device with two connections (the same pattern as MPU9250). The
compass heading is computed in software from both sensors.

Accelerometer framing: a multi-byte access sets bit 7 of the sub-address
(auto-increment), so the driver ORs 0x80 into the sub-address itself for
bursts; single-register access does not. Magnetometer framing: an address
pointer that auto-increments by itself, so no extra bit is needed.

Output order: acceleration is little-endian per axis (X, Y, Z); the
magnetometer is big-endian and its axes arrive in the order X, Z, Y, which
this driver reorders to X, Y, Z.

The driver is platform-agnostic — it only calls the RegisterConnection
interface. The same file is used on MicroPython, CircuitPython, and Linux.
"""

import math
import struct


def _sleep_ms(ms):
    import time
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(ms)
    else:
        time.sleep(ms / 1000.0)


def _round_clamp(value, lo, hi):
    v = int(round(value))
    return max(lo, min(hi, v))


def _b(flag):
    return 1 if flag else 0


def _check_pin(pin):
    if pin not in (1, 2):
        raise ValueError('pin must be 1 or 2')


# Accelerometer full-scale code (CTRL_REG4_A FS<1:0>) by range in g.
_FS_CODE = {2: 0, 4: 1, 8: 2, 16: 3}

# High-resolution sensitivity in mg/LSB by range in g (datasheet Table 3).
_HR_MG_PER_LSB = {2: 1, 4: 2, 8: 4, 16: 12}

# Index by mode (0 low-power, 1 normal, 2 high-resolution).
# Sensitivity multiplier vs high-resolution: 16x (8-bit), 4x (10-bit), 1x (12-bit).
_MODE_MG_MULT = (16, 4, 1)
# Right-shift applied to the left-justified 16-bit sample.
_MODE_SHIFT = (8, 6, 4)

# CTRL_REG1_A ODR<3:0> codes by output data rate in Hz.
# 1620 Hz and 5376 Hz are low-power only; 1344 Hz is normal / high-resolution only.
_ODR_CODES = {
    0: 0x0, 1: 0x1, 10: 0x2, 25: 0x3, 50: 0x4, 100: 0x5, 200: 0x6, 400: 0x7,
    1620: 0x8, 1344: 0x9, 5376: 0x9,
}

# Magnetometer full scale in µT -> (GN code, X/Y gain LSB/gauss, Z gain LSB/gauss).
_MAG_RANGE = {
    130: (1, 1100, 980),
    190: (2, 855, 760),
    250: (3, 670, 600),
    400: (4, 450, 400),
    470: (5, 400, 355),
    560: (6, 330, 295),
    810: (7, 230, 205),
}

# Magnetometer output data rate in Hz -> DO<2:0> code.
_MAG_RATE = {0.75: 0, 1.5: 1, 3: 2, 7.5: 3, 15: 4, 30: 5, 75: 6, 220: 7}

# Accelerometer register addresses.
_REG_CTRL_REG1_A = 0x20
_REG_CTRL_REG2_A = 0x21
_REG_CTRL_REG3_A = 0x22
_REG_CTRL_REG4_A = 0x23
_REG_CTRL_REG5_A = 0x24
_REG_CTRL_REG6_A = 0x25
_REG_REFERENCE_A = 0x26
_REG_STATUS_REG_A = 0x27
_REG_OUT_X_L_A = 0x28
_REG_FIFO_CTRL_REG_A = 0x2E
_REG_FIFO_SRC_REG_A = 0x2F
_REG_INT1_CFG_A = 0x30
_REG_INT1_SOURCE_A = 0x31
_REG_INT1_THS_A = 0x32
_REG_INT1_DURATION_A = 0x33
_REG_INT2_CFG_A = 0x34
_REG_INT2_SOURCE_A = 0x35
_REG_INT2_THS_A = 0x36
_REG_INT2_DURATION_A = 0x37
_REG_CLICK_CFG_A = 0x38
_REG_CLICK_SRC_A = 0x39
_REG_CLICK_THS_A = 0x3A
_REG_TIME_LIMIT_A = 0x3B
_REG_TIME_LATENCY_A = 0x3C
_REG_TIME_WINDOW_A = 0x3D

# Magnetometer register addresses.
_REG_CRA_REG_M = 0x00
_REG_CRB_REG_M = 0x01
_REG_MR_REG_M = 0x02
_REG_OUT_X_H_M = 0x03
_REG_SR_REG_M = 0x09
_REG_IRA_REG_M = 0x0A
_REG_TEMP_OUT_H_M = 0x31

# Magnetometer identity (IRA, IRB, IRC) — ASCII "H43".
_MAG_ID = b'\x48\x34\x33'


class LSM303Minimal:
    """LSM303DLHC accelerometer and magnetometer — minimal interface.

    Reads acceleration in *g* and magnetic flux density in µT with sensible
    defaults; no configuration beyond the two connections is required.

    Construction runs the initialization sequence:
        - Magnetometer identity check (IRA/IRB/IRC = 48 34 33); raises
          ValueError on mismatch.
        - Accelerometer: ±2 *g*, 100 Hz, high-resolution mode, BDU = 1, all
          axes enabled, high-pass filter / FIFO / interrupt routing cleared.
        - Magnetometer: ±130 µT, 15 Hz, continuous conversion, temperature
          sensor off.

    Args:
        accel_connection: RegisterConnection bound to the accelerometer
            (I²C address 0x19), 1-byte register addressing.
        mag_connection: RegisterConnection bound to the magnetometer
            (I²C address 0x1E), 1-byte register addressing. Both connections
            must be on the same bus.

    Raises:
        ValueError: If the magnetometer identity does not read 48 34 33.
    """

    _MAG_ID = _MAG_ID

    def __init__(self, accel_connection, mag_connection):
        self._accel = accel_connection
        self._mag = mag_connection
        # Cached configuration, kept in step with the device by the setters.
        self._range_g = 2
        self._mode = 2
        self._odr_hz = 100
        self._powered = True
        self._mag_range_ut = 130
        self._mag_gain_xy = 1100
        self._mag_gain_z = 980
        self._mag_rate_hz = 15.0
        self._temp_enabled = False
        self._init()

    def _init(self):
        _sleep_ms(5)
        ident = bytes(self._mag.read_reg(_REG_IRA_REG_M, 3))
        if ident != _MAG_ID:
            raise ValueError('LSM303 magnetometer ID: expected 48 34 33, got {}'.format(
                ' '.join('{:02X}'.format(b) for b in ident)))
        # No soft reset on this part: write every control register to a known value.
        self._acc_write(_REG_CTRL_REG2_A, 0x00)
        self._acc_write(_REG_CTRL_REG3_A, 0x00)
        self._acc_write(_REG_CTRL_REG5_A, 0x00)
        self._acc_write(_REG_CTRL_REG6_A, 0x00)
        self._acc_write(_REG_FIFO_CTRL_REG_A, 0x00)
        # BDU = 1, FS = ±2 g, HR = 1 (high-resolution).
        self._acc_write(_REG_CTRL_REG4_A, 0x88)
        # ODR = 100 Hz, LPen = 0, X/Y/Z enabled — leaves power-down.
        self._acc_write(_REG_CTRL_REG1_A, 0x57)
        self._mag.write_reg(_REG_CRA_REG_M, 0x10)   # 15 Hz, temperature off
        self._mag.write_reg(_REG_CRB_REG_M, 0x20)   # ±1.3 gauss
        self._mag.write_reg(_REG_MR_REG_M, 0x00)    # continuous conversion (reset value is sleep)
        # 7/ODR turn-on for the accelerometer (70 ms at 100 Hz); also covers the
        # first magnetometer sample (1/15 Hz ≈ 67 ms).
        _sleep_ms(70)

    # ---- bus helpers ------------------------------------------------------

    def _acc_read(self, reg, length):
        # Bit 7 of the sub-address enables auto-increment for multi-byte bursts.
        sub = (reg | 0x80) if length > 1 else reg
        return self._accel.read_reg(sub, length)

    def _acc_write(self, reg, value):
        self._accel.write_reg(reg, value & 0xFF)

    def _acc_rmw(self, reg, mask, value):
        cur = self._acc_read(reg, 1)[0]
        self._acc_write(reg, (cur & ~mask) | (value & mask))

    def _mag_rmw(self, reg, mask, value):
        cur = self._mag.read_reg(reg, 1)[0]
        self._mag.write_reg(reg, ((cur & ~mask) | (value & mask)) & 0xFF)

    # ---- conversion helpers -----------------------------------------------

    def _read_accel_raw16(self):
        raw = self._acc_read(_REG_OUT_X_L_A, 6)
        return struct.unpack('<hhh', raw)

    def _counts_to_g(self, raw16):
        shift = _MODE_SHIFT[self._mode]
        sens_mg = _HR_MG_PER_LSB[self._range_g] * _MODE_MG_MULT[self._mode]
        return tuple(((v >> shift) * sens_mg) / 1000.0 for v in raw16)

    # ---- public API -------------------------------------------------------

    def read_acceleration(self):
        """Read 3-axis acceleration.

        Burst-reads OUT_X_L_A..OUT_Z_H_A (0x28–0x2D) in one transaction so the
        three axes come from the same sample (BDU = 1).

        Returns:
            tuple: (x, y, z) acceleration in *g*.
        """
        return self._counts_to_g(self._read_accel_raw16())

    def read_magnetic(self):
        """Read 3-axis magnetic flux density.

        Burst-reads OUT_X_H_M..OUT_Y_L_M (0x03–0x08). The chip returns the
        axes as X, Z, Y; this method reorders them to X, Y, Z.

        Returns:
            tuple: (x, y, z) magnetic flux density in µT.
        """
        raw = self._mag.read_reg(_REG_OUT_X_H_M, 6)
        x, z, y = struct.unpack('>hhh', raw)
        return (x * 100.0 / self._mag_gain_xy,
                y * 100.0 / self._mag_gain_xy,
                z * 100.0 / self._mag_gain_z)


class LSM303Source:
    """Interrupt source constants for LSM303Full.enable_interrupt() / disable_interrupt().

    The values equal the bit positions reported by LSM303Full.poll_interrupt().
    SOURCE_BOOT is pin-only (no register flag), so poll_interrupt() never
    reports it. SOURCE_MAG_DATA_READY is reported by poll_interrupt() but is
    always on the dedicated DRDY pin, so enable_interrupt() rejects it.
    """

    SOURCE_DATA_READY = 0x0001      # New accelerometer sample (INT1).
    SOURCE_WATERMARK = 0x0002       # FIFO content exceeds watermark (INT1).
    SOURCE_OVERRUN = 0x0004         # FIFO overrun (INT1).
    SOURCE_IA1 = 0x0008             # Inertial generator 1 event (INT1 or INT2).
    SOURCE_IA2 = 0x0010             # Inertial generator 2 event (INT1 or INT2).
    SOURCE_CLICK = 0x0020           # Single / double click (INT1 or INT2).
    SOURCE_BOOT = 0x0040            # Boot running (INT2 only, pin-only).
    SOURCE_MAG_DATA_READY = 0x0080  # Magnetometer sample (DRDY pin, not routable).


# Routing for each routable source: ((CTRL register, bit) for INT1, (register, bit) for INT2).
# INT1-only / INT2-only sources use the same entry on both pins, so the pin argument is ignored.
_SOURCE_ROUTES = {
    LSM303Source.SOURCE_DATA_READY: ((_REG_CTRL_REG3_A, 4), (_REG_CTRL_REG3_A, 4)),
    LSM303Source.SOURCE_WATERMARK: ((_REG_CTRL_REG3_A, 2), (_REG_CTRL_REG3_A, 2)),
    LSM303Source.SOURCE_OVERRUN: ((_REG_CTRL_REG3_A, 1), (_REG_CTRL_REG3_A, 1)),
    LSM303Source.SOURCE_IA1: ((_REG_CTRL_REG3_A, 6), (_REG_CTRL_REG6_A, 6)),
    LSM303Source.SOURCE_IA2: ((_REG_CTRL_REG3_A, 5), (_REG_CTRL_REG6_A, 5)),
    LSM303Source.SOURCE_CLICK: ((_REG_CTRL_REG3_A, 7), (_REG_CTRL_REG6_A, 7)),
    LSM303Source.SOURCE_BOOT: ((_REG_CTRL_REG6_A, 4), (_REG_CTRL_REG6_A, 4)),
}


def _spread_click_mask(mask):
    # Axis mask (X=1, Y=2, Z=4) -> CLICK_CFG_A single-click bits (XS=0, YS=2, ZS=4).
    return (mask & 1) | ((mask & 2) << 1) | ((mask & 4) << 2)


class LSM303Full(LSM303Minimal):
    """LSM303DLHC full interface — extends LSM303Minimal with complete functionality.

    Adds accelerometer range, data rate, operating mode and axis control;
    high-pass filter, reference and FIFO configuration; two inertial
    interrupt generators (threshold, free-fall, 6D / 4D orientation, latch);
    single and double click detection; magnetometer range, data rate and
    mode; relative temperature; software compass heading; interrupt routing
    with on_interrupt / poll_interrupt; and BOOT reboot.

    Interrupts: the connection's ``int_pin`` (if present) is used for edge
    delivery; otherwise a 5 ms polling thread is started. Callbacks receive
    the combined status int from poll_interrupt() and are only called when
    that status is non-zero. The connection carries one INT line, so callbacks
    registered for pin 1 and pin 2 share it and receive the same status.

    Args:
        accel_connection: RegisterConnection bound to the accelerometer (0x19).
        mag_connection: RegisterConnection bound to the magnetometer (0x1E).
    """

    SOURCE_DATA_READY = LSM303Source.SOURCE_DATA_READY
    SOURCE_WATERMARK = LSM303Source.SOURCE_WATERMARK
    SOURCE_OVERRUN = LSM303Source.SOURCE_OVERRUN
    SOURCE_IA1 = LSM303Source.SOURCE_IA1
    SOURCE_IA2 = LSM303Source.SOURCE_IA2
    SOURCE_CLICK = LSM303Source.SOURCE_CLICK
    SOURCE_BOOT = LSM303Source.SOURCE_BOOT
    SOURCE_MAG_DATA_READY = LSM303Source.SOURCE_MAG_DATA_READY

    def __init__(self, accel_connection, mag_connection):
        super().__init__(accel_connection, mag_connection)
        self._callbacks = {}
        self._listening = False
        self._edge_pin = None
        self._edge_handler = None
        self._poll_stop = None

    def _gen_regs(self, generator):
        if generator == 1:
            return (_REG_INT1_CFG_A, _REG_INT1_THS_A, _REG_INT1_DURATION_A)
        if generator == 2:
            return (_REG_INT2_CFG_A, _REG_INT2_THS_A, _REG_INT2_DURATION_A)
        raise ValueError('generator must be 1 or 2')

    def _threshold_code(self, threshold_g):
        # 7-bit, 1 LSb = full-scale / 128.
        return _round_clamp(threshold_g * 128.0 / self._range_g, 0, 127)

    def _time_code(self, time_ms, bits):
        # 1 LSb = 1 / ODR.
        return _round_clamp(time_ms * self._odr_hz / 1000.0, 0, (1 << bits) - 1)

    # ---- accelerometer configuration -------------------------------------

    def set_range(self, range_g):
        """Set the accelerometer full-scale range.

        Args:
            range_g: 2, 4, 8 or 16 (*g*). Updates the cached sensitivity.

        Raises:
            ValueError: If range_g is not one of 2, 4, 8, 16.
        """
        if range_g not in _FS_CODE:
            raise ValueError('range_g must be one of 2, 4, 8, 16')
        self._acc_rmw(_REG_CTRL_REG4_A, 0x30, _FS_CODE[range_g] << 4)
        self._range_g = range_g

    def set_data_rate(self, rate_hz):
        """Set the accelerometer output data rate (0 powers the device down).

        Args:
            rate_hz: 0 (power-down), 1, 10, 25, 50, 100, 200, 400, 1344, 1620 or 5376 (Hz).
                1620 and 5376 Hz require low-power mode; 1344 Hz requires normal or
                high-resolution mode.

        Raises:
            ValueError: If the rate is not supported or not allowed in the current mode.
        """
        if rate_hz not in _ODR_CODES:
            raise ValueError('rate_hz must be one of 0, 1, 10, 25, 50, 100, 200, 400, 1344, 1620, 5376')
        if rate_hz in (1620, 5376) and self._mode != 0:
            raise ValueError('{} Hz is only available in low-power mode'.format(rate_hz))
        if rate_hz == 1344 and self._mode == 0:
            raise ValueError('1344 Hz is not available in low-power mode')
        self._acc_rmw(_REG_CTRL_REG1_A, 0xF0, _ODR_CODES[rate_hz] << 4)
        self._powered = rate_hz != 0
        if rate_hz != 0:
            self._odr_hz = rate_hz

    def set_mode(self, mode):
        """Set the accelerometer operating mode (LPen / HR).

        Args:
            mode: 0 low-power (8-bit, LPen = 1), 1 normal (10-bit, LPen = 0, HR = 0),
                2 high-resolution (12-bit, HR = 1).

        Raises:
            ValueError: If mode is invalid or the current data rate is not allowed in it.
        """
        if mode not in (0, 1, 2):
            raise ValueError('mode must be 0 (low-power), 1 (normal), or 2 (high-resolution)')
        if self._powered:
            if mode != 0 and self._odr_hz in (1620, 5376):
                raise ValueError('current ODR {} Hz is only available in low-power mode'.format(self._odr_hz))
            if mode == 0 and self._odr_hz == 1344:
                raise ValueError('current ODR 1344 Hz is not available in low-power mode')
        self._acc_rmw(_REG_CTRL_REG1_A, 0x08, 0x08 if mode == 0 else 0x00)
        self._acc_rmw(_REG_CTRL_REG4_A, 0x08, 0x08 if mode == 2 else 0x00)
        self._mode = mode

    def set_axes(self, x=True, y=True, z=True):
        """Enable or disable each accelerometer axis.

        Args:
            x: True to enable the X axis.
            y: True to enable the Y axis.
            z: True to enable the Z axis.
        """
        self._acc_rmw(_REG_CTRL_REG1_A, 0x07, (_b(z) << 2) | (_b(y) << 1) | _b(x))

    def power_down(self):
        """Power the accelerometer down (ODR = 0000). Register contents are retained."""
        self._acc_rmw(_REG_CTRL_REG1_A, 0xF0, 0x00)
        self._powered = False

    def read_acceleration_raw(self):
        """Read signed sample counts at the current resolution.

        Returns:
            tuple: (x, y, z) signed integer counts (shifted by the mode's resolution).
        """
        shift = _MODE_SHIFT[self._mode]
        return tuple(v >> shift for v in self._read_accel_raw16())

    def new_data_available(self):
        """Check whether a new accelerometer sample is available (ZYXDA).

        Returns:
            bool: True if STATUS_REG_A.ZYXDA is set.
        """
        return bool(self._acc_read(_REG_STATUS_REG_A, 1)[0] & 0x08)

    def read_status(self):
        """Read STATUS_REG_A.

        Returns:
            int: Status byte (ZYXOR, ZOR, YOR, XOR, ZYXDA, ZDA, YDA, XDA).
        """
        return self._acc_read(_REG_STATUS_REG_A, 1)[0]

    # ---- high-pass filter -------------------------------------------------

    def set_high_pass(self, mode=0, cutoff=0, filter_output=False, on_ia1=False, on_ia2=False, on_click=False):
        """Write CTRL_REG2_A (high-pass filter configuration).

        Args:
            mode: HPM<1:0> filter mode — 0 normal (reset by reading REFERENCE_A),
                1 reference filtering, 2 normal, 3 autoreset on interrupt.
            cutoff: HPCF<2:1> cutoff selection, 0–3 (no Hz values documented).
            filter_output: True to send filtered data to the output registers and FIFO.
            on_ia1: True to apply the high-pass filter to interrupt generator 1.
            on_ia2: True to apply the high-pass filter to interrupt generator 2.
            on_click: True to apply the high-pass filter to click detection.

        Raises:
            ValueError: If mode or cutoff is out of range.
        """
        if mode not in (0, 1, 2, 3):
            raise ValueError('mode must be 0–3')
        if cutoff not in (0, 1, 2, 3):
            raise ValueError('cutoff must be 0–3')
        value = ((mode << 6) | (cutoff << 4) | (_b(filter_output) << 3) |
                 (_b(on_click) << 2) | (_b(on_ia2) << 1) | _b(on_ia1))
        self._acc_write(_REG_CTRL_REG2_A, value)

    def reset_high_pass(self):
        """Read REFERENCE_A (resets the filter when HPM = 00).

        Returns:
            int: REFERENCE_A value.
        """
        return self._acc_read(_REG_REFERENCE_A, 1)[0]

    def set_reference(self, value):
        """Write REFERENCE_A (high-pass reference for HPM = 01).

        Args:
            value: Reference value, 0–255.

        Raises:
            ValueError: If value is out of range.
        """
        if value < 0 or value > 0xFF:
            raise ValueError('value must be 0–255')
        self._acc_write(_REG_REFERENCE_A, value)

    # ---- FIFO -------------------------------------------------------------

    def set_fifo(self, mode, watermark=0, trigger_int2=False):
        """Configure the 32-level FIFO.

        Writes FIFO_CTRL_REG_A, then sets FIFO_EN in CTRL_REG5_A for modes 1–3
        and clears it for bypass. Returning to bypass clears the buffer.

        Args:
            mode: 0 bypass, 1 FIFO, 2 stream, 3 trigger (stream-to-FIFO).
            watermark: Watermark level, 0–31.
            trigger_int2: Trigger source for mode 3 — False = INT1, True = INT2.

        Raises:
            ValueError: If mode or watermark is out of range.
        """
        if mode not in (0, 1, 2, 3):
            raise ValueError('mode must be 0 (bypass), 1 (FIFO), 2 (stream), or 3 (trigger)')
        if watermark < 0 or watermark > 31:
            raise ValueError('watermark must be 0–31')
        self._acc_write(_REG_FIFO_CTRL_REG_A, (mode << 6) | (_b(trigger_int2) << 5) | watermark)
        self._acc_rmw(_REG_CTRL_REG5_A, 0x40, 0x40 if mode != 0 else 0x00)

    def fifo_level(self):
        """Read the number of unread FIFO sample sets.

        Returns:
            int: FSS<4:0>, 0–32.
        """
        return self._acc_read(_REG_FIFO_SRC_REG_A, 1)[0] & 0x1F

    def read_fifo(self, count=None):
        """Drain FIFO sample sets, one 6-byte burst per sample.

        Args:
            count: Maximum number of sample sets to read. None reads all
                unread sets (FSS).

        Returns:
            list: Up to ``count`` (x, y, z) tuples in *g*.
        """
        level = self.fifo_level()
        n = level if count is None else min(count, level)
        return [self._counts_to_g(self._read_accel_raw16()) for _ in range(n)]

    def read_fifo_status(self):
        """Decode FIFO_SRC_REG_A.

        Returns:
            tuple: (watermark, overrun, empty, level) — three bools and the FSS count.
        """
        v = self._acc_read(_REG_FIFO_SRC_REG_A, 1)[0]
        return (bool(v & 0x80), bool(v & 0x40), bool(v & 0x20), v & 0x1F)

    # ---- inertial interrupt generators ------------------------------------

    def set_motion_interrupt(self, generator, events, threshold_g, duration_ms=0, and_mode=False):
        """Configure an inertial interrupt generator (OR / AND of axis events).

        Writes INTx_CFG_A (events, AOI), INTx_THS_A and INTx_DURATION_A. This
        also clears 6D mode. Route the generator with enable_interrupt().

        Args:
            generator: 1 or 2.
            events: 6-bit event mask ZHIE, ZLIE, YHIE, YLIE, XHIE, XLIE (bit 5 to bit 0).
            threshold_g: Threshold in *g* (1 LSb = full-scale / 128, clamped to 0–127).
            duration_ms: Minimum duration in ms (1 LSb = 1/ODR, clamped to 0–127).
            and_mode: True for AND combination of the enabled events, False for OR.

        Raises:
            ValueError: If generator or events is out of range.
        """
        cfg_reg, ths_reg, dur_reg = self._gen_regs(generator)
        if events < 0 or events > 0x3F:
            raise ValueError('events must be a 6-bit mask')
        self._acc_write(cfg_reg, (0x80 if and_mode else 0x00) | events)
        self._acc_write(ths_reg, self._threshold_code(threshold_g))
        self._acc_write(dur_reg, self._time_code(duration_ms, 7))

    def set_free_fall(self, generator=1, threshold_g=0.35, duration_ms=30):
        """Configure free-fall detection: all three axes low, AND combination.

        Equivalent to set_motion_interrupt(generator, 0x15, threshold_g, duration_ms, True),
        which writes INTx_CFG_A = 0x95.

        Args:
            generator: 1 or 2.
            threshold_g: Threshold in *g* (default 0.35).
            duration_ms: Minimum duration in ms (default 30).
        """
        self.set_motion_interrupt(generator, 0x15, threshold_g, duration_ms, True)

    def set_orientation(self, generator=1, position=False, four_d=False):
        """Configure 6D orientation recognition on a generator.

        Writes INTx_CFG_A with 6D set and all six direction zones enabled.
        The threshold and duration registers are left unchanged, so the zone
        boundary is whatever the device currently holds.

        Args:
            generator: 1 or 2.
            position: False for 6D movement recognition (one ODR-period pulse),
                True for 6D position recognition (level while in a zone).
            four_d: True to enable 4D (Z-axis zone disabled) via D4D_INTx.
        """
        cfg_reg, _, _ = self._gen_regs(generator)
        self._acc_write(cfg_reg, (0x80 if position else 0x00) | 0x40 | 0x3F)
        d4d_mask = 0x04 if generator == 1 else 0x01
        self._acc_rmw(_REG_CTRL_REG5_A, d4d_mask, d4d_mask if four_d else 0x00)

    def set_latch(self, generator, enabled):
        """Latch a generator's interrupt until its INTx_SOURCE_A register is read.

        Args:
            generator: 1 (LIR_INT1) or 2 (LIR_INT2).
            enabled: True to latch, False for self-clearing.
        """
        if generator == 1:
            mask = 0x08
        elif generator == 2:
            mask = 0x02
        else:
            raise ValueError('generator must be 1 or 2')
        self._acc_rmw(_REG_CTRL_REG5_A, mask, mask if enabled else 0x00)

    def read_interrupt_source(self, generator):
        """Read INTx_SOURCE_A. Reading clears the IA flag and the INT pin.

        Args:
            generator: 1 or 2.

        Returns:
            int: INTx_SOURCE_A byte (IA = bit 6; ZH/ZL/YH/YL/XH/XL in bits 5–0).
        """
        if generator == 1:
            return self._acc_read(_REG_INT1_SOURCE_A, 1)[0]
        if generator == 2:
            return self._acc_read(_REG_INT2_SOURCE_A, 1)[0]
        raise ValueError('generator must be 1 or 2')

    # ---- click detection --------------------------------------------------

    def set_click(self, single=0, double=0, threshold_g=0.5, time_limit_ms=10, latency_ms=20, window_ms=50):
        """Configure single and double click detection.

        Args:
            single: Axis mask for single click (X=1, Y=2, Z=4).
            double: Axis mask for double click (X=1, Y=2, Z=4).
            threshold_g: Click threshold in *g* (1 LSb = full-scale / 128).
            time_limit_ms: Click time limit in ms (1 LSb = 1/ODR, 7 bits).
            latency_ms: Latency after the first click in ms (1 LSb = 1/ODR, 8 bits).
            window_ms: Double-click window in ms (1 LSb = 1/ODR, 8 bits).

        Raises:
            ValueError: If an axis mask is outside 0–7.
        """
        if single < 0 or single > 7 or double < 0 or double > 7:
            raise ValueError('single and double must be axis masks 0–7')
        cfg = _spread_click_mask(single) | (_spread_click_mask(double) << 1)
        self._acc_write(_REG_CLICK_CFG_A, cfg)
        self._acc_write(_REG_CLICK_THS_A, self._threshold_code(threshold_g))
        self._acc_write(_REG_TIME_LIMIT_A, self._time_code(time_limit_ms, 7))
        self._acc_write(_REG_TIME_LATENCY_A, self._time_code(latency_ms, 8))
        self._acc_write(_REG_TIME_WINDOW_A, self._time_code(window_ms, 8))

    def read_click_source(self):
        """Read CLICK_SRC_A. Reading clears the click flags.

        Returns:
            int: CLICK_SRC_A byte (IA = bit 6, DCLICK = bit 5, SCLICK = bit 4, sign, Z, Y, X).
        """
        return self._acc_read(_REG_CLICK_SRC_A, 1)[0]

    def set_interrupt_polarity(self, active_low):
        """Set the polarity of INT1 and INT2 (CTRL_REG6_A H_LACTIVE).

        Args:
            active_low: True for active-low, False for active-high (default).
        """
        self._acc_rmw(_REG_CTRL_REG6_A, 0x02, 0x02 if active_low else 0x00)

    # ---- magnetometer -----------------------------------------------------

    def set_mag_range(self, range_ut):
        """Set the magnetometer full-scale range (CRB_REG_M GN<2:0>).

        Args:
            range_ut: 130, 190, 250, 400, 470, 560 or 810 (µT, i.e. ±1.3 … ±8.1 gauss).

        Raises:
            ValueError: If range_ut is not supported.
        """
        if range_ut not in _MAG_RANGE:
            raise ValueError('range_ut must be one of 130, 190, 250, 400, 470, 560, 810')
        code, gain_xy, gain_z = _MAG_RANGE[range_ut]
        self._mag.write_reg(_REG_CRB_REG_M, code << 5)
        self._mag_range_ut = range_ut
        self._mag_gain_xy = gain_xy
        self._mag_gain_z = gain_z

    def set_mag_data_rate(self, rate_hz):
        """Set the magnetometer output data rate (CRA_REG_M DO<2:0>, keeps TEMP_EN).

        Args:
            rate_hz: 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220 (Hz).

        Raises:
            ValueError: If rate_hz is not supported.
        """
        if rate_hz not in _MAG_RATE:
            raise ValueError('rate_hz must be one of 0.75, 1.5, 3, 7.5, 15, 30, 75, 220')
        self._mag_rmw(_REG_CRA_REG_M, 0x1C, _MAG_RATE[rate_hz] << 2)
        self._mag_rate_hz = float(rate_hz)

    def set_mag_mode(self, mode):
        """Set the magnetometer operating mode (MR_REG_M MD<1:0>).

        Args:
            mode: 0 continuous conversion, 1 single conversion (then sleep), 2 sleep.

        Raises:
            ValueError: If mode is invalid.
        """
        if mode not in (0, 1, 2):
            raise ValueError('mode must be 0 (continuous), 1 (single), or 2 (sleep)')
        self._mag.write_reg(_REG_MR_REG_M, mode)

    def read_magnetic_raw(self):
        """Read signed magnetometer counts.

        Returns:
            tuple: (x, y, z) signed 16-bit counts, reordered from the chip's X, Z, Y.
        """
        raw = self._mag.read_reg(_REG_OUT_X_H_M, 6)
        x, z, y = struct.unpack('>hhh', raw)
        return (x, y, z)

    def mag_data_ready(self):
        """Check whether a new magnetometer sample is available (SR_REG_M.DRDY).

        Returns:
            bool: True if DRDY is set.
        """
        return bool(self._mag.read_reg(_REG_SR_REG_M, 1)[0] & 0x01)

    def read_mag_status(self):
        """Decode SR_REG_M.

        Returns:
            tuple: (drdy, lock) — two bools.
        """
        v = self._mag.read_reg(_REG_SR_REG_M, 1)[0]
        return (bool(v & 0x01), bool(v & 0x02))

    def read_mag_identity(self):
        """Read the magnetometer identification registers IRA, IRB, IRC.

        Returns:
            bytes: Three bytes, normally b'\\x48\\x34\\x33'.
        """
        return bytes(self._mag.read_reg(_REG_IRA_REG_M, 3))

    def enable_temperature(self, enabled):
        """Enable or disable the magnetometer temperature sensor (CRA_REG_M TEMP_EN).

        Args:
            enabled: True to enable the sensor.
        """
        self._mag_rmw(_REG_CRA_REG_M, 0x80, 0x80 if enabled else 0x00)
        self._temp_enabled = bool(enabled)

    def read_temperature_delta(self):
        """Read the relative temperature.

        Enables TEMP_EN on first use and waits one magnetometer output period
        before reading. The value is relative to an unspecified reference
        (8 LSB/°C); it is not an absolute temperature.

        Returns:
            float: Temperature delta in °C.
        """
        if not self._temp_enabled:
            self.enable_temperature(True)
            _sleep_ms(int(1000.0 / self._mag_rate_hz) + 1)
        raw = self._mag.read_reg(_REG_TEMP_OUT_H_M, 2)
        v = struct.unpack('>h', raw)[0] >> 4
        return v / 8.0

    def heading(self, tilt_compensate=True):
        """Compute the compass heading in degrees.

        Uses atan2(y, x), wrapped to 0–360. With tilt_compensate, roll and pitch
        are derived from the accelerometer and the magnetic field is rotated into
        the horizontal plane. Assumes both sensors report in the same body frame.

        Args:
            tilt_compensate: True to read both sensors and compensate for tilt
                (default), False to use the magnetometer alone.

        Returns:
            float: Heading in degrees, 0–360.
        """
        mx, my, mz = self.read_magnetic()
        if not tilt_compensate:
            return (math.atan2(my, mx) * 180.0 / math.pi) % 360.0
        ax, ay, az = self.read_acceleration()
        roll = math.atan2(ay, az)
        pitch = math.atan2(-ax, ay * math.sin(roll) + az * math.cos(roll))
        xh = mx * math.cos(pitch) + mz * math.sin(pitch)
        yh = (mx * math.sin(roll) * math.sin(pitch) + my * math.cos(roll)
              - mz * math.sin(roll) * math.cos(pitch))
        return (math.atan2(yh, xh) * 180.0 / math.pi) % 360.0

    # ---- interrupts -------------------------------------------------------

    def enable_interrupt(self, source, pin=1):
        """Route one interrupt source to INT1 or INT2.

        Enables the routing bit only; configure the generator or function behind
        it with the set_* methods. ``pin`` is ignored for INT1-only and INT2-only
        sources.

        Args:
            source: One of LSM303Source.SOURCE_* (not SOURCE_MAG_DATA_READY).
            pin: 1 or 2.

        Raises:
            ValueError: If the source is unknown or not routable, or pin is not 1 or 2.
        """
        self._set_route(source, pin, True)

    def disable_interrupt(self, source, pin=1):
        """Remove one interrupt source from INT1 or INT2.

        Args:
            source: One of LSM303Source.SOURCE_* (not SOURCE_MAG_DATA_READY).
            pin: 1 or 2.

        Raises:
            ValueError: If the source is unknown or not routable, or pin is not 1 or 2.
        """
        self._set_route(source, pin, False)

    def _set_route(self, source, pin, enable):
        _check_pin(pin)
        if source == LSM303Source.SOURCE_MAG_DATA_READY:
            raise ValueError('SOURCE_MAG_DATA_READY is on the DRDY pin and cannot be routed')
        if source not in _SOURCE_ROUTES:
            raise ValueError('unknown interrupt source: 0x{:04X}'.format(source))
        reg, bit = _SOURCE_ROUTES[source][pin - 1]
        mask = 1 << bit
        self._acc_rmw(reg, mask, mask if enable else 0x00)

    def poll_interrupt(self):
        """Read the source registers of every enabled source and return the combined status.

        Reading INTx_SOURCE_A and CLICK_SRC_A clears their latched flags.
        SOURCE_BOOT is never reported.

        Returns:
            int: Status bits as LSM303Source.SOURCE_* values, OR-ed together.
        """
        ctrl3 = self._acc_read(_REG_CTRL_REG3_A, 1)[0]
        ctrl6 = self._acc_read(_REG_CTRL_REG6_A, 1)[0]
        status = 0
        if ctrl3 & 0x10 and self._acc_read(_REG_STATUS_REG_A, 1)[0] & 0x08:
            status |= LSM303Source.SOURCE_DATA_READY
        if ctrl3 & 0x06:
            fifo = self._acc_read(_REG_FIFO_SRC_REG_A, 1)[0]
            if ctrl3 & 0x04 and fifo & 0x80:
                status |= LSM303Source.SOURCE_WATERMARK
            if ctrl3 & 0x02 and fifo & 0x40:
                status |= LSM303Source.SOURCE_OVERRUN
        if (ctrl3 | ctrl6) & 0x40 and self._acc_read(_REG_INT1_SOURCE_A, 1)[0] & 0x40:
            status |= LSM303Source.SOURCE_IA1
        if (ctrl3 | ctrl6) & 0x20 and self._acc_read(_REG_INT2_SOURCE_A, 1)[0] & 0x40:
            status |= LSM303Source.SOURCE_IA2
        if (ctrl3 | ctrl6) & 0x80 and self._acc_read(_REG_CLICK_SRC_A, 1)[0] & 0x40:
            status |= LSM303Source.SOURCE_CLICK
        if self._mag.read_reg(_REG_SR_REG_M, 1)[0] & 0x01:
            status |= LSM303Source.SOURCE_MAG_DATA_READY
        return status

    def on_interrupt(self, callback, pin=1):
        """Subscribe to interrupt assertions.

        Uses the connection's int_pin (edge delivery, on any change) when
        present, otherwise a 5 ms polling thread. The callback is called with
        the poll_interrupt() status int, but only when that status is non-zero.
        Calling this again for the same pin replaces its callback.

        Args:
            callback: Function called with one int argument (the status).
            pin: 1 or 2. Both pins share the connection's INT line.

        Raises:
            ValueError: If pin is not 1 or 2.
        """
        _check_pin(pin)
        self._callbacks[pin] = callback
        if self._listening:
            return
        self._listening = True
        int_pin = getattr(self._accel, 'int_pin', None)
        if int_pin is not None and hasattr(int_pin, 'on_edge'):
            self._edge_pin = int_pin
            self._edge_handler = self._make_edge_handler()
            int_pin.on_edge(self._edge_handler, getattr(int_pin, 'CHANGE', 3))
        else:
            self._poll_stop = [False]
            self._start_poll_thread(self._poll_stop)

    def off_interrupt(self, pin=1):
        """Unsubscribe the callback for one pin.

        The edge handler or polling thread is removed once no pins remain subscribed.

        Args:
            pin: 1 or 2.

        Raises:
            ValueError: If pin is not 1 or 2.
        """
        _check_pin(pin)
        self._callbacks.pop(pin, None)
        if self._callbacks or not self._listening:
            return
        if self._edge_pin is not None:
            self._edge_pin.off_edge(self._edge_handler)
            self._edge_pin = None
            self._edge_handler = None
        if self._poll_stop is not None:
            self._poll_stop[0] = True
            self._poll_stop = None
        self._listening = False

    def _make_edge_handler(self):
        # InputPin handlers are called with no arguments; keep one object so off_edge() can remove it.
        drv = self

        def _handler():
            drv._dispatch()

        return _handler

    def _start_poll_thread(self, stop):
        import _thread
        drv = self

        def _loop():
            while not stop[0]:
                try:
                    drv._dispatch()
                except Exception:
                    # Bus errors in the background poll must not kill the thread.
                    pass
                _sleep_ms(5)

        _thread.start_new_thread(_loop, ())

    def _dispatch(self):
        if not self._callbacks:
            return
        status = self.poll_interrupt()
        if status:
            for cb in list(self._callbacks.values()):
                cb(status)

    # ---- reboot -----------------------------------------------------------

    def reboot(self):
        """Reboot the accelerometer memory (CTRL_REG5_A BOOT), then wait 5 ms.

        The datasheet does not say whether control registers survive a reboot,
        so construct a new driver instance afterwards to re-run the init sequence.
        """
        self._acc_rmw(_REG_CTRL_REG5_A, 0x80, 0x80)
        _sleep_ms(5)
