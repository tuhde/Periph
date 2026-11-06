"""FXOS8700CQ — 6-axis e-compass: 14-bit accelerometer + 16-bit magnetometer (NXP).

The FXOS8700CQ combines a three-axis MEMS accelerometer (±2 / ±4 / ±8 g,
0.244 / 0.488 / 0.976 mg/LSB) and a three-axis magnetometer (fixed ±1200 µT
range, 0.1 µT/LSB) behind **one I²C address** and **one shared ADC**. In
*hybrid mode* both sensors are sampled in an interleaved fashion at half the
programmed output data rate (ODR) and all twelve data bytes can be read in a
single burst. The accelerometer side adds a 32-sample FIFO, a high-pass
filtered output, embedded freefall / motion, transient, tap, orientation and
Auto-SLEEP / WAKE detectors; the magnetometer side adds hard-iron offsets,
an autonomous min / max tracker with hardware auto-calibration and a
relative die-temperature sensor.

I²C framing is the plain register-pointer protocol (write the register
address, repeated START, read). There is no auto-increment bit; multi-byte
transfers auto-increment within one transaction.

The chip is register-compatible with the NXP MMA845x family for registers
0x00-0x31; the magnetometer and hybrid registers live at 0x32-0x78.

The vector-magnitude detectors (0x5F-0x72), the magnetic threshold detector
(0x52-0x5A, ``M_INT_SRC``) and the per-axis freefall / motion thresholds
(0x73-0x78) are not wrapped by the API: use
:meth:`FXOS8700Full.read_register` / :meth:`FXOS8700Full.write_register`.

Args:
    connection: RegisterConnection (I²C) bound to 0x1C, 0x1D, 0x1E or 0x1F
        (selected by SA1 / SA0). Do not leave SA0 floating: a floating SA0 at
        power-up selects SPI mode.
"""

import math

from periph.connection.register import to_signed

#: I²C address for SA1 = 0, SA0 = 0 (default).
I2C_ADDRESS = 0x1E
#: All four selectable I²C addresses.
I2C_ADDRESSES = (0x1C, 0x1D, 0x1E, 0x1F)

# System output data rates selected by CTRL_REG1.dr (Hz).
_ODR_HZ = (800.0, 400.0, 200.0, 100.0, 50.0, 12.5, 6.25, 1.5625)

# Debounce time steps in ms (also PULSE_TMLT with the pulse LPF on), indexed
# [mods][row] with mods 0 = normal, 1 = low-noise low-power, 2 = high
# resolution, 3 = low power. ``row`` is the CTRL_REG1.dr code, shifted by one
# in hybrid mode because the effective ODR is half the system ODR.
_STEP_MS = (
    (1.25, 2.5, 5.0, 10.0, 20.0, 20.0, 20.0, 20.0),
    (1.25, 2.5, 5.0, 10.0, 20.0, 80.0, 80.0, 80.0),
    (1.25, 2.5, 2.5, 2.5, 2.5, 2.5, 2.5, 2.5),
    (1.25, 2.5, 5.0, 10.0, 20.0, 80.0, 160.0, 160.0),
)

# PULSE_TMLT steps with the pulse low-pass filter off (PULSE_LTCY and
# PULSE_WIND steps are always twice the PULSE_TMLT step).
_PULSE_STEP_NO_LPF_MS = (
    (0.625, 0.625, 1.25, 2.5, 5.0, 5.0, 5.0, 5.0),
    (0.625, 0.625, 1.25, 2.5, 5.0, 20.0, 20.0, 20.0),
    (0.625, 0.625, 0.625, 0.625, 0.625, 0.625, 0.625, 0.625),
    (0.625, 1.25, 2.5, 5.0, 10.0, 40.0, 40.0, 40.0),
)

# Orientation trip angle (degrees) -> P_L_THS<4:0>.
_PL_THRESHOLDS = {15: 0x07, 20: 0x09, 30: 0x0C, 35: 0x0D, 40: 0x0F,
                  45: 0x10, 55: 0x13, 60: 0x14, 70: 0x17, 75: 0x19}

# Auto-SLEEP rates (Hz) -> CTRL_REG1.aslp_rate.
_ASLP_RATES = ((50.0, 0), (12.5, 1), (6.25, 2), (1.56, 3))


def _delay_ms(ms):
    """Sleep ``ms`` milliseconds, portable across MicroPython and CPython."""
    import time
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(int(ms))
    else:
        time.sleep(ms / 1000.0)


def _clamp(value, low, high):
    """Clamp ``value`` into ``[low, high]``."""
    return low if value < low else (high if value > high else value)


class FXOS8700Source:
    """Interrupt source constants for the FXOS8700.

    The values equal the bit positions of ``INT_SOURCE``, ``CTRL_REG4`` and
    ``CTRL_REG5``, so they are also the return mask of
    :meth:`FXOS8700Full.poll_interrupt`.
    """

    #: New sample (accelerometer in accel-only mode, magnetometer otherwise).
    SOURCE_DATA_READY = 0x01
    #: Accelerometer vector-magnitude change (configured via raw registers).
    SOURCE_ACCEL_VECM = 0x02
    #: Freefall / motion (``A_FFMT_SRC.ea``).
    SOURCE_FF_MT = 0x04
    #: Single / double tap (``PULSE_SRC.ea``).
    SOURCE_PULSE = 0x08
    #: Orientation change (``PL_STATUS.newlp``).
    SOURCE_ORIENTATION = 0x10
    #: Transient (``TRANSIENT_SRC.ea``).
    SOURCE_TRANSIENT = 0x20
    #: FIFO overflow or watermark.
    SOURCE_FIFO = 0x40
    #: WAKE <-> SLEEP transition.
    SOURCE_AUTO_SLEEP = 0x80


