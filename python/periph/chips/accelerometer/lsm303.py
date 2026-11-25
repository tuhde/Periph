"""LSM303 — 3-axis accelerometer + magnetometer e-compass (STMicroelectronics).

The LSM303DLHC combines a three-axis MEMS accelerometer and a three-axis
magnetometer in one package. The two dies sit behind **two independent I²C
slave addresses** on the same bus: the accelerometer at 0x19 and the
magnetometer at 0x1E. The driver therefore takes two connections.

Accelerometer (0x19)
    Four full-scale ranges (±2 / ±4 / ±8 / ±16 g), 12-bit output at 1 mg/LSB
    in the default high-resolution mode, output data rates from 1 Hz to
    5.376 kHz, a 32-level FIFO, a selectable high-pass filter, two
    programmable inertial interrupt generators (threshold, free-fall, 6D/4D
    orientation) and single/double click detection.

Magnetometer (0x1E)
    Seven full-scale ranges (±130 µT to ±810 µT), 12-bit output, output data
    rates from 0.75 Hz to 220 Hz and a relative-only temperature sensor.

The compass heading is computed in software from both sensors.

I²C framing differs between the two slaves:

* **Accelerometer** — after the slave address the master sends an 8-bit
  sub-address: the 7 LSbs are the register address and the **MSb enables
  address auto-increment**. A single-register access uses ``reg``; a
  multi-byte access uses ``reg | 0x80``.
* **Magnetometer** — an address pointer without an auto-increment bit. The
  pointer always advances by one; after register 0x08 it rolls back to 0x03
  and from 0x0C upwards it wraps to 0x00. A 6-byte read from 0x03 therefore
  returns all six output registers in one transaction.

The magnetometer output order is **X, Z, Y** (not X, Y, Z) and the byte order
is **high byte first** — the opposite of the accelerometer.

Args:
    accel_connection: RegisterConnection (I²C) bound to the accelerometer
        slave address 0x19.
    mag_connection: RegisterConnection (I²C) bound to the magnetometer
        slave address 0x1E.

Both connections must sit on the same bus.
"""

import math

from periph.connection.register import to_signed

# Fixed I²C slave addresses of the two dies.
I2C_ADDRESS_ACCEL = 0x19
I2C_ADDRESS_MAG = 0x1E

# Magnetometer identity (IRA_REG_M, IRB_REG_M, IRC_REG_M) — ASCII "H43".
MAG_IDENTITY = b'\x48\x34\x33'


def _delay_ms(ms):
    """Sleep ``ms`` milliseconds, portable across MicroPython and CPython."""
    import time
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(int(ms))
    else:
        time.sleep(ms / 1000.0)


class LSM303Source:
    """Interrupt source constants for the LSM303.

    The values are the bit positions of :meth:`LSM303Full.poll_interrupt`'s
    return value.
    """

    #: New accelerometer XYZ sample (``STATUS_REG_A.ZYXDA``), INT1.
    SOURCE_DATA_READY = 0x0001
    #: FIFO content exceeds the watermark (``FIFO_SRC_REG_A.WTM``), INT1.
    SOURCE_WATERMARK = 0x0002
    #: FIFO overrun (``FIFO_SRC_REG_A.OVRN_FIFO``), INT1.
    SOURCE_OVERRUN = 0x0004
    #: Inertial generator 1 event (``INT1_SOURCE_A.IA``), INT1 or INT2.
    SOURCE_IA1 = 0x0008
    #: Inertial generator 2 event (``INT2_SOURCE_A.IA``), INT1 or INT2.
    SOURCE_IA2 = 0x0010
    #: Single / double click (``CLICK_SRC_A.IA``), INT1 or INT2.
    SOURCE_CLICK = 0x0020
    #: Boot procedure running — INT2 only, never reported by poll_interrupt().
    SOURCE_BOOT = 0x0040
    #: New magnetometer sample (``SR_REG_M.DRDY``) — dedicated DRDY pin.
    SOURCE_MAG_DATA_READY = 0x0080


