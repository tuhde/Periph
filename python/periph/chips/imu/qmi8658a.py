"""QMI8658A — 6-axis inertial measurement unit (QST).

3-axis gyroscope (±16 … ±2048 dps), 3-axis accelerometer (±2 … ±16 g) and a
16-bit temperature sensor, with a FIFO, two interrupt pins and a motion
co-processor (any-motion, no-motion, significant-motion, tap, pedometer,
wake-on-motion).

Communicates over I²C at address 0x6B (SA0 = GND) or 0x6A (SA0 = VDDIO), or
over 4-wire SPI (mode 3, read bit 0x80, no multi-byte bit). I3C and 3-wire
SPI are out of scope. Configuration registers CTRL1-CTRL9 and the CAL
registers are always written one register per transaction.
"""

import time

from periph.connection.register import to_signed

_G = 9.80665
_DEG_TO_RAD = 3.141592653589793 / 180.0


class QMI8658AMinimal:
    """QMI8658A minimal interface — acceleration and angular rate.

    Runs the datasheet initialisation sequence: soft reset, ``WHO_AM_I``
    check, address auto-increment on, little-endian reads, CTRL9 handshake by
    polling, then both sensors on.

    Default configuration (baked in at construction):
        - Accelerometer ±2 g, gyroscope ±256 dps
        - Both output data rates code 5 (224.2 Hz in 6DOF mode)
        - Both low-pass filters on, mode 3 (13.37 % of ODR)
        - FIFO bypass (data-ready active), all INT pins high-Z, engines off

    Args:
        connection: RegisterConnection (I²C, SMBus or SPI built with
            ``read_bit=0x80``) pointing at the device.

    Raises:
        ValueError: If WHO_AM_I does not read 0x05.
    """

    _REG_WHO_AM_I = 0x00
    _REG_REVISION_ID = 0x01
    _REG_CTRL1 = 0x02
    _REG_CTRL2 = 0x03
    _REG_CTRL3 = 0x04
    _REG_CTRL5 = 0x06
    _REG_CTRL7 = 0x08
    _REG_CTRL8 = 0x09
    _REG_CTRL9 = 0x0A
    _REG_CAL1_L = 0x0B
    _REG_FIFO_WTM_TH = 0x13
    _REG_FIFO_CTRL = 0x14
    _REG_FIFO_SMPL_CNT = 0x15
    _REG_FIFO_STATUS = 0x16
    _REG_FIFO_DATA = 0x17
    _REG_STATUSINT = 0x2D
    _REG_STATUS0 = 0x2E
    _REG_STATUS1 = 0x2F
    _REG_TIMESTAMP = 0x30
    _REG_TEMP_L = 0x33
    _REG_AX_L = 0x35
    _REG_GX_L = 0x3B
    _REG_COD_STATUS = 0x46
    _REG_RESET_STATUS = 0x4D
    _REG_DVX_L = 0x51
    _REG_TAP_STATUS = 0x59
    _REG_STEP_CNT = 0x5A
    _REG_RESET = 0x60

    _WHO_AM_I_VALUE = 0x05
    _RESET_CMD = 0xB0

    _CMD_ACK = 0x00
    _CMD_RST_FIFO = 0x04
    _CMD_REQ_FIFO = 0x05
    _CMD_WRITE_WOM = 0x08
    _CMD_ACCEL_OFFSET = 0x09
    _CMD_GYRO_OFFSET = 0x0A
    _CMD_CONFIG_TAP = 0x0C
    _CMD_CONFIG_PEDOMETER = 0x0D
    _CMD_CONFIG_MOTION = 0x0E
    _CMD_RESET_PEDOMETER = 0x0F
    _CMD_COPY_USID = 0x10
    _CMD_SET_RPU = 0x11
    _CMD_AHB_CLOCK_GATING = 0x12
    _CMD_COD = 0xA2
    _CMD_APPLY_GYRO_GAINS = 0xAA

    def __init__(self, connection):
        self._connection = connection
        self._ctrl1 = 0x40
        self._ctrl7 = 0x03
        self._ctrl8 = 0x80
        self._accel_fs = 0
        self._gyro_fs = 4
        self._accel_odr = 5
        self._gyro_odr = 5
        self._accel_sens = 16384.0
        self._gyro_sens = 128.0
        self._fifo_ctrl = 0x00
        self._fifo_wtm = 0
        self._sync = False
        self._reset_and_configure()

    def _reset_and_configure(self):
        time.sleep(0.015)
        self._connection.write_reg(self._REG_RESET, self._RESET_CMD)
        time.sleep(0.015)
        self._connection.read_reg(self._REG_RESET_STATUS, 1)   # 0x80 after reset, informational
        who = self._connection.read_reg(self._REG_WHO_AM_I, 1)[0]
        if who != self._WHO_AM_I_VALUE:
            raise ValueError('QMI8658A WHO_AM_I: expected 0x{:02X}, got 0x{:02X}'.format(
                self._WHO_AM_I_VALUE, who))
        self._ctrl1 = 0x40
        self._ctrl7 = 0x03
        self._ctrl8 = 0x80
        self._fifo_ctrl = 0x00
        self._fifo_wtm = 0
        self._sync = False
        self._connection.write_reg(self._REG_CTRL1, self._ctrl1)   # ADDR_AI=1, BE=0
        self._connection.write_reg(self._REG_CTRL8, self._ctrl8)   # handshake by polling
        self._accel_fs, self._gyro_fs = 0, 4
        self._accel_odr = self._gyro_odr = 5
        self._update_sens()
        self._connection.write_reg(self._REG_CTRL2, 0x05)
        self._connection.write_reg(self._REG_CTRL3, 0x45)
        self._connection.write_reg(self._REG_CTRL5, 0x77)
        self._connection.write_reg(self._REG_CTRL7, self._ctrl7)
        time.sleep(0.165)                                          # gyroscope turn-on

    def _update_sens(self):
        self._accel_sens = float(16384 >> self._accel_fs)
        self._gyro_sens = float(2048 >> self._gyro_fs)

    def _read_data(self, reg, length):
        """Read sensor data registers (Full overrides this for SyncSample)."""
        return self._connection.read_reg(reg, length)

    def _vec(self, reg, sens, scale):
        raw = self._read_data(reg, 6)
        return tuple(to_signed(raw[2 * i] | (raw[2 * i + 1] << 8), 16) / sens * scale
                     for i in range(3))

    def accel(self):
        """Read linear acceleration.

        Returns:
            tuple[float, float, float]: (x, y, z) in m/s².
        """
        return self._vec(self._REG_AX_L, self._accel_sens, _G)

    def gyro(self):
        """Read angular rate.

        Returns:
            tuple[float, float, float]: (x, y, z) in rad/s.
        """
        return self._vec(self._REG_GX_L, self._gyro_sens, _DEG_TO_RAD)