class FXOS8700Minimal:
    """FXOS8700 minimal interface — acceleration and magnetic field.

    The constructor runs the datasheet initialization sequence: it verifies
    ``WHO_AM_I`` (0xC7, or 0xC4 on pre-production parts), performs a software
    reset and starts hybrid mode.

    Default configuration:
        - Hybrid mode (accelerometer + magnetometer), system ODR 200 Hz
          (100 Hz per sensor), ±2 g (4096 counts/g), 14-bit output, normal
          accelerometer oversampling, magnetometer OSR 7, low-noise off,
          ``hyb_autoinc_mode`` on, no FIFO, no detectors, no interrupt routing,
          interrupts active-low push-pull.

    Args:
        connection: RegisterConnection (I²C) bound to 0x1C-0x1F.

    Raises:
        ValueError: If ``WHO_AM_I`` is neither 0xC7 nor 0xC4.
    """

    # --- Registers ---------------------------------------------------------
    _REG_STATUS = 0x00
    _REG_OUT_X_MSB = 0x01
    _REG_F_SETUP = 0x09
    _REG_TRIG_CFG = 0x0A
    _REG_SYSMOD = 0x0B
    _REG_INT_SOURCE = 0x0C
    _REG_WHO_AM_I = 0x0D
    _REG_XYZ_DATA_CFG = 0x0E
    _REG_HP_FILTER_CUTOFF = 0x0F
    _REG_PL_STATUS = 0x10
    _REG_PL_CFG = 0x11
    _REG_PL_COUNT = 0x12
    _REG_PL_BF_ZCOMP = 0x13
    _REG_PL_THS = 0x14
    _REG_A_FFMT_CFG = 0x15
    _REG_A_FFMT_SRC = 0x16
    _REG_A_FFMT_THS = 0x17
    _REG_A_FFMT_COUNT = 0x18
    _REG_TRANSIENT_CFG = 0x1D
    _REG_TRANSIENT_SRC = 0x1E
    _REG_TRANSIENT_THS = 0x1F
    _REG_TRANSIENT_COUNT = 0x20
    _REG_PULSE_CFG = 0x21
    _REG_PULSE_SRC = 0x22
    _REG_PULSE_THSX = 0x23
    _REG_PULSE_TMLT = 0x26
    _REG_PULSE_LTCY = 0x27
    _REG_PULSE_WIND = 0x28
    _REG_ASLP_COUNT = 0x29
    _REG_CTRL1 = 0x2A
    _REG_CTRL2 = 0x2B
    _REG_CTRL3 = 0x2C
    _REG_CTRL4 = 0x2D
    _REG_CTRL5 = 0x2E
    _REG_OFF_X = 0x2F
    _REG_M_DR_STATUS = 0x32
    _REG_M_OUT_X_MSB = 0x33
    _REG_M_OFF_X_MSB = 0x3F
    _REG_MAX_X_MSB = 0x45
    _REG_MIN_X_MSB = 0x4B
    _REG_TEMP = 0x51
    _REG_M_CTRL1 = 0x5B
    _REG_M_CTRL2 = 0x5C
    _REG_M_CTRL3 = 0x5D

    # --- Bit masks ---------------------------------------------------------
    _CTRL1_ACTIVE = 0x01
    _CTRL1_F_READ = 0x02
    _CTRL1_LNOISE = 0x04
    _CTRL1_DR_SHIFT = 3
    _CTRL1_DR_MASK = 0x38
    _CTRL1_ASLP_MASK = 0xC0
    _CTRL2_ST = 0x80
    _CTRL2_RST = 0x40
    _CTRL2_SMODS_MASK = 0x18
    _CTRL2_SLPE = 0x04
    _CTRL2_MODS_MASK = 0x03
    _STATUS_ZYXDR = 0x08
    _M_CTRL1_ACAL = 0x80
    _M_CTRL1_RST = 0x40
    _M_CTRL1_OST = 0x20
    _M_CTRL1_OS_MASK = 0x1C
    _M_CTRL1_HMS_MASK = 0x03
    _M_CTRL2_HYB_AUTOINC = 0x20

    _WHO_AM_I = (0xC7, 0xC4)

    #: Counts per g in 14-bit mode for ±2 / ±4 / ±8 g.
    _COUNTS_PER_G = {2: 4096.0, 4: 2048.0, 8: 1024.0}
    #: Magnetometer sensitivity, µT per LSB.
    _MAG_UT_PER_LSB = 0.1

    def __init__(self, connection):
        self._conn = connection
        # Cached configuration, kept in sync with the registers.
        self._mode = 2          # 0 accel only, 1 mag only, 2 hybrid
        self._dr = 2            # CTRL_REG1.dr code
        self._fs_g = 2
        self._mods = 0          # accelerometer oversampling (wake)
        self._lnoise = False
        self._f_read = False    # True = 8-bit fast read
        self._fifo_mode = 0
        self._hyb = True        # hyb_autoinc_mode
        self.init()

    # --- Initialization ----------------------------------------------------

    def init(self):
        """Run the datasheet initialization sequence.

        Verifies ``WHO_AM_I``, performs a software reset (the ``rst`` write is
        not acknowledged by the chip and the resulting NACK is ignored) and
        starts hybrid mode at a 200 Hz system ODR (100 Hz per sensor), ±2 g,
        magnetometer OSR 7.

        Raises:
            ValueError: If ``WHO_AM_I`` is neither 0xC7 nor 0xC4.
        """
        _delay_ms(1)
        if self._who_am_i() not in self._WHO_AM_I:
            raise ValueError('FXOS8700 not found: unexpected WHO_AM_I')
        self._soft_reset()
        if self._who_am_i() not in self._WHO_AM_I:
            raise ValueError('FXOS8700 not found after reset')
        self._conn.write_reg(self._REG_CTRL1, 0x00)
        self._conn.write_reg(self._REG_M_CTRL1, 0x1F)
        self._conn.write_reg(self._REG_M_CTRL2, self._M_CTRL2_HYB_AUTOINC)
        self._conn.write_reg(self._REG_XYZ_DATA_CFG, 0x00)
        self._conn.write_reg(self._REG_CTRL2, 0x00)
        self._conn.write_reg(self._REG_CTRL1, 0x11)
        self._mode = 2
        self._dr = 2
        self._fs_g = 2
        self._mods = 0
        self._lnoise = False
        self._f_read = False
        self._fifo_mode = 0
        self._hyb = True
        _delay_ms(self._turn_on_ms())

    def _who_am_i(self):
        """Read ``WHO_AM_I``, retrying on NACK for up to 10 ms."""
        for attempt in range(10):
            try:
                return self._conn.read_reg(self._REG_WHO_AM_I, 1)[0]
            except OSError:
                _delay_ms(1)
        return self._conn.read_reg(self._REG_WHO_AM_I, 1)[0]

    def _soft_reset(self):
        """Write ``CTRL_REG2.rst`` tolerating the missing ACK, then wait 2 ms."""
        try:
            self._conn.write_reg(self._REG_CTRL2, self._CTRL2_RST)
        except OSError:
            pass
        _delay_ms(2)

    # --- Measurements --------------------------------------------------------

    def read_accel(self):
        """Read the acceleration.

        Returns:
            tuple: ``(x, y, z)`` in g (one burst read of 0x01-0x06).
        """
        raw = self._read_accel_counts()
        scale = self._accel_scale()
        return (raw[0] / scale, raw[1] / scale, raw[2] / scale)

    def read_mag(self):
        """Read the magnetic flux density.

        Reading ``M_OUT_X_MSB`` first latches the X / Y / Z set, so the six
        bytes are always read as one burst.

        Returns:
            tuple: ``(x, y, z)`` in µT.
        """
        raw = self._read_mag_counts()
        k = self._MAG_UT_PER_LSB
        return (raw[0] * k, raw[1] * k, raw[2] * k)

    # --- Helpers shared with FXOS8700Full ---------------------------------

    def _accel_scale(self):
        """Counts per g at the current range and resolution."""
        cpg = self._COUNTS_PER_G[self._fs_g]
        return cpg / 64.0 if self._f_read else cpg

    def _decode_accel(self, data, offset=0):
        """Decode three accelerometer axes from ``data`` starting at ``offset``."""
        if self._f_read:
            return (to_signed(data[offset], 8), to_signed(data[offset + 1], 8),
                    to_signed(data[offset + 2], 8))
        out = []
        for i in range(3):
            raw16 = (data[offset + 2 * i] << 8) | data[offset + 2 * i + 1]
            out.append(to_signed(raw16, 16) >> 2)
        return tuple(out)

    def _read_accel_counts(self):
        """Read the signed accelerometer counts (14-bit, or 8-bit in fast read)."""
        n = 3 if self._f_read else 6
        return self._decode_accel(self._conn.read_reg(self._REG_OUT_X_MSB, n))

    @staticmethod
    def _decode_mag(data, offset=0):
        """Decode three magnetometer axes (16-bit, MSB first)."""
        out = []
        for i in range(3):
            raw16 = (data[offset + 2 * i] << 8) | data[offset + 2 * i + 1]
            out.append(to_signed(raw16, 16))
        return tuple(out)

    def _read_mag_counts(self):
        """Read the signed magnetometer counts."""
        return self._decode_mag(self._conn.read_reg(self._REG_M_OUT_X_MSB, 6))

    def _odr_hz(self):
        """System output data rate in Hz."""
        return _ODR_HZ[self._dr]

    def _turn_on_ms(self):
        """STANDBY -> ACTIVE turn-on time, 2 / ODR + 2 ms (rounded up)."""
        return int(2000.0 / self._odr_hz()) + 3