class LSM303Minimal:
    """LSM303 minimal interface — acceleration and magnetic field.

    The constructor runs the full initialization sequence of the datasheet:
    it verifies the magnetometer identity (0x48 0x34 0x33), puts the
    accelerometer control registers into a known state and starts both dies.

    Default configuration:
        - Accelerometer: ±2 g, 100 Hz, high-resolution (12-bit), all three
          axes enabled, ``BDU`` set, high-pass filter bypassed, FIFO bypassed,
          all interrupt routing cleared.
        - Magnetometer: ±130 µT (±1.3 gauss), 15 Hz, continuous conversion,
          temperature sensor off.

    Args:
        accel_connection: RegisterConnection (I²C) bound to 0x19.
        mag_connection: RegisterConnection (I²C) bound to 0x1E.

    Raises:
        ValueError: If the magnetometer identity does not match.
    """

    # --- Accelerometer registers -----------------------------------------
    _REG_CTRL1_A = 0x20
    _REG_CTRL2_A = 0x21
    _REG_CTRL3_A = 0x22
    _REG_CTRL4_A = 0x23
    _REG_CTRL5_A = 0x24
    _REG_CTRL6_A = 0x25
    _REG_REFERENCE_A = 0x26
    _REG_STATUS_A = 0x27
    _REG_OUT_X_L_A = 0x28
    _REG_FIFO_CTRL_A = 0x2E
    _REG_FIFO_SRC_A = 0x2F
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

    # --- Magnetometer registers ------------------------------------------
    _REG_CRA_M = 0x00
    _REG_CRB_M = 0x01
    _REG_MR_M = 0x02
    _REG_OUT_X_H_M = 0x03
    _REG_SR_M = 0x09
    _REG_IRA_M = 0x0A
    _REG_TEMP_OUT_H_M = 0x31

    # Bit 7 of the accelerometer sub-address enables address auto-increment.
    _ACCEL_AUTO_INCREMENT = 0x80

    # Accelerometer operating modes.
    MODE_LOW_POWER = 0
    MODE_NORMAL = 1
    MODE_HIGH_RESOLUTION = 2

    def __init__(self, accel_connection, mag_connection):
        self._accel = accel_connection
        self._mag = mag_connection
        # Cached configuration, kept in sync with the registers.
        self._mode = self.MODE_HIGH_RESOLUTION
        self._fs_g = 2
        self._odr_hz = 100.0
        self._mag_range_ut = 130
        self.init()

    # --- Low level register access ---------------------------------------

    def _accel_read(self, reg, length):
        """Read ``length`` bytes from an accelerometer register.

        Multi-byte reads set bit 7 of the sub-address so the accelerometer
        auto-increments the register pointer.
        """
        if length > 1:
            reg |= self._ACCEL_AUTO_INCREMENT
        return self._accel.read_reg(reg, length)

    def _accel_write(self, reg, value):
        """Write one byte to an accelerometer register."""
        self._accel.write_reg(reg, value)

    def _mag_read(self, reg, length):
        """Read ``length`` bytes from a magnetometer register.

        The magnetometer address pointer always auto-increments, so no
        auto-increment bit is needed.
        """
        return self._mag.read_reg(reg, length)

    def _mag_write(self, reg, value):
        """Write one byte to a magnetometer register."""
        self._mag.write_reg(reg, value)

    # --- Initialization ----------------------------------------------------

    def init(self):
        """Run the datasheet initialization sequence.

        Verifies the magnetometer identity, clears the accelerometer's
        interrupt routing, FIFO and high-pass configuration, selects
        ±2 g / 100 Hz / high-resolution on the accelerometer and
        ±130 µT / 15 Hz / continuous conversion on the magnetometer, then
        waits for the first samples of both dies.
        """
        # Boot download of the factory trim values.
        _delay_ms(5)

        identity = self._mag_read(self._REG_IRA_M, 3)
        if bytes(identity) != MAG_IDENTITY:
            raise ValueError(
                'LSM303 magnetometer not found: expected identity 0x483433, got 0x{:06X}'.format(
                    (identity[0] << 16) | (identity[1] << 8) | identity[2]))

        # Accelerometer: known state for the high-pass filter, the interrupt
        # routing and the FIFO.
        self._accel_write(self._REG_CTRL2_A, 0x00)
        self._accel_write(self._REG_CTRL3_A, 0x00)
        self._accel_write(self._REG_CTRL5_A, 0x00)
        self._accel_write(self._REG_CTRL6_A, 0x00)
        self._accel_write(self._REG_FIFO_CTRL_A, 0x00)

        # Accelerometer: BDU = 1, ±2 g, high-resolution mode.
        self._accel_write(self._REG_CTRL4_A, 0x88)
        # Accelerometer: 100 Hz, X/Y/Z enabled.
        self._accel_write(self._REG_CTRL1_A, 0x57)

        # Magnetometer: 15 Hz, ±1.3 gauss, continuous conversion (the part
        # resets into sleep mode).
        self._mag_write(self._REG_CRA_M, 0x10)
        self._mag_write(self._REG_CRB_M, 0x20)
        self._mag_write(self._REG_MR_M, 0x00)

        # Accelerometer turn-on: 7/ODR = 70 ms at 100 Hz.
        _delay_ms(70)
        # Magnetometer first sample: one output period = 67 ms at 15 Hz.
        _delay_ms(67)

        self._mode = self.MODE_HIGH_RESOLUTION
        self._fs_g = 2
        self._odr_hz = 100.0

    # --- Minimal API -------------------------------------------------------

    def read_acceleration(self):
        """Read the acceleration.

        Burst-reads the six output registers so all three axes belong to the
        same sample.

        Returns:
            tuple: (x, y, z) acceleration in *g*.
        """
        raw = self._accel_read(self._REG_OUT_X_L_A, 6)
        return self._convert_accel(raw)

    def read_magnetic(self):
        """Read the magnetic flux density.

        Burst-reads the six output registers (X, Z, Y on the wire) and
        reorders them to X, Y, Z.

        Returns:
            tuple: (x, y, z) magnetic flux density in µT.
        """
        raw = self._mag_read(self._REG_OUT_X_H_M, 6)
        x, y, z = self._reorder_mag(raw)
        gain_x, gain_z = self._mag_gains()
        return (x / gain_x * 100.0, y / gain_x * 100.0, z / gain_z * 100.0)

    # --- Conversion helpers ------------------------------------------------

    def _shift(self):
        """Return the right-shift that turns the raw 16-bit pair into counts."""
        if self._mode == self.MODE_LOW_POWER:
            return 8
        if self._mode == self.MODE_NORMAL:
            return 6
        return 4

    def _sensitivity_mg(self):
        """Return the sensitivity in mg/LSB for the current mode and range."""
        if self._mode == self.MODE_LOW_POWER:
            table = (16, 32, 64, 192)
        elif self._mode == self.MODE_NORMAL:
            table = (4, 8, 16, 48)
        else:
            table = (1, 2, 4, 12)
        index = {2: 0, 4: 1, 8: 2, 16: 3}[self._fs_g]
        return table[index]

    def _convert_accel(self, raw):
        """Convert a six-byte accelerometer burst into (x, y, z) in *g*."""
        shift = self._shift()
        sens = self._sensitivity_mg() / 1000.0
        x = to_signed(raw[0] | (raw[1] << 8), 16) >> shift
        y = to_signed(raw[2] | (raw[3] << 8), 16) >> shift
        z = to_signed(raw[4] | (raw[5] << 8), 16) >> shift
        return (x * sens, y * sens, z * sens)

    def _raw_accel(self, raw):
        """Convert a six-byte accelerometer burst into raw counts."""
        shift = self._shift()
        return (to_signed(raw[0] | (raw[1] << 8), 16) >> shift,
                to_signed(raw[2] | (raw[3] << 8), 16) >> shift,
                to_signed(raw[4] | (raw[5] << 8), 16) >> shift)

    def _reorder_mag(self, raw):
        """Reorder a six-byte magnetometer burst (X, Z, Y) into (X, Y, Z)."""
        x = to_signed((raw[0] << 8) | raw[1], 16)
        z = to_signed((raw[2] << 8) | raw[3], 16)
        y = to_signed((raw[4] << 8) | raw[5], 16)
        return (x, y, z)

    def _mag_gains(self):
        """Return the (X/Y, Z) gains in LSB per gauss for the current range."""
        table = {
            130: (1100, 980),
            190: (855, 760),
            250: (670, 600),
            400: (450, 400),
            470: (400, 355),
            560: (330, 295),
            810: (230, 205),
        }
        return table[self._mag_range_ut]