class QMI8658AFull(QMI8658AMinimal):
    """QMI8658A full interface — extends :class:`QMI8658AMinimal`.

    Adds raw and burst reads, temperature, sample counter, full-scale / ODR /
    filter configuration, power control, SyncSample data locking, the FIFO,
    the motion engines (any / no / significant motion, tap, pedometer,
    wake-on-motion), offsets, calibration-on-demand, self-test, device info,
    pull-up control and interrupts.

    Args:
        connection: RegisterConnection (I²C, SMBus or SPI) pointing at the device.
    """

    SOURCE_DATA_READY = 0x0001
    SOURCE_FIFO_WATERMARK = 0x0002
    SOURCE_FIFO_FULL = 0x0004
    SOURCE_ANY_MOTION = 0x0008
    SOURCE_NO_MOTION = 0x0010
    SOURCE_SIGNIFICANT_MOTION = 0x0020
    SOURCE_STEP = 0x0040
    SOURCE_TAP = 0x0080
    SOURCE_WAKE_ON_MOTION = 0x0100

    # source -> CTRL8 engine bit
    _ENGINE_BIT = {
        0x0008: 0x02,   # any motion
        0x0010: 0x04,   # no motion
        0x0020: 0x08,   # significant motion
        0x0040: 0x10,   # pedometer
        0x0080: 0x01,   # tap
    }

    def __init__(self, connection):
        self._any = [0.25, 3, 0b111, False]
        self._no = [0.125, 30, 0b111, True]
        self._sig = [150, 600]
        self._int_sources = 0
        self._fifo_pin = 2
        self._activity_pin = 2
        self._wom_pin = 1
        self._int_callback = None
        self._int_handler = None
        self._poll_timer = None
        super().__init__(connection)
        self._int_sources = 0
        self._fifo_pin = 2
        self._activity_pin = 2
        self._wom_pin = 1

    # -- low-level helpers -----------------------------------------------

    def _w(self, reg, value):
        self._connection.write_reg(reg, value)

    def _set_ctrl1(self, value):
        self._ctrl1 = value & 0xFF
        self._w(self._REG_CTRL1, self._ctrl1)

    def _set_ctrl7(self, value):
        self._ctrl7 = value & 0xFF
        self._w(self._REG_CTRL7, self._ctrl7)

    def _set_ctrl8(self, value):
        self._ctrl8 = value & 0xFF
        self._w(self._REG_CTRL8, self._ctrl8)

    def _wait_status(self, mask, want, timeout_s, what):
        deadline = time.time() + timeout_s
        while True:
            st = self._connection.read_reg(self._REG_STATUSINT, 1)[0]
            if bool(st & mask) == want:
                return st
            if time.time() > deadline:
                raise OSError('QMI8658A timeout waiting for ' + what)
            time.sleep(0.001)

    def _ctrl9(self, cmd, timeout_s=0.1):
        """Run one CTRL9 command (command, wait CmdDone, acknowledge)."""
        self._w(self._REG_CTRL9, cmd)
        self._wait_status(0x80, True, timeout_s, 'CTRL9 CmdDone')
        self._w(self._REG_CTRL9, self._CMD_ACK)
        self._wait_status(0x80, False, timeout_s, 'CTRL9 ack')

    def _cal(self, b0, b1=0, b2=0, b3=0, b4=0, b5=0, b6=0, b7=0):
        for i, v in enumerate((b0, b1, b2, b3, b4, b5, b6, b7)):
            self._w(self._REG_CAL1_L + i, v & 0xFF)

    def _cal_cmd(self, cmd, params, timeout_s=0.1):
        self._cal(*params)
        self._ctrl9(cmd, timeout_s)

    def _two_sets(self, cmd, set1, set2):
        self._cal_cmd(cmd, tuple(set1) + (0,) * (7 - len(set1)) + (0x10,))
        self._cal_cmd(cmd, tuple(set2) + (0,) * (7 - len(set2)) + (0x20,))

    def _sensors_off(self):
        """Disable both sensors; return the previous enables."""
        prev = self._ctrl7 & 0x03
        if prev:
            self._set_ctrl7(self._ctrl7 & ~0x03)
            time.sleep(0.005)
        return prev

    def _sensors_restore(self, prev):
        if prev:
            self._set_ctrl7(self._ctrl7 | prev)
            time.sleep(0.165 if prev & 0x02 else 0.01)

    def _s16(self, raw, i):
        return to_signed(raw[2 * i] | (raw[2 * i + 1] << 8), 16)

    def _read_data(self, reg, length):
        if not self._sync:
            return self._connection.read_reg(reg, length)
        self._wait_status(0x01, True, 0.1, 'SyncSample Avail')
        self._wait_status(0x02, True, 0.1, 'SyncSample Locked')
        return self._connection.read_reg(reg, length)

    # -- raw data and conversions ----------------------------------------

    def accel_raw(self):
        """Read raw acceleration counts.

        Returns:
            tuple[int, int, int]: Signed 16-bit (x, y, z).
        """
        raw = self._read_data(self._REG_AX_L, 6)
        return tuple(self._s16(raw, i) for i in range(3))

    def gyro_raw(self):
        """Read raw angular-rate counts.

        Returns:
            tuple[int, int, int]: Signed 16-bit (x, y, z).
        """
        raw = self._read_data(self._REG_GX_L, 6)
        return tuple(self._s16(raw, i) for i in range(3))

    def read_all(self):
        """Read temperature, acceleration and angular rate in one burst.

        Returns:
            tuple: ``((ax, ay, az) m/s², (gx, gy, gz) rad/s, temp °C)``.
        """
        raw = self._read_data(self._REG_TEMP_L, 14)
        temp = self._s16(raw, 0) / 256.0
        accel = tuple(self._s16(raw, 1 + i) / self._accel_sens * _G for i in range(3))
        gyro = tuple(self._s16(raw, 4 + i) / self._gyro_sens * _DEG_TO_RAD for i in range(3))
        return accel, gyro, temp

    def temperature(self):
        """Read the die temperature.

        Returns:
            float: Temperature in °C.
        """
        raw = self._read_data(self._REG_TEMP_L, 2)
        return self._s16(raw, 0) / 256.0

    def timestamp(self):
        """Read the 24-bit sample counter.

        Returns:
            int: Counter value, wraps at 0xFFFFFF.
        """
        raw = self._connection.read_reg(self._REG_TIMESTAMP, 3)
        return raw[0] | (raw[1] << 8) | (raw[2] << 16)

    def data_ready(self):
        """Check for new sensor data.

        Returns:
            bool: True when an enabled sensor has new data (``STATUSINT.Avail``
            while SyncSample is on).
        """
        if self._sync:
            return bool(self._connection.read_reg(self._REG_STATUSINT, 1)[0] & 0x01)
        return bool(self._connection.read_reg(self._REG_STATUS0, 1)[0] & 0x03)

    # -- configuration ---------------------------------------------------

    def configure_accel(self, full_scale=0, odr=5):
        """Configure the accelerometer (CTRL2).

        Args:
            full_scale: 0 = ±2 g, 1 = ±4 g, 2 = ±8 g, 3 = ±16 g.
            odr: Output data rate code 0-8 or 12-15 (12-15 only with the
                gyroscope off).

        Raises:
            ValueError: On an out-of-range argument.
        """
        if not 0 <= full_scale <= 3:
            raise ValueError('full_scale must be 0-3')
        if not (0 <= odr <= 8 or 12 <= odr <= 15):
            raise ValueError('odr must be 0-8 or 12-15')
        self._accel_fs = full_scale
        self._accel_odr = odr
        self._update_sens()
        self._w(self._REG_CTRL2, (full_scale << 4) | odr)

    def configure_gyro(self, full_scale=4, odr=5):
        """Configure the gyroscope (CTRL3).

        Args:
            full_scale: 0 = ±16 dps … 7 = ±2048 dps (doubling).
            odr: Output data rate code 0-8.

        Raises:
            ValueError: On an out-of-range argument.
        """
        if not 0 <= full_scale <= 7:
            raise ValueError('full_scale must be 0-7')
        if not 0 <= odr <= 8:
            raise ValueError('odr must be 0-8')
        self._gyro_fs = full_scale
        self._gyro_odr = odr
        self._update_sens()
        self._w(self._REG_CTRL3, (full_scale << 4) | odr)

    def configure_filters(self, accel_filter=True, accel_mode=3, gyro_filter=True, gyro_mode=3):
        """Configure the low-pass filters (CTRL5).

        Args:
            accel_filter: Enable the accelerometer filter.
            accel_mode: 0-3 = 2.66 / 3.63 / 5.39 / 13.37 % of ODR.
            gyro_filter: Enable the gyroscope filter.
            gyro_mode: 0-3, same bandwidths.
        """
        if not (0 <= accel_mode <= 3 and 0 <= gyro_mode <= 3):
            raise ValueError('filter mode must be 0-3')
        self._w(self._REG_CTRL5, ((gyro_mode & 3) << 5) | (0x10 if gyro_filter else 0) |
                ((accel_mode & 3) << 1) | (1 if accel_filter else 0))

    def set_sensors(self, accel=True, gyro=True, gyro_snooze=False):
        """Enable or disable the sensors (CTRL7 aEN / gEN / gSN).

        Re-enabling the gyroscope needs 150 ms + 3/ODR, the accelerometer
        3 ms + 3/ODR before data is valid.

        Args:
            accel: Enable the accelerometer.
            gyro: Enable the gyroscope.
            gyro_snooze: Gyroscope snooze (drive only, no data).
        """
        v = self._ctrl7 & ~0x13
        v |= (0x01 if accel else 0) | (0x02 if gyro else 0) | (0x10 if gyro_snooze else 0)
        self._set_ctrl7(v)

    def set_power_down(self, down=True):
        """Power the chip down or wake it (CTRL1.SensorDisable).

        Powering down disables the sensors first. After waking, wait for the
        oscillator and re-enable the sensors with :meth:`set_sensors`.

        Args:
            down: True to power down, False to wake.
        """
        if down:
            self._set_ctrl7(self._ctrl7 & ~0x03)
            self._set_ctrl1(self._ctrl1 | 0x01)
        else:
            self._set_ctrl1(self._ctrl1 & ~0x01)
            time.sleep(0.015)

    def set_sync_sample(self, enabled):
        """Enable or disable SyncSample (data-locking) mode.

        Incompatible with the FIFO and the motion engines.

        Args:
            enabled: True to enable.
        """
        if enabled:
            self._cal_cmd(self._CMD_AHB_CLOCK_GATING, (1,))
            self._set_ctrl7(self._ctrl7 | 0x80)
        else:
            self._set_ctrl7(self._ctrl7 & ~0x80)
            self._cal_cmd(self._CMD_AHB_CLOCK_GATING, (0,))
        self._sync = bool(enabled)

    # -- FIFO ------------------------------------------------------------

    def configure_fifo(self, mode=2, size=3, watermark=0):
        """Configure the FIFO.

        Args:
            mode: 0 = bypass, 1 = FIFO (stops when full), 2 = stream.
            size: 0-3 = 16 / 32 / 64 / 128 samples.
            watermark: Watermark in samples (FIFO_WTM_TH), 0 disables.
        """
        if not (0 <= mode <= 2 and 0 <= size <= 3):
            raise ValueError('mode must be 0-2 and size 0-3')
        self._fifo_wtm = watermark & 0xFF
        self._w(self._REG_FIFO_WTM_TH, self._fifo_wtm)
        self._fifo_ctrl = (size << 2) | mode
        self._w(self._REG_FIFO_CTRL, self._fifo_ctrl)
        if mode == 0:
            self._set_ctrl7(self._ctrl7 & ~0x20)    # DRDY back on

    def fifo_status(self):
        """Read the raw FIFO_STATUS byte.

        Returns:
            int: Full (bit 7), watermark (6), overflow (5), not-empty (4) flags.
        """
        return self._connection.read_reg(self._REG_FIFO_STATUS, 1)[0]

    def fifo_count(self):
        """Number of bytes in the FIFO.

        Returns:
            int: 2 × the sample-word count.
        """
        raw = self._connection.read_reg(self._REG_FIFO_STATUS, 1)[0]
        low = self._connection.read_reg(self._REG_FIFO_SMPL_CNT, 1)[0]
        return 2 * (((raw & 0x03) << 8) | low)

    def read_fifo(self, count=None):
        """Drain the FIFO using the read-mode procedure.

        Frames are little-endian 6-byte vectors: accelerometer first when
        both sensors are on (12 bytes per sample).

        Args:
            count: Maximum number of bytes (default: all available).

        Returns:
            bytes: The FIFO contents.
        """
        total = self.fifo_count()
        n = total if count is None else min(count, total)
        if n <= 0:
            return b''
        self._ctrl9(self._CMD_REQ_FIFO)
        self._w(self._REG_CTRL1, self._ctrl1 & ~0x40)       # ADDR_AI off: FIFO_DATA repeats
        out = bytearray()
        try:
            while len(out) < n:
                chunk = min(32, n - len(out))
                out += self._connection.read_reg(self._REG_FIFO_DATA, chunk)
        finally:
            self._w(self._REG_CTRL1, self._ctrl1)
            self._w(self._REG_FIFO_CTRL, self._fifo_ctrl & 0x7F)
        return bytes(out)

    def reset_fifo(self):
        """Reset the FIFO (CTRL9 RST_FIFO)."""
        self._ctrl9(self._CMD_RST_FIFO)

    # -- motion engines --------------------------------------------------

    def _send_motion(self):
        prev = self._sensors_off()
        try:
            a_thr, a_win, a_axes, a_and = self._any
            n_thr, n_win, n_axes, n_and = self._no
            a_code = max(0, min(255, int(round(a_thr * 32))))
            n_code = max(0, min(255, int(round(n_thr * 32))))
            mode = ((0x80 if n_and else 0) | ((n_axes & 7) << 4) |
                    (0x08 if a_and else 0) | (a_axes & 7))
            sw, cw = self._sig
            self._two_sets(
                self._CMD_CONFIG_MOTION,
                (a_code, a_code, a_code, n_code, n_code, n_code, mode),
                (a_win & 0xFF, n_win & 0xFF, sw & 0xFF, (sw >> 8) & 0xFF,
                 cw & 0xFF, (cw >> 8) & 0xFF))
        finally:
            self._sensors_restore(prev)

    def configure_any_motion(self, threshold_g=0.25, window=3, axes=0b111, logic_and=False):
        """Configure the any-motion engine.

        Args:
            threshold_g: Slope threshold in g (1/32 g steps, 0-7.97).
            window: Window in samples.
            axes: Axis mask (bit 0 X, 1 Y, 2 Z).
            logic_and: AND instead of OR over the enabled axes.
        """
        self._any = [threshold_g, window, axes, logic_and]
        self._send_motion()

    def configure_no_motion(self, threshold_g=0.125, window=30, axes=0b111, logic_and=True):
        """Configure the no-motion engine (arguments as for any-motion)."""
        self._no = [threshold_g, window, axes, logic_and]
        self._send_motion()

    def configure_significant_motion(self, wait_window=150, confirm_window=600):
        """Configure the significant-motion engine.

        Requires any- and no-motion to be configured and enabled.

        Args:
            wait_window: Wait window in samples (16-bit).
            confirm_window: Confirmation window in samples (16-bit).
        """
        self._sig = [wait_window, confirm_window]
        self._send_motion()

    def enable_motion(self, any_motion=False, no_motion=False, significant=False):
        """Enable the motion engines (CTRL8 bits 1-3).

        Args:
            any_motion: Enable any-motion.
            no_motion: Enable no-motion.
            significant: Enable significant-motion.
        """
        v = self._ctrl8 & ~0x0E
        v |= (0x02 if any_motion else 0) | (0x04 if no_motion else 0) | (0x08 if significant else 0)
        self._set_ctrl8(v)

    def configure_tap(self, priority=0, peak_window=20, tap_window=50, double_tap_window=250,
                      alpha=0.0625, gamma=0.25, peak_threshold=0.8, quiet_threshold=0.4):
        """Configure the tap engine (two CTRL9 CONFIGURE_TAP commands).

        Args:
            priority: Axis priority 0-5.
            peak_window: Peak window in samples.
            tap_window: Tap (quiet) window in samples.
            double_tap_window: Double-tap window in samples.
            alpha: Acceleration averaging weight (1/128 steps).
            gamma: Magnitude averaging weight (1/128 steps).
            peak_threshold: Peak threshold in g² (1/1024 steps).
            quiet_threshold: Undefined-motion threshold in g² (1/1024 steps).
        """
        peak = int(round(peak_threshold * 1024))
        quiet = int(round(quiet_threshold * 1024))
        prev = self._sensors_off()
        try:
            self._two_sets(
                self._CMD_CONFIG_TAP,
                (peak_window & 0xFF, priority & 0xFF, tap_window & 0xFF, (tap_window >> 8) & 0xFF,
                 double_tap_window & 0xFF, (double_tap_window >> 8) & 0xFF),
                (int(round(alpha * 128)) & 0x7F, int(round(gamma * 128)) & 0x7F,
                 peak & 0xFF, (peak >> 8) & 0xFF, quiet & 0xFF, (quiet >> 8) & 0xFF))
        finally:
            self._sensors_restore(prev)

    def enable_tap(self, enabled=True):
        """Enable the tap engine (CTRL8.Tap_EN). Use an accelerometer ODR above 200 Hz.

        Args:
            enabled: True to enable.
        """
        self._set_ctrl8((self._ctrl8 | 0x01) if enabled else (self._ctrl8 & ~0x01))

    def tap_status(self):
        """Read the last tap event.

        Returns:
            tuple[int, int, bool]: ``(count, axis, negative)`` — count 0/1/2,
            axis 0 none / 1 X / 2 Y / 3 Z.
        """
        v = self._connection.read_reg(self._REG_TAP_STATUS, 1)[0]
        return v & 0x03, (v >> 4) & 0x03, bool(v & 0x80)

    def configure_pedometer(self, sample_count=50, peak_to_peak_mg=200, peak_mg=100, time_up=200,
                            time_low=20, entry_count=10, precision=0, report_every=4):
        """Configure the pedometer (two CTRL9 CONFIGURE_PEDOMETER commands).

        Defaults are for a 50 Hz accelerometer ODR.

        Args:
            sample_count: Samples per step window.
            peak_to_peak_mg: Peak-to-peak threshold in mg.
            peak_mg: Peak threshold in mg.
            time_up: Maximum step interval in samples.
            time_low: Minimum step interval in samples.
            entry_count: Steps needed to enter counting.
            precision: Precision setting.
            report_every: Steps per pedometer event / count update.
        """
        p2p = int(round(peak_to_peak_mg / 1000.0 * 1024))
        pk = int(round(peak_mg / 1000.0 * 1024))
        prev = self._sensors_off()
        try:
            self._two_sets(
                self._CMD_CONFIG_PEDOMETER,
                (sample_count & 0xFF, (sample_count >> 8) & 0xFF, p2p & 0xFF, (p2p >> 8) & 0xFF,
                 pk & 0xFF, (pk >> 8) & 0xFF),
                (time_up & 0xFF, (time_up >> 8) & 0xFF, time_low & 0xFF, entry_count & 0xFF,
                 precision & 0xFF, report_every & 0xFF))
        finally:
            self._sensors_restore(prev)

    def enable_pedometer(self, enabled=True):
        """Enable the pedometer (CTRL8.Pedo_EN); a 0→1 transition resets the count.

        Args:
            enabled: True to enable.
        """
        self._set_ctrl8((self._ctrl8 | 0x10) if enabled else (self._ctrl8 & ~0x10))

    def step_count(self):
        """Read the 24-bit step count.

        Returns:
            int: Steps (updated every ``report_every`` steps).
        """
        raw = self._connection.read_reg(self._REG_STEP_CNT, 3)
        return raw[0] | (raw[1] << 8) | (raw[2] << 16)

    def reset_step_count(self):
        """Clear the step count (CTRL9 RESET_PEDOMETER)."""
        self._ctrl9(self._CMD_RESET_PEDOMETER)

    def set_wake_on_motion(self, threshold_mg, interrupt_pin=1, initial_level=0, blanking_samples=0):
        """Enter or leave wake-on-motion mode.

        Uses accelerometer ±2 g at ODR code 12 (128 Hz). The chosen pin must
        also be enabled (done here).

        Args:
            threshold_mg: 1-255 mg enables, 0 exits WoM mode.
            interrupt_pin: 1 or 2.
            initial_level: Initial pin level, 0 or 1.
            blanking_samples: Samples ignored after enabling (0-63).
        """
        self._sensors_off()
        if threshold_mg == 0:
            self._cal_cmd(self._CMD_WRITE_WOM, (0, 0))
            self._int_sources &= ~self.SOURCE_WAKE_ON_MOTION
            self._update_int_pins()
            return
        self._accel_fs, self._accel_odr = 0, 12
        self._update_sens()
        self._w(self._REG_CTRL2, 0x0C)
        self._wom_pin = 2 if interrupt_pin == 2 else 1
        sel = (0x40 if self._wom_pin == 2 else 0x00) | (0x80 if initial_level else 0x00)
        self._cal_cmd(self._CMD_WRITE_WOM,
                      (min(255, int(threshold_mg)), sel | (blanking_samples & 0x3F)))
        self._int_sources |= self.SOURCE_WAKE_ON_MOTION
        self._update_int_pins()
        self._set_ctrl7((self._ctrl7 & ~0x03) | 0x01)
        time.sleep(0.01)

    # -- offsets, calibration, self-test ---------------------------------

    def set_accel_offset(self, x, y, z):
        """Apply an accelerometer offset (volatile).

        Args:
            x: X offset in m/s².
            y: Y offset in m/s².
            z: Z offset in m/s².
        """
        codes = [int(round(v / _G * 4096)) & 0xFFFF for v in (x, y, z)]
        self._offset_cmd(self._CMD_ACCEL_OFFSET, codes)

    def set_gyro_offset(self, x, y, z):
        """Apply a gyroscope offset (volatile).

        Args:
            x: X offset in rad/s.
            y: Y offset in rad/s.
            z: Z offset in rad/s.
        """
        codes = [int(round(v / _DEG_TO_RAD * 32)) & 0xFFFF for v in (x, y, z)]
        self._offset_cmd(self._CMD_GYRO_OFFSET, codes)

    def _offset_cmd(self, cmd, codes):
        prev = self._sensors_off()
        try:
            params = []
            for c in codes:
                params += [c & 0xFF, c >> 8]
            self._cal_cmd(cmd, tuple(params))
        finally:
            self._sensors_restore(prev)

    def calibrate_gyro(self):
        """Run gyroscope calibration-on-demand (keep the device still, ~1.5 s).

        Returns:
            tuple[bool, int]: ``(success, cod_status)``.
        """
        prev = self._sensors_off()
        try:
            self._ctrl9(self._CMD_COD, 3.0)
            status = self._connection.read_reg(self._REG_COD_STATUS, 1)[0]
        finally:
            self._sensors_restore(prev)
        return status == 0, status

    def get_gyro_gains(self):
        """Read the raw gyroscope gains, valid right after :meth:`calibrate_gyro`.

        Returns:
            tuple[int, int, int]: Raw 16-bit X, Y, Z gains.
        """
        raw = self._connection.read_reg(self._REG_DVX_L, 6)
        return tuple(raw[2 * i] | (raw[2 * i + 1] << 8) for i in range(3))

    def set_gyro_gains(self, x, y, z):
        """Restore previously saved gyroscope gains.

        Args:
            x: Raw X gain.
            y: Raw Y gain.
            z: Raw Z gain.
        """
        prev = self._sensors_off()
        try:
            params = []
            for c in (x, y, z):
                params += [c & 0xFF, (c >> 8) & 0xFF]
            self._cal_cmd(self._CMD_APPLY_GYRO_GAINS, tuple(params))
        finally:
            self._sensors_restore(prev)

    def _self_test(self, ctrl_reg, saved, scale):
        prev = self._sensors_off()
        try:
            self._w(ctrl_reg, saved | 0x80)
            self._wait_status(0x01, True, 3.0, 'self-test Avail')
            raw = self._connection.read_reg(self._REG_DVX_L, 6)
            self._w(ctrl_reg, saved)
            self._wait_status(0x01, False, 3.0, 'self-test end')
        finally:
            self._sensors_restore(prev)
        return tuple(self._s16(raw, i) / scale for i in range(3))

    def self_test_accel(self):
        """Run the accelerometer self-test.

        Returns:
            tuple: ``(passed, (x, y, z))`` — values in m/s²; passes when every
            axis exceeds 200 mg.
        """
        g = self._self_test(self._REG_CTRL2, (self._accel_fs << 4) | self._accel_odr, 2048.0)
        return all(abs(v) > 0.2 for v in g), tuple(v * _G for v in g)

    def self_test_gyro(self):
        """Run the gyroscope self-test.

        Returns:
            tuple: ``(passed, (x, y, z))`` — values in rad/s; passes when every
            axis exceeds 300 dps.
        """
        d = self._self_test(self._REG_CTRL3, (self._gyro_fs << 4) | self._gyro_odr, 16.0)
        return all(abs(v) > 300.0 for v in d), tuple(v * _DEG_TO_RAD for v in d)

    def device_info(self):
        """Read revision, firmware version and USID (CTRL9 COPY_USID).

        Returns:
            dict: ``{'revision': int, 'firmware': bytes(3), 'usid': bytes(6)}``.
        """
        self._ctrl9(self._CMD_COPY_USID)
        revision = self._connection.read_reg(self._REG_REVISION_ID, 1)[0]
        fw = bytes(self._connection.read_reg(0x49, 2)) + bytes(self._connection.read_reg(0x4B, 1))
        usid = bytes(self._connection.read_reg(self._REG_DVX_L, 6))
        return {'revision': revision, 'firmware': fw, 'usid': usid}

    def set_pullups(self, aux=True, sdx=True, cs=True, i2c=True):
        """Control the internal 200 kΩ pull-ups (CTRL9 SET_RPU).

        ``False`` disables a group. Disabling ``i2c`` on a bus without
        external pull-ups breaks the bus.

        Args:
            aux: SDx / SCx / RESV pull-ups.
            sdx: SDx pull-up.
            cs: CS pull-up.
            i2c: SCL / SDA pull-ups.
        """
        v = ((0 if aux else 1) | (0 if sdx else 2) | (0 if cs else 4) | (0 if i2c else 8))
        self._cal_cmd(self._CMD_SET_RPU, (v,))

    def reset(self):
        """Soft reset (wait 15 ms). Re-runs the initialisation sequence defaults."""
        self._connection.write_reg(self._REG_RESET, self._RESET_CMD)
        time.sleep(0.015)
        self._ctrl1, self._ctrl7, self._ctrl8 = 0x00, 0x00, 0x00
        self._sync = False
        self._connection.write_reg(self._REG_CTRL1, 0x40)
        self._ctrl1 = 0x40
        self._connection.write_reg(self._REG_CTRL8, 0x80)
        self._ctrl8 = 0x80
        self._int_sources = 0

    # -- interrupts ------------------------------------------------------

    def _source_pin(self, source):
        if source == self.SOURCE_DATA_READY:
            return 2
        if source in (self.SOURCE_FIFO_WATERMARK, self.SOURCE_FIFO_FULL):
            return self._fifo_pin
        if source == self.SOURCE_WAKE_ON_MOTION:
            return self._wom_pin
        return self._activity_pin

    def _update_int_pins(self):
        pins = 0
        for bit in range(9):
            s = 1 << bit
            if self._int_sources & s:
                pins |= 1 << (self._source_pin(s) - 1)
        v = self._ctrl1 & ~0x18
        v |= (0x08 if pins & 1 else 0) | (0x10 if pins & 2 else 0)
        v = (v | 0x04) if self._fifo_pin == 1 else (v & ~0x04)
        if v != self._ctrl1:
            self._set_ctrl1(v)

    def set_interrupt_pins(self, fifo_pin=2, activity_pin=2):
        """Route the FIFO interrupt and the motion-engine group.

        Args:
            fifo_pin: 1 or 2 (CTRL1.FIFO_INT_SEL).
            activity_pin: 1 or 2 (CTRL8.ACTIVITY_INT_SEL).
        """
        self._fifo_pin = 1 if fifo_pin == 1 else 2
        self._activity_pin = 1 if activity_pin == 1 else 2
        self._set_ctrl8((self._ctrl8 | 0x40) if self._activity_pin == 1 else (self._ctrl8 & ~0x40))
        self._update_int_pins()

    def enable_interrupt(self, source):
        """Enable one interrupt source (``SOURCE_*`` constant).

        Engine sources set the engine bit in CTRL8 (configure the engine
        first); FIFO sources need a configured FIFO.

        Args:
            source: One of the ``SOURCE_*`` constants.
        """
        if source == self.SOURCE_DATA_READY:
            self._set_ctrl7(self._ctrl7 & ~0x20)
        elif source == self.SOURCE_FIFO_WATERMARK:
            self._w(self._REG_FIFO_WTM_TH, self._fifo_wtm)
        elif source in self._ENGINE_BIT:
            self._set_ctrl8(self._ctrl8 | self._ENGINE_BIT[source])
        self._int_sources |= source
        self._update_int_pins()

    def disable_interrupt(self, source):
        """Disable one interrupt source (``SOURCE_*`` constant).

        Args:
            source: One of the ``SOURCE_*`` constants.
        """
        if source == self.SOURCE_DATA_READY:
            self._set_ctrl7(self._ctrl7 | 0x20)
        elif source == self.SOURCE_FIFO_WATERMARK:
            self._w(self._REG_FIFO_WTM_TH, 0)
        elif source in self._ENGINE_BIT:
            self._set_ctrl8(self._ctrl8 & ~self._ENGINE_BIT[source])
        self._int_sources &= ~source
        self._update_int_pins()

    def poll_interrupt(self):
        """Read STATUS0, STATUS1 and FIFO_STATUS (clearing what clears on read).

        Returns:
            int: Combined status using the ``SOURCE_*`` bit layout.
        """
        s0, s1 = self._connection.read_reg(self._REG_STATUS0, 2)
        fs = self._connection.read_reg(self._REG_FIFO_STATUS, 1)[0]
        status = 0
        if s0 & 0x03:
            status |= self.SOURCE_DATA_READY
        if fs & 0x40:
            status |= self.SOURCE_FIFO_WATERMARK
        if fs & 0x80:
            status |= self.SOURCE_FIFO_FULL
        if s1 & 0x20:
            status |= self.SOURCE_ANY_MOTION
        if s1 & 0x40:
            status |= self.SOURCE_NO_MOTION
        if s1 & 0x80:
            status |= self.SOURCE_SIGNIFICANT_MOTION
        if s1 & 0x10:
            status |= self.SOURCE_STEP
        if s1 & 0x02:
            status |= self.SOURCE_TAP
        if s1 & 0x04:
            status |= self.SOURCE_WAKE_ON_MOTION
        return status

    def on_interrupt(self, callback):
        """Subscribe to interrupt-pin assertions.

        Uses the connection's ``int_pin`` when present, otherwise falls back
        to a polling thread.

        Args:
            callback: Function called with the status int from
                :meth:`poll_interrupt`.
        """
        self._int_callback = callback
        int_pin = getattr(self._connection, 'int_pin', None)
        if int_pin is not None and hasattr(int_pin, 'on_edge'):
            self._int_handler = lambda pin: self._dispatch_int()
            int_pin.on_edge(self._int_handler, getattr(int_pin, 'RISING', 1))
        else:
            self._start_polling_fallback()

    def off_interrupt(self):
        """Unsubscribe from interrupt-pin assertions."""
        self._int_callback = None
        int_pin = getattr(self._connection, 'int_pin', None)
        handler = self._int_handler
        if int_pin is not None and handler is not None and hasattr(int_pin, 'off_edge'):
            int_pin.off_edge(handler)
        elif self._poll_timer is not None:
            try:
                self._poll_timer.deinit()
            except Exception:
                pass
            self._poll_timer = None
        self._int_handler = None

    def _dispatch_int(self):
        cb = self._int_callback
        if cb is None:
            return
        try:
            cb(self.poll_interrupt())
        except Exception:
            pass

    def _start_polling_fallback(self):
        try:
            import _thread
        except ImportError:
            import threading

            def _run():
                while self._int_callback is not None:
                    self._dispatch_int()
                    time.sleep(0.005)
            threading.Thread(target=_run, daemon=True).start()
            return

        def _run_mp():
            while self._int_callback is not None:
                self._dispatch_int()
                time.sleep(0.005)
        _thread.start_new_thread(_run_mp, ())