class FXOS8700Full(FXOS8700Minimal):
    """FXOS8700 full interface — the complete chip functionality.

    Methods that change a STANDBY-only register put the chip into STANDBY,
    write, and restore ACTIVE internally (waiting for the mode change and the
    turn-on time), so the caller never manages the mode. Every such change
    flushes the FIFO and resets the detectors' debounce counters.

    Args:
        connection: RegisterConnection (I²C) bound to 0x1C-0x1F.
    """

    MODE_ACCEL_ONLY = 0
    MODE_MAG_ONLY = 1
    MODE_HYBRID = 2

    FIFO_DISABLED = 0
    FIFO_CIRCULAR = 1
    FIFO_FILL = 2
    FIFO_TRIGGER = 3

    def __init__(self, connection):
        self._callback = None
        self._int_pin_used = None
        self._poll_stop = True
        self._poll_thread = None
        self._last_status = 0
        super().__init__(connection)

    # --- Mode / power --------------------------------------------------------

    def standby(self):
        """Enter STANDBY (``active`` = 0) and wait until ``SYSMOD`` reads 0.

        Leaving mag-only / hybrid mode can take up to 300 ms.
        """
        c1 = self._conn.read_reg(self._REG_CTRL1, 1)[0]
        self._conn.write_reg(self._REG_CTRL1, c1 & ~self._CTRL1_ACTIVE & 0xFF)
        self._wait_standby()

    def activate(self):
        """Enter ACTIVE (``active`` = 1) and wait the turn-on time."""
        c1 = self._conn.read_reg(self._REG_CTRL1, 1)[0]
        self._conn.write_reg(self._REG_CTRL1, c1 | self._CTRL1_ACTIVE)
        _delay_ms(self._turn_on_ms())

    def _wait_standby(self):
        """Poll ``SYSMOD`` until it reads 00 (up to 350 ms)."""
        for _ in range(350):
            if (self._conn.read_reg(self._REG_SYSMOD, 1)[0] & 0x03) == 0:
                return
            _delay_ms(1)
        raise OSError('FXOS8700 did not enter STANDBY')

    def _begin(self):
        """Enter STANDBY if ACTIVE; return whether it was ACTIVE."""
        c1 = self._conn.read_reg(self._REG_CTRL1, 1)[0]
        if c1 & self._CTRL1_ACTIVE:
            self._conn.write_reg(self._REG_CTRL1, c1 & ~self._CTRL1_ACTIVE & 0xFF)
            self._wait_standby()
            return True
        return False

    def _end(self, was_active):
        """Restore ACTIVE if ``was_active``."""
        if was_active:
            self.activate()

    def _modify(self, reg, mask, value):
        """Read-modify-write ``reg``: clear ``mask``, OR in ``value``."""
        cur = self._conn.read_reg(reg, 1)[0]
        self._conn.write_reg(reg, (cur & ~mask & 0xFF) | (value & mask))

    def reset(self):
        """Software reset (``CTRL_REG2.rst``).

        The chip does not acknowledge the write (the NACK is tolerated) and
        returns to STANDBY with every register at its reset value. Run
        :meth:`init` afterwards to resume operation.
        """
        self._soft_reset()
        self._mode = 0
        self._dr = 0
        self._fs_g = 2
        self._mods = 0
        self._lnoise = False
        self._f_read = False
        self._fifo_mode = 0
        self._hyb = False

    def set_mode(self, mode):
        """Select accelerometer-only, magnetometer-only or hybrid operation.

        Args:
            mode: 0 accelerometer only, 1 magnetometer only, 2 hybrid. Hybrid
                mode also enables ``hyb_autoinc_mode``.

        Raises:
            ValueError: If ``mode`` is not 0, 1 or 2.
        """
        if mode not in (0, 1, 2):
            raise ValueError('mode must be 0, 1 or 2')
        was = self._begin()
        self._modify(self._REG_M_CTRL1, self._M_CTRL1_HMS_MASK, (0, 1, 3)[mode])
        if mode == 2:
            self._modify(self._REG_M_CTRL2, self._M_CTRL2_HYB_AUTOINC,
                         self._M_CTRL2_HYB_AUTOINC)
            self._hyb = True
        self._mode = mode
        self._end(was)

    def set_range(self, range_g):
        """Select the accelerometer full-scale range.

        Args:
            range_g: 2, 4 or 8 (g).

        Raises:
            ValueError: For any other value, or 8 while low-noise mode is on.
        """
        if range_g not in (2, 4, 8):
            raise ValueError('range_g must be 2, 4 or 8')
        if range_g == 8 and self._lnoise:
            raise ValueError('±8 g is not available in low-noise mode')
        self._modify(self._REG_XYZ_DATA_CFG, 0x03, {2: 0, 4: 1, 8: 2}[range_g])
        self._fs_g = range_g

    def set_data_rate(self, rate_hz):
        """Select the system output data rate.

        Args:
            rate_hz: 800, 400, 200, 100, 50, 12.5, 6.25 or 1.5625 (1.56 is
                accepted). In hybrid mode each sensor runs at half this rate.

        Raises:
            ValueError: If the rate is not one of the supported values.
        """
        code = None
        for i, hz in enumerate(_ODR_HZ):
            if abs(hz - rate_hz) <= 0.01 * hz:
                code = i
        if code is None:
            raise ValueError('unsupported data rate')
        was = self._begin()
        self._modify(self._REG_CTRL1, self._CTRL1_DR_MASK, code << self._CTRL1_DR_SHIFT)
        self._dr = code
        self._end(was)

    def set_accel_oversampling(self, mode, sleep_mode=None):
        """Select the accelerometer oversampling mode.

        Args:
            mode: 0 normal, 1 low-noise low-power, 2 high resolution,
                3 low power (WAKE mode, ``mods``).
            sleep_mode: Same encoding for SLEEP mode (``smods``); unchanged if
                ``None``.

        Raises:
            ValueError: If a mode is outside 0-3.
        """
        if mode not in (0, 1, 2, 3) or sleep_mode not in (None, 0, 1, 2, 3):
            raise ValueError('oversampling mode must be 0-3')
        was = self._begin()
        self._modify(self._REG_CTRL2, self._CTRL2_MODS_MASK, mode)
        if sleep_mode is not None:
            self._modify(self._REG_CTRL2, self._CTRL2_SMODS_MASK, sleep_mode << 3)
        self._mods = mode
        self._end(was)

    def set_mag_oversampling(self, ratio, sleep_ratio=None):
        """Select the magnetometer oversampling ratio code (``m_os``).

        The ratio code 0-7 maps to an oversampling that depends on the ODR
        (datasheet Table 203): at 200 Hz, for example, 0-5 give 2x, 6 gives 4x
        and 7 gives 8x.

        Args:
            ratio: 0-7 for active mode.
            sleep_ratio: 0-7 for Auto-SLEEP (``m_aslp_os``); unchanged if None.

        Raises:
            ValueError: If a value is outside 0-7.
        """
        if ratio not in range(8) or (sleep_ratio is not None and sleep_ratio not in range(8)):
            raise ValueError('oversampling ratio must be 0-7')
        self._modify(self._REG_M_CTRL1, self._M_CTRL1_OS_MASK, ratio << 2)
        if sleep_ratio is not None:
            self._modify(self._REG_M_CTRL3, 0x70, sleep_ratio << 4)

    def set_low_noise(self, enabled):
        """Enable or disable the reduced-noise mode (``lnoise``).

        Args:
            enabled: True to enable; limits the range to ±4 g.

        Raises:
            ValueError: If enabling while the range is ±8 g.
        """
        if enabled and self._fs_g == 8:
            raise ValueError('low-noise mode is not available at ±8 g')
        was = self._begin()
        self._modify(self._REG_CTRL1, self._CTRL1_LNOISE, self._CTRL1_LNOISE if enabled else 0)
        self._lnoise = bool(enabled)
        self._end(was)

    def set_resolution(self, bits):
        """Select 14-bit or 8-bit (fast read) accelerometer output.

        Args:
            bits: 14 or 8.

        Raises:
            ValueError: If ``bits`` is not 14 or 8, or the FIFO is on.
        """
        if bits not in (14, 8):
            raise ValueError('bits must be 14 or 8')
        if self._fifo_mode != 0:
            raise ValueError('resolution cannot change while the FIFO is on')
        was = self._begin()
        self._modify(self._REG_CTRL1, self._CTRL1_F_READ, self._CTRL1_F_READ if bits == 8 else 0)
        self._f_read = bits == 8
        self._end(was)

    # --- Data ----------------------------------------------------------------

    def read_raw_accel(self):
        """Read the raw accelerometer counts.

        Returns:
            tuple: Signed ``(x, y, z)`` counts (14-bit right-aligned, or 8-bit).
        """
        return self._read_accel_counts()

    def read_raw_mag(self):
        """Read the raw magnetometer counts.

        Returns:
            tuple: Signed 16-bit ``(x, y, z)`` counts (0.1 µT/LSB).
        """
        return self._read_mag_counts()

    def read_hybrid(self):
        """Read accelerometer and magnetometer with one burst from 0x01.

        Returns:
            tuple: ``((ax, ay, az) in g, (mx, my, mz) in µT)``.

        Raises:
            ValueError: If the chip is not in hybrid mode.
        """
        if self._mode != 2 or not self._hyb:
            raise ValueError('read_hybrid needs hybrid mode with hyb_autoinc_mode')
        n_accel = 3 if self._f_read else 6
        data = self._conn.read_reg(self._REG_OUT_X_MSB, n_accel + 6)
        acc = self._decode_accel(data, 0)
        mag = self._decode_mag(data, n_accel)
        scale = self._accel_scale()
        k = self._MAG_UT_PER_LSB
        return ((acc[0] / scale, acc[1] / scale, acc[2] / scale),
                (mag[0] * k, mag[1] * k, mag[2] * k))

    def read_temperature(self):
        """Read the die temperature.

        Returns:
            float: Temperature in °C (``int8(TEMP) * 0.96``); relative and
            uncalibrated.

        Raises:
            ValueError: In accelerometer-only mode.
        """
        if self._mode == 0:
            raise ValueError('temperature needs the magnetometer to be active')
        return to_signed(self._conn.read_reg(self._REG_TEMP, 1)[0], 8) * 0.96

    def new_accel_data(self):
        """Return True if a new accelerometer sample is ready (``STATUS.zyxdr``).

        Not valid while the FIFO is on.
        """
        return bool(self._conn.read_reg(self._REG_STATUS, 1)[0] & self._STATUS_ZYXDR)

    def new_mag_data(self):
        """Return True if a new magnetometer sample is ready (``M_DR_STATUS.zyxdr``)."""
        return bool(self._conn.read_reg(self._REG_M_DR_STATUS, 1)[0] & self._STATUS_ZYXDR)

    def read_status(self):
        """Return the ``STATUS`` byte."""
        return self._conn.read_reg(self._REG_STATUS, 1)[0]

    def read_mag_status(self):
        """Return the ``M_DR_STATUS`` byte."""
        return self._conn.read_reg(self._REG_M_DR_STATUS, 1)[0]

    def read_system_mode(self):
        """Return ``SYSMOD`` bits 1:0 (0 standby, 1 wake, 2 sleep)."""
        return self._conn.read_reg(self._REG_SYSMOD, 1)[0] & 0x03

    def set_high_pass_output(self, enabled, cutoff=0):
        """Select high-pass-filtered output data.

        The cutoff in Hz depends on ODR and oversampling mode (datasheet
        Table 64); in hybrid mode the effective ODR is halved.

        Args:
            enabled: True to output high-pass-filtered data (``hpf_out``).
            cutoff: ``sel`` code 0-3.

        Raises:
            ValueError: If ``cutoff`` is outside 0-3.
        """
        if cutoff not in range(4):
            raise ValueError('cutoff must be 0-3')
        was = self._begin()
        self._modify(self._REG_XYZ_DATA_CFG, 0x10, 0x10 if enabled else 0)
        self._modify(self._REG_HP_FILTER_CUTOFF, 0x03, cutoff)
        self._end(was)

    def set_accel_offset(self, x_g, y_g, z_g):
        """Write the per-axis accelerometer offset registers.

        The value is *added* to the measurement (2 mg/LSB, ±0.256 g).

        Args:
            x_g, y_g, z_g: Offsets in g.
        """
        data = bytes([_clamp(int(round(v / 0.002)), -128, 127) & 0xFF
                      for v in (x_g, y_g, z_g)])
        was = self._begin()
        self._conn.write_reg(self._REG_OFF_X, data)
        self._end(was)

    def get_accel_offset(self):
        """Return the accelerometer offsets as ``(x, y, z)`` in g."""
        data = self._conn.read_reg(self._REG_OFF_X, 3)
        return tuple(to_signed(b, 8) * 0.002 for b in data)

    def set_mag_offset(self, x_ut, y_ut, z_ut):
        """Write the hard-iron offset registers.

        The registers hold a 15-bit value in bits 15:1 that is *subtracted*
        from each measurement (0.1 µT/LSB).

        Args:
            x_ut, y_ut, z_ut: Offsets to subtract, in µT.
        """
        out = bytearray()
        for v in (x_ut, y_ut, z_ut):
            word = (_clamp(int(round(v / 0.1)), -16384, 16383) << 1) & 0xFFFF
            out.append(word >> 8)
            out.append(word & 0xFF)
        self._conn.write_reg(self._REG_M_OFF_X_MSB, bytes(out))

    def get_mag_offset(self):
        """Return the hard-iron offsets as ``(x, y, z)`` in µT."""
        data = self._conn.read_reg(self._REG_M_OFF_X_MSB, 6)
        out = []
        for i in range(3):
            word = (data[2 * i] << 8) | data[2 * i + 1]
            out.append((to_signed(word, 16) >> 1) * 0.1)
        return tuple(out)

    def set_mag_raw(self, raw):
        """Select raw magnetometer output (``m_raw``).

        Args:
            raw: True = hard-iron offsets are not applied to the output.
        """
        self._modify(self._REG_M_CTRL3, 0x80, 0x80 if raw else 0)

    def set_mag_auto_calibration(self, enabled):
        """Enable or disable hardware hard-iron auto-calibration (``m_acal``).

        Enabling also clears ``m_raw`` and enables min/max tracking. While on,
        rotate the board through all orientations; the offsets stay in
        ``M_OFF_*`` after disabling (NXP AN4399).

        Args:
            enabled: True to enable.
        """
        if enabled:
            self._modify(self._REG_M_CTRL3, 0x80, 0)
            was = self._begin()
            self._modify(self._REG_M_CTRL2, 0x10, 0)
            self._end(was)
        self._modify(self._REG_M_CTRL1, self._M_CTRL1_ACAL, self._M_CTRL1_ACAL if enabled else 0)

    def read_mag_min_max(self):
        """Read the tracked magnetic minimum and maximum.

        Returns:
            tuple: ``((minx, miny, minz), (maxx, maxy, maxz))`` in µT.
        """
        k = self._MAG_UT_PER_LSB
        mn = self._decode_mag(self._conn.read_reg(self._REG_MIN_X_MSB, 6))
        mx = self._decode_mag(self._conn.read_reg(self._REG_MAX_X_MSB, 6))
        return (tuple(v * k for v in mn), tuple(v * k for v in mx))

    def reset_mag_min_max(self):
        """Reset the min/max registers to 0x7FFF / 0x8000 (``m_maxmin_rst``)."""
        self._modify(self._REG_M_CTRL2, 0x04, 0x04)

    def set_mag_reset(self, mode):
        """Select the automatic magnetic reset (degauss) period.

        Args:
            mode: 0 every ODR cycle, 1 every 16, 2 every 512, 3 disabled.

        Raises:
            ValueError: If ``mode`` is outside 0-3.
        """
        if mode not in range(4):
            raise ValueError('mode must be 0-3')
        was = self._begin()
        self._modify(self._REG_M_CTRL2, 0x03, mode)
        self._end(was)

    def trigger_mag_reset(self):
        """Trigger a one-shot magnetic reset (``m_rst``)."""
        self._modify(self._REG_M_CTRL1, self._M_CTRL1_RST, self._M_CTRL1_RST)

    def measure_mag_once(self):
        """Take one magnetic measurement in STANDBY (``m_ost``).

        Returns:
            tuple: ``(x, y, z)`` in µT. ACTIVE is restored afterwards.

        Raises:
            ValueError: In accelerometer-only mode.
            OSError: If the measurement does not finish within 1 s.
        """
        if self._mode == 0:
            raise ValueError('one-shot measurement needs the magnetometer')
        was = self._begin()
        self._modify(self._REG_M_CTRL1, self._M_CTRL1_OST, self._M_CTRL1_OST)
        done = False
        for _ in range(1000):
            if not self._conn.read_reg(self._REG_M_CTRL1, 1)[0] & self._M_CTRL1_OST:
                done = True
                break
            _delay_ms(1)
        if not done:
            self._end(was)
            raise OSError('magnetic one-shot measurement timed out')
        value = self.read_mag()
        self._end(was)
        return value

    def heading(self, declination_deg=0.0):
        """Compute the tilt-compensated compass heading.

        Uses one accelerometer and one magnetometer sample in the chip frame
        (X forward, Y right, Z down). Axis signs should be verified on
        hardware.

        Args:
            declination_deg: Magnetic declination added to the result.

        Returns:
            float: Heading in degrees, [0, 360).
        """
        if self._mode == 2 and self._hyb:
            (ax, ay, az), (mx, my, mz) = self.read_hybrid()
        else:
            ax, ay, az = self.read_accel()
            mx, my, mz = self.read_mag()
        roll = math.atan2(ay, az)
        pitch = math.atan2(-ax, ay * math.sin(roll) + az * math.cos(roll))
        mx2 = mx * math.cos(pitch) + mz * math.sin(pitch)
        my2 = (mx * math.sin(roll) * math.sin(pitch) + my * math.cos(roll)
               - mz * math.sin(roll) * math.cos(pitch))
        deg = math.degrees(math.atan2(-my2, mx2)) + declination_deg
        return deg % 360.0

    # --- FIFO ----------------------------------------------------------------

    def set_fifo(self, mode, watermark=0):
        """Configure the 32-sample accelerometer FIFO.

        Changing between two enabled modes goes through disabled. The FIFO
        holds accelerometer data only.

        Args:
            mode: 0 disabled, 1 circular, 2 fill, 3 trigger.
            watermark: 0-63 (0 disables the flag).

        Raises:
            ValueError: If an argument is out of range.
        """
        if mode not in range(4) or watermark not in range(64):
            raise ValueError('invalid FIFO mode or watermark')
        was = self._begin()
        if self._fifo_mode != 0 and mode != 0 and mode != self._fifo_mode:
            self._conn.write_reg(self._REG_F_SETUP, 0x00)
        self._conn.write_reg(self._REG_F_SETUP, (mode << 6) | watermark)
        self._fifo_mode = mode
        self._end(was)

    def set_fifo_trigger(self, transient=False, orientation=False, pulse=False, motion=False):
        """Select the FIFO trigger sources (``TRIG_CFG``).

        Args:
            transient, orientation, pulse, motion: Source enables.
        """
        value = ((0x20 if transient else 0) | (0x10 if orientation else 0)
                 | (0x08 if pulse else 0) | (0x04 if motion else 0))
        was = self._begin()
        self._conn.write_reg(self._REG_TRIG_CFG, value)
        self._end(was)

    def fifo_level(self):
        """Return the number of samples in the FIFO (0-32)."""
        return self._conn.read_reg(self._REG_STATUS, 1)[0] & 0x3F

    def read_fifo_status(self):
        """Decode ``F_STATUS`` (clears ``SOURCE_FIFO``).

        Returns:
            tuple: ``(overflow, watermark, count)``.
        """
        v = self._conn.read_reg(self._REG_STATUS, 1)[0]
        return (bool(v & 0x80), bool(v & 0x40), v & 0x3F)

    def read_fifo(self, count=None):
        """Drain accelerometer samples from the FIFO.

        Each sample is read with its own 6-byte (3 in 8-bit mode) transaction
        from 0x01, so the hybrid auto-increment jump to the magnetometer never
        matters.

        Args:
            count: Maximum number of samples; default all.

        Returns:
            list: ``(x, y, z)`` tuples in g.
        """
        n = self.fifo_level()
        if count is not None and count < n:
            n = count
        scale = self._accel_scale()
        out = []
        for _ in range(n):
            c = self._read_accel_counts()
            out.append((c[0] / scale, c[1] / scale, c[2] / scale))
        return out

    def set_fifo_gate(self, enabled):
        """Enable or disable the FIFO gate (``fifo_gate``).

        Args:
            enabled: True to block FIFO input around WAKE/SLEEP transitions.
        """
        was = self._begin()
        self._modify(self._REG_CTRL3, 0x80, 0x80 if enabled else 0)
        self._end(was)

    # --- Detectors -----------------------------------------------------------

    def _step_ms(self):
        """Debounce time step in ms at the effective ODR and oversampling."""
        row = min(self._dr + (1 if self._mode == 2 else 0), 7)
        return _STEP_MS[self._mods][row]

    def _pulse_step_ms(self, lpf):
        """PULSE_TMLT time step in ms."""
        row = min(self._dr + (1 if self._mode == 2 else 0), 7)
        return _STEP_MS[self._mods][row] if lpf else _PULSE_STEP_NO_LPF_MS[self._mods][row]

    @staticmethod
    def _threshold(threshold_g):
        """Encode a 7-bit threshold (0.063 g/LSB)."""
        return _clamp(int(round(threshold_g / 0.063)), 0, 127)

    def set_freefall_motion(self, motion, axes=7, threshold_g=0.3, debounce_ms=0,
                            latch=False, debounce_clear=False):
        """Configure the freefall / motion detector (``A_FFMT_*``).

        Args:
            motion: True = motion (any enabled axis above the threshold),
                False = freefall (all enabled axes below).
            axes: Axis mask, X = 1, Y = 2, Z = 4.
            threshold_g: Threshold in g (0.063 g/LSB, max 8 g).
            debounce_ms: Debounce time in ms.
            latch: True to latch the event until ``A_FFMT_SRC`` is read.
            debounce_clear: True to clear (instead of decrement) the debounce
                counter when the condition is lost.
        """
        cfg = (0x80 if latch else 0) | (0x40 if motion else 0) | ((axes & 7) << 3)
        ths = (0x80 if debounce_clear else 0) | self._threshold(threshold_g)
        count = _clamp(int(round(debounce_ms / self._step_ms())), 0, 255)
        was = self._begin()
        self._conn.write_reg(self._REG_A_FFMT_CFG, cfg)
        self._conn.write_reg(self._REG_A_FFMT_THS, ths)
        self._conn.write_reg(self._REG_A_FFMT_COUNT, count)
        self._end(was)

    def read_motion_source(self):
        """Return ``A_FFMT_SRC`` (clears the flags when latched)."""
        return self._conn.read_reg(self._REG_A_FFMT_SRC, 1)[0]

    def set_transient(self, axes=7, threshold_g=0.3, debounce_ms=0,
                      bypass_hpf=False, latch=False):
        """Configure the transient detector (``TRANSIENT_*``).

        Args:
            axes: Axis mask, X = 1, Y = 2, Z = 4.
            threshold_g: Threshold in g (0.063 g/LSB).
            debounce_ms: Debounce time in ms.
            bypass_hpf: True to bypass the high-pass filter (motion-like).
            latch: True to latch the event until ``TRANSIENT_SRC`` is read.
        """
        cfg = (0x10 if latch else 0) | ((axes & 7) << 1) | (1 if bypass_hpf else 0)
        count = _clamp(int(round(debounce_ms / self._step_ms())), 0, 255)
        was = self._begin()
        self._conn.write_reg(self._REG_TRANSIENT_CFG, cfg)
        self._conn.write_reg(self._REG_TRANSIENT_THS, self._threshold(threshold_g))
        self._conn.write_reg(self._REG_TRANSIENT_COUNT, count)
        self._end(was)

    def read_transient_source(self):
        """Return ``TRANSIENT_SRC`` — **clears** the interrupt."""
        return self._conn.read_reg(self._REG_TRANSIENT_SRC, 1)[0]

    def set_pulse(self, single=0, double=0, threshold_g=(0.5, 0.5, 0.5),
                  time_limit_ms=30, latency_ms=100, window_ms=300,
                  latch=False, double_abort=False, lpf=True, hpf=True):
        """Configure tap (pulse) detection (``PULSE_*``).

        Args:
            single: Axis mask (X = 1, Y = 2, Z = 4) for single-tap detection.
            double: Axis mask for double-tap detection.
            threshold_g: ``(x, y, z)`` thresholds in g, or one number for all.
            time_limit_ms: Pulse time limit.
            latency_ms: Latency between the two taps.
            window_ms: Window for the second tap.
            latch: True to latch until ``PULSE_SRC`` is read.
            double_abort: True to abort double-tap detection on a pulse during
                the latency window.
            lpf: Enable the pulse low-pass filter.
            hpf: Enable the pulse high-pass filter.
        """
        if isinstance(threshold_g, (int, float)):
            threshold_g = (threshold_g, threshold_g, threshold_g)
        cfg = (0x80 if double_abort else 0) | (0x40 if latch else 0)
        for i in range(3):
            if (single >> i) & 1:
                cfg |= 1 << (2 * i)
            if (double >> i) & 1:
                cfg |= 2 << (2 * i)
        step = self._pulse_step_ms(lpf)
        tmlt = _clamp(int(round(time_limit_ms / step)), 0, 255)
        ltcy = _clamp(int(round(latency_ms / (2 * step))), 0, 255)
        wind = _clamp(int(round(window_ms / (2 * step))), 0, 255)
        was = self._begin()
        self._conn.write_reg(self._REG_PULSE_CFG, cfg)
        self._conn.write_reg(self._REG_PULSE_THSX,
                             bytes([self._threshold(t) for t in threshold_g]))
        self._conn.write_reg(self._REG_PULSE_TMLT, tmlt)
        self._conn.write_reg(self._REG_PULSE_LTCY, ltcy)
        self._conn.write_reg(self._REG_PULSE_WIND, wind)
        self._modify(self._REG_HP_FILTER_CUTOFF, 0x30,
                     (0 if hpf else 0x20) | (0x10 if lpf else 0))
        self._end(was)

    def read_pulse_source(self):
        """Return ``PULSE_SRC`` — **clears** the interrupt."""
        return self._conn.read_reg(self._REG_PULSE_SRC, 1)[0]

    def set_orientation(self, enabled=True, debounce_ms=0, threshold_deg=45,
                        hysteresis=4, z_lock=4, back_front=2):
        """Configure orientation (portrait / landscape) detection.

        Args:
            enabled: Enable the detector (``pl_en``).
            debounce_ms: Debounce time in ms.
            threshold_deg: Trip angle, one of 15, 20, 30, 35, 40, 45, 55, 60,
                70, 75.
            hysteresis: ``hys`` code 0-7.
            z_lock: Z-lock angle code 0-7.
            back_front: Back/front trip code 0-3.

        Raises:
            ValueError: For an invalid argument, or if ``pl_ths + hys`` is
                outside 1-31.
        """
        if threshold_deg not in _PL_THRESHOLDS:
            raise ValueError('unsupported threshold_deg')
        if hysteresis not in range(8) or z_lock not in range(8) or back_front not in range(4):
            raise ValueError('hysteresis/z_lock/back_front out of range')
        ths = _PL_THRESHOLDS[threshold_deg]
        if not 1 <= ths + hysteresis <= 31:
            raise ValueError('pl_ths + hys must be within 1-31')
        count = _clamp(int(round(debounce_ms / self._step_ms())), 0, 255)
        was = self._begin()
        self._conn.write_reg(self._REG_PL_CFG, 0x80 | (0x40 if enabled else 0))
        self._conn.write_reg(self._REG_PL_COUNT, count)
        self._conn.write_reg(self._REG_PL_THS, (ths << 3) | hysteresis)
        self._conn.write_reg(self._REG_PL_BF_ZCOMP, (back_front << 6) | z_lock)
        self._end(was)

    def read_orientation(self):
        """Decode ``PL_STATUS`` — **clears** ``newlp``.

        Returns:
            tuple: ``(changed, lockout, portrait_landscape, back)`` where
            ``portrait_landscape`` is 0 portrait up, 1 portrait down,
            2 landscape right, 3 landscape left.
        """
        v = self._conn.read_reg(self._REG_PL_STATUS, 1)[0]
        return (bool(v & 0x80), bool(v & 0x40), (v >> 1) & 0x03, bool(v & 0x01))

    def set_auto_sleep(self, enabled, sleep_rate_hz=50, timeout_s=10, wake_sources=0):
        """Configure Auto-SLEEP / WAKE.

        Args:
            enabled: Enable Auto-SLEEP (``slpe``).
            sleep_rate_hz: 50, 12.5, 6.25 or 1.56 Hz (halved in hybrid mode).
            timeout_s: Inactivity time before SLEEP.
            wake_sources: Mask of ``SOURCE_TRANSIENT``, ``SOURCE_ORIENTATION``,
                ``SOURCE_PULSE``, ``SOURCE_FF_MT`` and ``SOURCE_ACCEL_VECM``.

        Raises:
            ValueError: For an unsupported rate or source.
        """
        code = None
        for hz, c in _ASLP_RATES:
            if abs(hz - sleep_rate_hz) <= 0.01 * hz:
                code = c
        if code is None:
            raise ValueError('unsupported sleep rate')
        if wake_sources & ~0x3E:
            raise ValueError('unsupported wake source')
        step = 0.64 if self._dr == 7 else 0.32
        count = _clamp(int(round(timeout_s / step)), 0, 255)
        was = self._begin()
        self._modify(self._REG_CTRL1, self._CTRL1_ASLP_MASK, code << 6)
        self._conn.write_reg(self._REG_ASLP_COUNT, count)
        self._modify(self._REG_CTRL2, self._CTRL2_SLPE, self._CTRL2_SLPE if enabled else 0)
        self._modify(self._REG_CTRL3, 0x7C, (wake_sources & 0x3E) << 1)
        self._end(was)

    # --- Interrupts ----------------------------------------------------------

    def set_interrupt_pins(self, active_high=False, open_drain=False):
        """Configure INT1 / INT2 polarity and drive (``ipol``, ``pp_od``).

        Args:
            active_high: True for active-high.
            open_drain: True for open-drain (needs an external pull-up).
        """
        was = self._begin()
        self._modify(self._REG_CTRL3, 0x03,
                     (0x02 if active_high else 0) | (0x01 if open_drain else 0))
        self._end(was)

    def enable_interrupt(self, source, pin=1):
        """Enable an interrupt source and route it to INT1 or INT2.

        Only routes the source; the detector behind it is configured with the
        ``set_*`` methods.

        Args:
            source: A ``FXOS8700Source.SOURCE_*`` mask.
            pin: 1 for INT1, 2 for INT2.

        Raises:
            ValueError: If ``pin`` is not 1 or 2.
        """
        if pin not in (1, 2):
            raise ValueError('pin must be 1 or 2')
        was = self._begin()
        self._modify(self._REG_CTRL4, source, source)
        self._modify(self._REG_CTRL5, source, source if pin == 1 else 0)
        self._end(was)

    def disable_interrupt(self, source):
        """Disable an interrupt source (clears its ``int_en_*`` bit).

        Args:
            source: A ``FXOS8700Source.SOURCE_*`` mask.
        """
        was = self._begin()
        self._modify(self._REG_CTRL4, source, 0)
        self._end(was)

    def poll_interrupt(self):
        """Read ``INT_SOURCE`` and clear the pending sources.

        For every set bit except ``SOURCE_DATA_READY`` and
        ``SOURCE_ACCEL_VECM`` the matching source register is read so the
        flag (and the pin) clears. ``SOURCE_DATA_READY`` is cleared by
        reading the data.

        Returns:
            int: A mask of ``FXOS8700Source.SOURCE_*`` constants.
        """
        status = self._conn.read_reg(self._REG_INT_SOURCE, 1)[0]
        clear = ((FXOS8700Source.SOURCE_AUTO_SLEEP, self._REG_SYSMOD),
                 (FXOS8700Source.SOURCE_FIFO, self._REG_STATUS),
                 (FXOS8700Source.SOURCE_TRANSIENT, self._REG_TRANSIENT_SRC),
                 (FXOS8700Source.SOURCE_ORIENTATION, self._REG_PL_STATUS),
                 (FXOS8700Source.SOURCE_PULSE, self._REG_PULSE_SRC),
                 (FXOS8700Source.SOURCE_FF_MT, self._REG_A_FFMT_SRC))
        for bit, reg in clear:
            if status & bit:
                self._conn.read_reg(reg, 1)
        return status

    def on_interrupt(self, callback, pin=1):
        """Subscribe to the INT1 or INT2 line.

        The edge follows the configured polarity. Without an INT pin on the
        connection a 5 ms polling thread takes over.

        Args:
            callback: Callable(status: int) receiving the
                :meth:`poll_interrupt` mask.
            pin: 1 for INT1, 2 for INT2.

        Raises:
            ValueError: If the pin is not 1 or 2.
        """
        if pin not in (1, 2):
            raise ValueError('pin must be 1 or 2')
        self._callback = callback
        int_pin = getattr(self._conn, 'int_pin', None)
        self._int_pin_used = int_pin
        if int_pin is not None:
            from periph.connection.input_pin import InputPin
            active_high = bool(self._conn.read_reg(self._REG_CTRL3, 1)[0] & 0x02)
            int_pin.on_edge(self._int_handler,
                            InputPin.RISING if active_high else InputPin.FALLING)
        else:
            self._last_status = self.poll_interrupt()
            self._poll_stop = False
            try:
                import threading as _threading
            except ImportError:
                return
            self._poll_thread = _threading.Thread(target=self._poll_loop, daemon=True)
            self._poll_thread.start()

    def off_interrupt(self, pin=1):
        """Unsubscribe from the INT1 or INT2 line.

        Args:
            pin: 1 for INT1, 2 for INT2.
        """
        if self._int_pin_used is not None:
            self._int_pin_used.off_edge(self._int_handler)
            self._int_pin_used = None
        else:
            self._poll_stop = True
        self._callback = None

    def _int_handler(self):
        """Handle an INT edge: read the status and dispatch it."""
        status = self.poll_interrupt()
        if self._callback:
            self._callback(status)

    def _poll_loop(self):
        """Poll the interrupt status every 5 ms and dispatch on change."""
        import time
        while not self._poll_stop:
            status = self.poll_interrupt()
            if status != self._last_status:
                self._last_status = status
                if self._callback:
                    self._callback(status)
            time.sleep(0.005)

    # --- Escape hatch / self-test --------------------------------------------

    def read_register(self, reg, length=1):
        """Read raw register bytes (vector-magnitude, magnetic threshold and
        per-axis FFMT registers). No STANDBY handling.

        Args:
            reg: Start register address.
            length: Number of bytes.

        Returns:
            bytes: The register contents.
        """
        return self._conn.read_reg(reg, length)

    def write_register(self, reg, data):
        """Write raw register bytes. No STANDBY handling.

        Args:
            reg: Start register address.
            data: ``bytes`` (or a single int).
        """
        self._conn.write_reg(reg, data)

    def _average_counts(self, samples=8):
        """Average ``samples`` 14-bit accelerometer readings (accel-only)."""
        sx = sy = sz = 0
        for _ in range(samples):
            _delay_ms(12)
            c = self._decode_accel(self._conn.read_reg(self._REG_OUT_X_MSB, 6))
            sx += c[0]
            sy += c[1]
            sz += c[2]
        return (sx // samples, sy // samples, sz // samples)

    def self_test(self):
        """Run the accelerometer self-test and restore the configuration.

        STANDBY, ±2 g, 100 Hz accelerometer-only, normal oversampling; the
        average of 8 samples is taken before and after setting ``st`` and the
        difference is checked against the datasheet minimum of
        X >= +192, Y >= +270, Z >= +1275 counts.

        Returns:
            tuple: ``(ok, dx, dy, dz)`` with the differences in 14-bit counts.
        """
        r = self._conn.read_reg
        saved = {reg: r(reg, 1)[0] for reg in
                 (self._REG_CTRL1, self._REG_CTRL2, self._REG_XYZ_DATA_CFG,
                  self._REG_M_CTRL1)}
        cache = (self._mode, self._dr, self._fs_g, self._mods, self._lnoise, self._f_read)
        self.standby()
        self._conn.write_reg(self._REG_XYZ_DATA_CFG, 0x00)
        self._conn.write_reg(self._REG_M_CTRL1, saved[self._REG_M_CTRL1] & ~self._M_CTRL1_HMS_MASK & 0xFF)
        self._conn.write_reg(self._REG_CTRL2, 0x00)
        self._mode, self._dr, self._fs_g, self._mods = 0, 3, 2, 0
        self._lnoise = self._f_read = False
        self._conn.write_reg(self._REG_CTRL1, (3 << self._CTRL1_DR_SHIFT) | self._CTRL1_ACTIVE)
        _delay_ms(self._turn_on_ms())
        before = self._average_counts()
        self._conn.write_reg(self._REG_CTRL2, self._CTRL2_ST)
        _delay_ms(25)
        after = self._average_counts()
        self._conn.write_reg(self._REG_CTRL2, 0x00)
        self.standby()
        self._conn.write_reg(self._REG_XYZ_DATA_CFG, saved[self._REG_XYZ_DATA_CFG])
        self._conn.write_reg(self._REG_M_CTRL1, saved[self._REG_M_CTRL1])
        self._conn.write_reg(self._REG_CTRL2, saved[self._REG_CTRL2])
        (self._mode, self._dr, self._fs_g, self._mods, self._lnoise, self._f_read) = cache
        self._conn.write_reg(self._REG_CTRL1, saved[self._REG_CTRL1])
        if saved[self._REG_CTRL1] & self._CTRL1_ACTIVE:
            _delay_ms(self._turn_on_ms())
        dx, dy, dz = after[0] - before[0], after[1] - before[1], after[2] - before[2]
        return (dx >= 192 and dy >= 270 and dz >= 1275, dx, dy, dz)