class LSM303Full(LSM303Minimal):
    """LSM303 full interface — extends :class:`LSM303Minimal` with the
    complete chip API.

    Adds:
        - Accelerometer range, output data rate, operating mode and per-axis
          enable, plus power-down.
        - Raw acceleration and raw magnetic readings.
        - High-pass filter configuration and the reference value.
        - The 32-level FIFO in bypass / FIFO / stream / trigger mode.
        - Two inertial interrupt generators with thresholds, durations,
          free-fall and 6D/4D orientation recognition, plus latching.
        - Single and double click detection.
        - Magnetometer gain, data rate, operating mode, identity, status and
          the relative temperature sensor.
        - A software compass heading, optionally tilt-compensated.
        - The Level-3 interrupt API (INT1 / INT2).

    Args:
        accel_connection: RegisterConnection (I²C) bound to 0x19.
        mag_connection: RegisterConnection (I²C) bound to 0x1E.

    Raises:
        ValueError: If the magnetometer identity does not match.
    """

    # FIFO modes (FIFO_CTRL_REG_A.FM<1:0>).
    FIFO_BYPASS = 0
    FIFO_MODE = 1
    FIFO_STREAM = 2
    FIFO_TRIGGER = 3

    # Magnetometer operating modes (MR_REG_M.MD<1:0>).
    MAG_MODE_CONTINUOUS = 0
    MAG_MODE_SINGLE = 1
    MAG_MODE_SLEEP = 2

    # Click axis masks.
    CLICK_AXIS_X = 0x01
    CLICK_AXIS_Y = 0x02
    CLICK_AXIS_Z = 0x04

    # CTRL_REG1_A bits.
    _CTRL1_ODR_MASK = 0xF0
    _CTRL1_LPEN = 0x08
    _CTRL1_AXES_MASK = 0x07

    # CTRL_REG2_A bits.
    _CTRL2_HPM_MASK = 0xC0
    _CTRL2_HPCF_MASK = 0x30
    _CTRL2_FDS = 0x08
    _CTRL2_HPCLICK = 0x04
    _CTRL2_HPIS2 = 0x02
    _CTRL2_HPIS1 = 0x01

    # CTRL_REG3_A bits (routing to INT1).
    _CTRL3_I1_CLICK = 0x80
    _CTRL3_I1_AOI1 = 0x40
    _CTRL3_I1_AOI2 = 0x20
    _CTRL3_I1_DRDY1 = 0x10
    _CTRL3_I1_WTM = 0x04
    _CTRL3_I1_OVERRUN = 0x02

    # CTRL_REG4_A bits.
    _CTRL4_BDU = 0x80
    _CTRL4_FS_MASK = 0x30
    _CTRL4_HR = 0x08

    # CTRL_REG5_A bits.
    _CTRL5_BOOT = 0x80
    _CTRL5_FIFO_EN = 0x40
    _CTRL5_LIR_INT1 = 0x08
    _CTRL5_D4D_INT1 = 0x04
    _CTRL5_LIR_INT2 = 0x02
    _CTRL5_D4D_INT2 = 0x01

    # CTRL_REG6_A bits (routing to INT2).
    _CTRL6_I2_CLICK = 0x80
    _CTRL6_I2_INT1 = 0x40
    _CTRL6_I2_INT2 = 0x20
    _CTRL6_BOOT_I1 = 0x10
    _CTRL6_H_LACTIVE = 0x02

    # STATUS_REG_A bits.
    _STATUS_ZYXDA = 0x08

    # FIFO_SRC_REG_A bits.
    _FIFO_SRC_WTM = 0x80
    _FIFO_SRC_OVERRUN = 0x40
    _FIFO_SRC_EMPTY = 0x20
    _FIFO_SRC_LEVEL_MASK = 0x1F

    # INTx_SOURCE_A / CLICK_SRC_A interrupt-activation bit.
    _SOURCE_IA = 0x40

    # CRA_REG_M bits.
    _CRA_TEMP_EN = 0x80
    _CRA_DO_MASK = 0x1C

    # CRB_REG_M bits.
    _CRB_GN_MASK = 0xE0

    # SR_REG_M bits.
    _SR_LOCK = 0x02
    _SR_DRDY = 0x01

    def __init__(self, accel_connection, mag_connection):
        super().__init__(accel_connection, mag_connection)
        self._temp_enabled = False
        self._callback = None
        self._int_pin_used = None
        self._poll_stop = False
        self._poll_thread = None
        self._last_status = 0
        self._enabled_sources = 0

    # --- Accelerometer configuration --------------------------------------

    def set_range(self, range_g):
        """Set the accelerometer full-scale range.

        Args:
            range_g: 2, 4, 8 or 16 (*g*).

        Raises:
            ValueError: If range_g is not one of the supported ranges.
        """
        code = {2: 0x00, 4: 0x10, 8: 0x20, 16: 0x30}.get(range_g)
        if code is None:
            raise ValueError('range_g must be one of 2, 4, 8, 16')
        ctrl4 = self._accel_read(self._REG_CTRL4_A, 1)[0]
        ctrl4 = (ctrl4 & ~self._CTRL4_FS_MASK) | code
        self._accel_write(self._REG_CTRL4_A, ctrl4)
        self._fs_g = range_g

    def set_data_rate(self, rate_hz):
        """Set the accelerometer output data rate.

        Args:
            rate_hz: 0 (power-down), 1, 10, 25, 50, 100, 200, 400, 1344, 1620
                or 5376. The rates 1620 and 5376 Hz exist in low-power mode
                only, 1344 Hz in normal and high-resolution mode only.

        Raises:
            ValueError: If the rate is not supported or not allowed in the
                current operating mode.
        """
        codes = {
            0: 0x0, 1: 0x1, 10: 0x2, 25: 0x3, 50: 0x4,
            100: 0x5, 200: 0x6, 400: 0x7, 1620: 0x8, 1344: 0x9, 5376: 0x9,
        }
        if rate_hz not in codes:
            raise ValueError('rate_hz must be one of 0, 1, 10, 25, 50, 100, 200, 400, 1344, 1620, 5376')
        low_power = self._mode == self.MODE_LOW_POWER
        if rate_hz in (1620, 5376) and not low_power:
            raise ValueError('{} Hz is only available in low-power mode'.format(rate_hz))
        if rate_hz == 1344 and low_power:
            raise ValueError('1344 Hz is not available in low-power mode (use 5376 Hz)')
        ctrl1 = self._accel_read(self._REG_CTRL1_A, 1)[0]
        ctrl1 = (ctrl1 & ~self._CTRL1_ODR_MASK) | (codes[rate_hz] << 4)
        self._accel_write(self._REG_CTRL1_A, ctrl1)
        self._odr_hz = float(rate_hz)

    def set_mode(self, mode):
        """Set the accelerometer operating mode.

        Low-power mode uses 8-bit output, normal mode 10-bit and
        high-resolution mode 12-bit; the sensitivity changes accordingly.

        Args:
            mode: MODE_LOW_POWER (0), MODE_NORMAL (1) or
                MODE_HIGH_RESOLUTION (2).

        Raises:
            ValueError: If mode is not one of the three operating modes.
        """
        if mode == self.MODE_LOW_POWER:
            lpen, hr = True, False
        elif mode == self.MODE_NORMAL:
            lpen, hr = False, False
        elif mode == self.MODE_HIGH_RESOLUTION:
            lpen, hr = False, True
        else:
            raise ValueError('mode must be MODE_LOW_POWER, MODE_NORMAL or MODE_HIGH_RESOLUTION')
        ctrl1 = self._accel_read(self._REG_CTRL1_A, 1)[0]
        ctrl4 = self._accel_read(self._REG_CTRL4_A, 1)[0]
        if lpen:
            ctrl1 |= self._CTRL1_LPEN
        else:
            ctrl1 &= ~self._CTRL1_LPEN
        if hr:
            ctrl4 |= self._CTRL4_HR
        else:
            ctrl4 &= ~self._CTRL4_HR
        self._accel_write(self._REG_CTRL1_A, ctrl1)
        self._accel_write(self._REG_CTRL4_A, ctrl4)
        self._mode = mode

    def set_axes(self, x=True, y=True, z=True):
        """Enable or disable the individual accelerometer axes.

        Args:
            x: True to enable the X axis.
            y: True to enable the Y axis.
            z: True to enable the Z axis.
        """
        ctrl1 = self._accel_read(self._REG_CTRL1_A, 1)[0]
        ctrl1 &= ~self._CTRL1_AXES_MASK
        if x:
            ctrl1 |= 0x01
        if y:
            ctrl1 |= 0x02
        if z:
            ctrl1 |= 0x04
        self._accel_write(self._REG_CTRL1_A, ctrl1)

    def power_down(self):
        """Put the accelerometer into power-down mode.

        The output data rate is set to 0000; the register contents are
        retained, so ``set_data_rate()`` resumes the conversions.
        """
        ctrl1 = self._accel_read(self._REG_CTRL1_A, 1)[0]
        self._accel_write(self._REG_CTRL1_A, ctrl1 & ~self._CTRL1_ODR_MASK)
        self._odr_hz = 0.0

    # --- Accelerometer readings -------------------------------------------

    def read_acceleration_raw(self):
        """Read the acceleration as raw counts.

        Returns:
            tuple: (x, y, z) signed counts at the current resolution.
        """
        raw = self._accel_read(self._REG_OUT_X_L_A, 6)
        return self._raw_accel(raw)

    def new_data_available(self):
        """Report whether a new accelerometer sample is available.

        Returns:
            bool: True if ``STATUS_REG_A.ZYXDA`` is set.
        """
        return bool(self._accel_read(self._REG_STATUS_A, 1)[0] & self._STATUS_ZYXDA)

    def read_status(self):
        """Read the raw accelerometer status register.

        Returns:
            int: The ``STATUS_REG_A`` byte (overrun and data-available flags).
        """
        return self._accel_read(self._REG_STATUS_A, 1)[0]

    # --- High-pass filter ---------------------------------------------------

    def set_high_pass(self, mode=0, cutoff=0, filter_output=False,
                      on_ia1=False, on_ia2=False, on_click=False):
        """Configure the accelerometer high-pass filter.

        Args:
            mode: HPM code 0–3 (0 = normal, 1 = reference signal, 2 = normal,
                3 = autoreset on interrupt event).
            cutoff: HPCF code 0–3 (high-pass cutoff selection).
            filter_output: True to route the filtered data to the output
                registers and the FIFO (``FDS``).
            on_ia1: True to high-pass the input of interrupt generator 1.
            on_ia2: True to high-pass the input of interrupt generator 2.
            on_click: True to high-pass the click detector input.

        Raises:
            ValueError: If mode or cutoff is out of range.
        """
        if mode < 0 or mode > 3:
            raise ValueError('mode must be 0..3')
        if cutoff < 0 or cutoff > 3:
            raise ValueError('cutoff must be 0..3')
        ctrl2 = (mode << 6) | (cutoff << 4)
        if filter_output:
            ctrl2 |= self._CTRL2_FDS
        if on_click:
            ctrl2 |= self._CTRL2_HPCLICK
        if on_ia2:
            ctrl2 |= self._CTRL2_HPIS2
        if on_ia1:
            ctrl2 |= self._CTRL2_HPIS1
        self._accel_write(self._REG_CTRL2_A, ctrl2)

    def reset_high_pass(self):
        """Reset the high-pass filter and return the reference value.

        Reading ``REFERENCE_A`` resets the filter in HPM = 00 mode.

        Returns:
            int: The reference value (0–255).
        """
        return self._accel_read(self._REG_REFERENCE_A, 1)[0]

    def set_reference(self, value):
        """Write the high-pass reference value.

        Args:
            value: Reference value 0–255.
        """
        self._accel_write(self._REG_REFERENCE_A, value & 0xFF)

    # --- FIFO ---------------------------------------------------------------

    def set_fifo(self, mode, watermark=0, trigger_int2=False):
        """Configure the accelerometer FIFO.

        Args:
            mode: FIFO_BYPASS (0), FIFO_MODE (1), FIFO_STREAM (2) or
                FIFO_TRIGGER (3).
            watermark: Watermark level 0–31.
            trigger_int2: True to use INT2 as the trigger source in trigger
                mode, False for INT1.

        Raises:
            ValueError: If mode or watermark is out of range.
        """
        if mode < 0 or mode > 3:
            raise ValueError('mode must be 0..3')
        if watermark < 0 or watermark > 31:
            raise ValueError('watermark must be 0..31')
        fifo = (mode << 6) | (watermark & 0x1F)
        if trigger_int2:
            fifo |= 0x20
        self._accel_write(self._REG_FIFO_CTRL_A, fifo)
        ctrl5 = self._accel_read(self._REG_CTRL5_A, 1)[0]
        if mode == self.FIFO_BYPASS:
            ctrl5 &= ~self._CTRL5_FIFO_EN
        else:
            ctrl5 |= self._CTRL5_FIFO_EN
        self._accel_write(self._REG_CTRL5_A, ctrl5)

    def fifo_level(self):
        """Return the number of unread sample sets in the FIFO.

        Returns:
            int: The number of unread sample sets (0–32).
        """
        return self._accel_read(self._REG_FIFO_SRC_A, 1)[0] & self._FIFO_SRC_LEVEL_MASK

    def read_fifo(self, count=None):
        """Read sample sets from the FIFO.

        Each sample set is popped with one six-byte burst read.

        Args:
            count: Number of sample sets to read; None reads all of them.

        Returns:
            list: A list of (x, y, z) acceleration tuples in *g*.
        """
        if count is None:
            count = self.fifo_level()
        result = []
        for _ in range(count):
            raw = self._accel_read(self._REG_OUT_X_L_A, 6)
            result.append(self._convert_accel(raw))
        return result

    def read_fifo_status(self):
        """Read the decoded FIFO status.

        Returns:
            tuple: (watermark, overrun, empty, level).
        """
        src = self._accel_read(self._REG_FIFO_SRC_A, 1)[0]
        return (bool(src & self._FIFO_SRC_WTM),
                bool(src & self._FIFO_SRC_OVERRUN),
                bool(src & self._FIFO_SRC_EMPTY),
                src & self._FIFO_SRC_LEVEL_MASK)

    # --- Inertial interrupt generators --------------------------------------

    def set_motion_interrupt(self, generator, events, threshold_g,
                             duration_ms=0, and_mode=False):
        """Configure one inertial interrupt generator.

        Args:
            generator: 1 or 2.
            events: Bit mask of the enabled events — ``ZHIE`` (0x20),
                ``ZLIE`` (0x10), ``YHIE`` (0x08), ``YLIE`` (0x04),
                ``XHIE`` (0x02) or ``XLIE`` (0x01).
            threshold_g: Threshold in *g*, relative to the current range.
            duration_ms: Minimum duration in milliseconds; 0 disables the
                duration filter.
            and_mode: True to combine the events with AND instead of OR.

        Raises:
            ValueError: If the generator or the event mask is invalid.
        """
        cfg, ths, dur = self._int_regs(generator)
        if events < 0 or events > 0x3F:
            raise ValueError('events must be a 6-bit mask (0..63)')
        value = events & 0x3F
        if and_mode:
            value |= 0x80
        self._accel_write(cfg, value)
        self._accel_write(ths, self._encode_threshold(threshold_g))
        self._accel_write(dur, self._encode_duration(duration_ms))

    def set_free_fall(self, generator=1, threshold_g=0.35, duration_ms=30):
        """Arm the free-fall detector of one generator.

        All three axes are watched for a low event and combined with AND.

        Args:
            generator: 1 or 2.
            threshold_g: Threshold in *g*.
            duration_ms: Minimum duration in milliseconds.
        """
        self.set_motion_interrupt(generator, 0x15, threshold_g, duration_ms, and_mode=True)

    def set_orientation(self, generator=1, position=False, four_d=False):
        """Arm the 6D orientation detector of one generator.

        Args:
            generator: 1 or 2.
            position: False for movement recognition (a one-ODR pulse),
                True for position recognition (a level).
            four_d: True for 4D detection, which drops the Z axis from the
                zone detection.
        """
        cfg, _ths, _dur = self._int_regs(generator)
        events = 0x3F
        if four_d:
            events &= ~0x30
        value = events | 0x40
        if position:
            value |= 0x80
        self._accel_write(cfg, value)
        ctrl5 = self._accel_read(self._REG_CTRL5_A, 1)[0]
        if four_d:
            ctrl5 |= (self._CTRL5_D4D_INT1 if generator == 1 else self._CTRL5_D4D_INT2)
        else:
            ctrl5 &= ~(self._CTRL5_D4D_INT1 if generator == 1 else self._CTRL5_D4D_INT2)
        self._accel_write(self._REG_CTRL5_A, ctrl5)

    def set_latch(self, generator, enabled):
        """Latch or release the interrupt of one generator.

        A latched interrupt stays asserted until ``INTx_SOURCE_A`` is read.

        Args:
            generator: 1 or 2.
            enabled: True to latch, False to release.

        Raises:
            ValueError: If the generator is not 1 or 2.
        """
        if generator not in (1, 2):
            raise ValueError('generator must be 1 or 2')
        ctrl5 = self._accel_read(self._REG_CTRL5_A, 1)[0]
        bit = self._CTRL5_LIR_INT1 if generator == 1 else self._CTRL5_LIR_INT2
        if enabled:
            ctrl5 |= bit
        else:
            ctrl5 &= ~bit
        self._accel_write(self._REG_CTRL5_A, ctrl5)

    def read_interrupt_source(self, generator):
        """Read the interrupt source register of one generator.

        Reading the register clears the latched interrupt.

        Args:
            generator: 1 or 2.

        Returns:
            int: The raw ``INTx_SOURCE_A`` byte.

        Raises:
            ValueError: If the generator is not 1 or 2.
        """
        if generator not in (1, 2):
            raise ValueError('generator must be 1 or 2')
        reg = self._REG_INT1_SOURCE_A if generator == 1 else self._REG_INT2_SOURCE_A
        return self._accel_read(reg, 1)[0]

    def _int_regs(self, generator):
        """Return the (config, threshold, duration) register triple of a generator."""
        if generator == 1:
            return (self._REG_INT1_CFG_A, self._REG_INT1_THS_A, self._REG_INT1_DURATION_A)
        if generator == 2:
            return (self._REG_INT2_CFG_A, self._REG_INT2_THS_A, self._REG_INT2_DURATION_A)
        raise ValueError('generator must be 1 or 2')

    def _encode_threshold(self, threshold_g):
        """Convert a threshold in *g* into a 7-bit register code."""
        code = int(threshold_g * 128.0 / self._fs_g + 0.5)
        if code < 0:
            code = 0
        if code > 127:
            code = 127
        return code

    def _encode_duration(self, duration_ms, bits=7):
        """Convert a duration in milliseconds into a register code.

        One LSB is 1/ODR; the code is clamped to the register width.
        """
        if self._odr_hz <= 0:
            code = 0
        else:
            code = int(duration_ms * self._odr_hz / 1000.0 + 0.5)
        if code < 0:
            code = 0
        if code > (1 << bits) - 1:
            code = (1 << bits) - 1
        return code

    # --- Click detection ----------------------------------------------------

    def set_click(self, single=0, double=0, threshold_g=0.5,
                  time_limit_ms=10, latency_ms=20, window_ms=50):
        """Configure the click detector.

        Args:
            single: Axis mask of the enabled single-click detectors
                (X = 1, Y = 2, Z = 4).
            double: Axis mask of the enabled double-click detectors
                (X = 1, Y = 2, Z = 4).
            threshold_g: Click threshold in *g*.
            time_limit_ms: Maximum time between the two clicks of a double
                click, in milliseconds.
            latency_ms: Minimum time between two consecutive clicks, in
                milliseconds.
            window_ms: Time the click detector observes, in milliseconds.

        Raises:
            ValueError: If an axis mask is out of range.
        """
        if single < 0 or single > 0x07:
            raise ValueError('single must be an axis mask 0..7')
        if double < 0 or double > 0x07:
            raise ValueError('double must be an axis mask 0..7')
        cfg = 0
        if single & self.CLICK_AXIS_X:
            cfg |= 0x01
        if single & self.CLICK_AXIS_Y:
            cfg |= 0x04
        if single & self.CLICK_AXIS_Z:
            cfg |= 0x10
        if double & self.CLICK_AXIS_X:
            cfg |= 0x02
        if double & self.CLICK_AXIS_Y:
            cfg |= 0x08
        if double & self.CLICK_AXIS_Z:
            cfg |= 0x20
        self._accel_write(self._REG_CLICK_CFG_A, cfg)
        self._accel_write(self._REG_CLICK_THS_A, self._encode_threshold(threshold_g))
        self._accel_write(self._REG_TIME_LIMIT_A, self._encode_duration(time_limit_ms, 7))
        self._accel_write(self._REG_TIME_LATENCY_A, self._encode_duration(latency_ms, 8))
        self._accel_write(self._REG_TIME_WINDOW_A, self._encode_duration(window_ms, 8))

    def read_click_source(self):
        """Read the click source register.

        Reading the register clears the latched click interrupt.

        Returns:
            int: The raw ``CLICK_SRC_A`` byte.
        """
        return self._accel_read(self._REG_CLICK_SRC_A, 1)[0]

    def set_interrupt_polarity(self, active_low):
        """Set the polarity of the INT1 / INT2 outputs.

        Args:
            active_low: True for active-low, False for active-high (default).
        """
        ctrl6 = self._accel_read(self._REG_CTRL6_A, 1)[0]
        if active_low:
            ctrl6 |= self._CTRL6_H_LACTIVE
        else:
            ctrl6 &= ~self._CTRL6_H_LACTIVE
        self._accel_write(self._REG_CTRL6_A, ctrl6)

    # --- Magnetometer -------------------------------------------------------

    def set_mag_range(self, range_ut):
        """Set the magnetometer full-scale range.

        Args:
            range_ut: 130, 190, 250, 400, 470, 560 or 810 (µT).

        Raises:
            ValueError: If the range is not supported.
        """
        codes = {130: 0x20, 190: 0x40, 250: 0x60, 400: 0x80,
                 470: 0xA0, 560: 0xC0, 810: 0xE0}
        if range_ut not in codes:
            raise ValueError('range_ut must be one of 130, 190, 250, 400, 470, 560, 810')
        crb = self._mag_read(self._REG_CRB_M, 1)[0]
        crb = (crb & ~self._CRB_GN_MASK) | codes[range_ut]
        self._mag_write(self._REG_CRB_M, crb)
        self._mag_range_ut = range_ut

    def set_mag_data_rate(self, rate_hz):
        """Set the magnetometer output data rate.

        Args:
            rate_hz: 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220.

        Raises:
            ValueError: If the rate is not supported.
        """
        rates = (0.75, 1.5, 3, 7.5, 15, 30, 75, 220)
        for code, rate in enumerate(rates):
            if abs(rate - rate_hz) < 1e-6:
                cra = self._mag_read(self._REG_CRA_M, 1)[0]
                cra = (cra & ~self._CRA_DO_MASK) | (code << 2)
                self._mag_write(self._REG_CRA_M, cra)
                return
        raise ValueError('rate_hz must be one of 0.75, 1.5, 3, 7.5, 15, 30, 75, 220')

    def set_mag_mode(self, mode):
        """Set the magnetometer operating mode.

        Args:
            mode: MAG_MODE_CONTINUOUS (0), MAG_MODE_SINGLE (1) or
                MAG_MODE_SLEEP (2).

        Raises:
            ValueError: If the mode is not supported.
        """
        if mode not in (self.MAG_MODE_CONTINUOUS, self.MAG_MODE_SINGLE, self.MAG_MODE_SLEEP):
            raise ValueError('mode must be MAG_MODE_CONTINUOUS, MAG_MODE_SINGLE or MAG_MODE_SLEEP')
        mr = self._mag_read(self._REG_MR_M, 1)[0]
        mr = (mr & ~0x03) | mode
        self._mag_write(self._REG_MR_M, mr)

    def read_magnetic_raw(self):
        """Read the magnetic field as raw counts.

        Returns:
            tuple: (x, y, z) signed counts in X, Y, Z order.
        """
        raw = self._mag_read(self._REG_OUT_X_H_M, 6)
        return self._reorder_mag(raw)

    def mag_data_ready(self):
        """Report whether a new magnetometer sample is available.

        Returns:
            bool: True if ``SR_REG_M.DRDY`` is set.
        """
        return bool(self._mag_read(self._REG_SR_M, 1)[0] & self._SR_DRDY)

    def read_mag_status(self):
        """Read the decoded magnetometer status register.

        Returns:
            tuple: (drdy, lock).
        """
        sr = self._mag_read(self._REG_SR_M, 1)[0]
        return (bool(sr & self._SR_DRDY), bool(sr & self._SR_LOCK))

    def read_mag_identity(self):
        """Read the magnetometer identity bytes.

        Returns:
            bytes: The three identity bytes (0x48 0x34 0x33 on a real part).
        """
        return bytes(self._mag_read(self._REG_IRA_M, 3))

    def enable_temperature(self, enabled):
        """Enable or disable the magnetometer temperature sensor.

        Args:
            enabled: True to enable the sensor, False to disable it.
        """
        cra = self._mag_read(self._REG_CRA_M, 1)[0]
        if enabled:
            cra |= self._CRA_TEMP_EN
        else:
            cra &= ~self._CRA_TEMP_EN
        self._mag_write(self._REG_CRA_M, cra)
        self._temp_enabled = bool(enabled)

    def read_temperature_delta(self):
        """Read the relative temperature of the magnetometer.

        The sensor is enabled on first use. The datasheet gives no zero
        reference, so the value is a *delta* relative to an unspecified
        temperature and not an absolute °C reading.

        Returns:
            float: Temperature difference in °C (8 LSB/°C).
        """
        if not self._temp_enabled:
            self.enable_temperature(True)
        raw = self._mag_read(self._REG_TEMP_OUT_H_M, 2)
        value = to_signed((raw[0] << 8) | raw[1], 16) >> 4
        return value / 8.0

    # --- Compass ------------------------------------------------------------

    def heading(self, tilt_compensate=True):
        """Compute the compass heading.

        The heading is measured in the sensor frame: the datasheet does not
        document the axis alignment between the two dies, so the tilt
        compensation assumes both report in the same body frame.

        Args:
            tilt_compensate: True to compensate for the tilt reported by the
                accelerometer, False for the flat (magnetic-only) heading.

        Returns:
            float: The heading in degrees, 0–360.
        """
        mx, my, mz = self.read_magnetic()
        if not tilt_compensate:
            return self._wrap_degrees(math.degrees(math.atan2(my, mx)))
        ax, ay, az = self.read_acceleration()
        roll = math.atan2(ay, az)
        pitch = math.atan2(-ax, ay * math.sin(roll) + az * math.cos(roll))
        xh = mx * math.cos(pitch) + mz * math.sin(pitch)
        yh = (mx * math.sin(roll) * math.sin(pitch) + my * math.cos(roll)
              - mz * math.sin(roll) * math.cos(pitch))
        return self._wrap_degrees(math.degrees(math.atan2(yh, xh)))

    def _wrap_degrees(self, degrees):
        """Wrap an angle in degrees into the 0–360 range."""
        value = degrees % 360.0
        if value < 0:
            value += 360.0
        return value

    # --- Interrupt API ------------------------------------------------------

    def enable_interrupt(self, source, pin=1):
        """Route one interrupt source to INT1 or INT2.

        Only the routing is configured; the generator or function behind the
        source is set up with the ``set_*`` methods.

        Args:
            source: One of the ``LSM303Source.SOURCE_*`` constants.
            pin: 1 for INT1, 2 for INT2. The argument is ignored for sources
                that can only appear on one of the two lines.

        Raises:
            ValueError: If the source cannot be routed or the pin is invalid.
        """
        if pin not in (1, 2):
            raise ValueError('pin must be 1 or 2')
        if source == LSM303Source.SOURCE_MAG_DATA_READY:
            raise ValueError('SOURCE_MAG_DATA_READY is reported on the DRDY pin and cannot be routed')
        ctrl3_bit, ctrl6_bit = self._source_bits(source)
        if ctrl3_bit is None and ctrl6_bit is None:
            raise ValueError('unknown interrupt source')
        if ctrl3_bit is not None and (pin == 1 or ctrl6_bit is None):
            ctrl3 = self._accel_read(self._REG_CTRL3_A, 1)[0]
            self._accel_write(self._REG_CTRL3_A, ctrl3 | ctrl3_bit)
        if ctrl6_bit is not None and (pin == 2 or ctrl3_bit is None):
            ctrl6 = self._accel_read(self._REG_CTRL6_A, 1)[0]
            self._accel_write(self._REG_CTRL6_A, ctrl6 | ctrl6_bit)
        self._enabled_sources |= source

    def disable_interrupt(self, source, pin=1):
        """Remove one interrupt source from INT1 or INT2.

        Args:
            source: One of the ``LSM303Source.SOURCE_*`` constants.
            pin: 1 for INT1, 2 for INT2.

        Raises:
            ValueError: If the source cannot be routed or the pin is invalid.
        """
        if pin not in (1, 2):
            raise ValueError('pin must be 1 or 2')
        if source == LSM303Source.SOURCE_MAG_DATA_READY:
            raise ValueError('SOURCE_MAG_DATA_READY is reported on the DRDY pin and cannot be routed')
        ctrl3_bit, ctrl6_bit = self._source_bits(source)
        if ctrl3_bit is None and ctrl6_bit is None:
            raise ValueError('unknown interrupt source')
        if ctrl3_bit is not None and (pin == 1 or ctrl6_bit is None):
            ctrl3 = self._accel_read(self._REG_CTRL3_A, 1)[0]
            self._accel_write(self._REG_CTRL3_A, ctrl3 & ~ctrl3_bit)
        if ctrl6_bit is not None and (pin == 2 or ctrl3_bit is None):
            ctrl6 = self._accel_read(self._REG_CTRL6_A, 1)[0]
            self._accel_write(self._REG_CTRL6_A, ctrl6 & ~ctrl6_bit)
        self._enabled_sources &= ~source

    def _source_bits(self, source):
        """Return the (CTRL_REG3_A, CTRL_REG6_A) routing bits of a source."""
        if source == LSM303Source.SOURCE_DATA_READY:
            return (self._CTRL3_I1_DRDY1, None)
        if source == LSM303Source.SOURCE_WATERMARK:
            return (self._CTRL3_I1_WTM, None)
        if source == LSM303Source.SOURCE_OVERRUN:
            return (self._CTRL3_I1_OVERRUN, None)
        if source == LSM303Source.SOURCE_IA1:
            return (self._CTRL3_I1_AOI1, self._CTRL6_I2_INT1)
        if source == LSM303Source.SOURCE_IA2:
            return (self._CTRL3_I1_AOI2, self._CTRL6_I2_INT2)
        if source == LSM303Source.SOURCE_CLICK:
            return (self._CTRL3_I1_CLICK, self._CTRL6_I2_CLICK)
        if source == LSM303Source.SOURCE_BOOT:
            return (None, self._CTRL6_BOOT_I1)
        return (None, None)

    def poll_interrupt(self):
        """Read the interrupt status of every enabled source.

        Reading ``INTx_SOURCE_A`` and ``CLICK_SRC_A`` clears their latched
        flags.

        Returns:
            int: A mask of the ``LSM303Source.SOURCE_*`` constants.
        """
        status = 0
        if self._accel_read(self._REG_STATUS_A, 1)[0] & self._STATUS_ZYXDA:
            status |= LSM303Source.SOURCE_DATA_READY
        fifo = self._accel_read(self._REG_FIFO_SRC_A, 1)[0]
        if fifo & self._FIFO_SRC_WTM:
            status |= LSM303Source.SOURCE_WATERMARK
        if fifo & self._FIFO_SRC_OVERRUN:
            status |= LSM303Source.SOURCE_OVERRUN
        if self._enabled_sources & LSM303Source.SOURCE_IA1:
            if self._accel_read(self._REG_INT1_SOURCE_A, 1)[0] & self._SOURCE_IA:
                status |= LSM303Source.SOURCE_IA1
        if self._enabled_sources & LSM303Source.SOURCE_IA2:
            if self._accel_read(self._REG_INT2_SOURCE_A, 1)[0] & self._SOURCE_IA:
                status |= LSM303Source.SOURCE_IA2
        if self._enabled_sources & LSM303Source.SOURCE_CLICK:
            if self._accel_read(self._REG_CLICK_SRC_A, 1)[0] & self._SOURCE_IA:
                status |= LSM303Source.SOURCE_CLICK
        if self._mag_read(self._REG_SR_M, 1)[0] & self._SR_DRDY:
            status |= LSM303Source.SOURCE_MAG_DATA_READY
        return status

    def on_interrupt(self, callback, pin=1):
        """Subscribe to the INT1 or INT2 line.

        The edge direction follows the polarity set with
        :meth:`set_interrupt_polarity`. Without an INT pin — for example on a
        Linux host without a GPIO wired up — a 5 ms polling thread takes over.

        Args:
            callback: Callable(status: int) receiving the
                :meth:`poll_interrupt` mask.
            pin: 1 for INT1, 2 for INT2. The line must be wired to the INT
                pin of the accelerometer connection.

        Raises:
            ValueError: If the pin is not 1 or 2.
        """
        if pin not in (1, 2):
            raise ValueError('pin must be 1 or 2')
        self._callback = callback
        int_pin = getattr(self._accel, 'int_pin', None)
        self._int_pin_used = int_pin
        if int_pin is not None:
            from periph.connection.input_pin import InputPin
            active_low = bool(self._accel_read(self._REG_CTRL6_A, 1)[0] & self._CTRL6_H_LACTIVE)
            int_pin.on_edge(self._int_handler,
                            InputPin.FALLING if active_low else InputPin.RISING)
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

    # --- Miscellaneous ------------------------------------------------------

    def reboot(self):
        """Reboot the accelerometer's memory content.

        The ``BOOT`` bit reloads the factory trim values. The datasheet does
        not say whether the control registers survive, so the initialization
        sequence is run again afterwards and any configuration applied after
        :meth:`init` has to be repeated.
        """
        ctrl5 = self._accel_read(self._REG_CTRL5_A, 1)[0]
        self._accel_write(self._REG_CTRL5_A, ctrl5 | self._CTRL5_BOOT)
        _delay_ms(5)
        self.init()
        self._temp_enabled = False
