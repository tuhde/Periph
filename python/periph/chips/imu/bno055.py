"""BNO055 — 9-axis absolute-orientation sensor (Bosch Sensortec).

14-bit accelerometer, 16-bit gyroscope and magnetometer with an on-chip
sensor-fusion microcontroller that outputs Euler angles, a quaternion, linear
acceleration and the gravity vector, with automatic calibration.

Communicates over I²C at address 0x29 (COM3 = VDDIO, datasheet default) or
0x28 (COM3 = GND); the UART interface is out of scope. The chip stretches the
I²C clock, so the host bus must support clock stretching. All data registers
are little-endian and are always read as a burst so the LSB/MSB pairs are
consistent.

The driver fixes the unit selection to ``UNIT_SEL = 0x86`` (Android
orientation, Euler angles in radians, angular rate in rad/s, acceleration in
m/s², temperature in °C) and converts everything to SI-style units.
"""

import time


class BNO055Minimal:
    """BNO055 minimal interface — fused Euler angles.

    Waits for the chip to boot, checks ``CHIP_ID``, selects normal power mode
    and ``UNIT_SEL`` = 0x86, then enters NDOF fusion mode (100 Hz, absolute
    orientation, internal oscillator).

    Args:
        connection: RegisterConnection (I²C, address 0x28 or 0x29).

    Raises:
        ValueError: If ``CHIP_ID`` does not read 0xA0 within 650 ms.
    """

    _REG_CHIP_ID = 0x00
    _REG_PAGE_ID = 0x07
    _REG_ACC_DATA = 0x08
    _REG_MAG_DATA = 0x0E
    _REG_GYR_DATA = 0x14
    _REG_EUL_DATA = 0x1A
    _REG_QUA_DATA = 0x20
    _REG_LIA_DATA = 0x28
    _REG_GRV_DATA = 0x2E
    _REG_TEMP = 0x34
    _REG_CALIB_STAT = 0x35
    _REG_ST_RESULT = 0x36
    _REG_INT_STA = 0x37
    _REG_SYS_STATUS = 0x39
    _REG_UNIT_SEL = 0x3B
    _REG_OPR_MODE = 0x3D
    _REG_PWR_MODE = 0x3E
    _REG_SYS_TRIGGER = 0x3F
    _REG_TEMP_SOURCE = 0x40
    _REG_AXIS_MAP_CONFIG = 0x41
    _REG_AXIS_MAP_SIGN = 0x42
    _REG_ACC_OFFSET = 0x55
    _REG_INT_MSK = 0x0F      # page 1
    _REG_INT_EN = 0x10       # page 1
    _REG_UNIQUE_ID = 0x50    # page 1

    _CHIP_ID_VALUE = 0xA0
    _UNIT_SEL_SI = 0x86
    _MODE_CONFIG = 0x00
    _MODE_NDOF = 0x0C

    def __init__(self, connection):
        self._connection = connection
        self._mode = self._MODE_NDOF
        self._initialize()

    def _initialize(self):
        """Wait for boot, check the chip ID and enter NDOF."""
        deadline = time.time() + 0.65
        chip_id = -1
        while True:
            try:
                chip_id = self._connection.read_reg(self._REG_CHIP_ID, 1)[0]
                if chip_id == self._CHIP_ID_VALUE:
                    break
            except OSError:
                pass                                   # the chip does not ACK while booting
            if time.time() >= deadline:
                raise ValueError('BNO055 CHIP_ID: expected 0xA0, got 0x{:02X}'.format(chip_id & 0xFF))
            time.sleep(0.01)
        self._connection.write_reg(self._REG_PWR_MODE, 0x00)
        self._connection.write_reg(self._REG_PAGE_ID, 0x00)
        self._connection.write_reg(self._REG_UNIT_SEL, self._UNIT_SEL_SI)
        self._connection.write_reg(self._REG_OPR_MODE, self._MODE_NDOF)
        self._mode = self._MODE_NDOF
        time.sleep(0.007)

    @staticmethod
    def _s16(raw, offset):
        v = raw[offset] | (raw[offset + 1] << 8)
        return v - 0x10000 if v & 0x8000 else v

    def _vector(self, reg, scale):
        raw = self._connection.read_reg(reg, 6)
        return (self._s16(raw, 0) / scale, self._s16(raw, 2) / scale, self._s16(raw, 4) / scale)

    def euler(self):
        """Read the fused Euler angles.

        Returns:
            tuple: ``(heading, roll, pitch)`` in radians (raw / 900).
        """
        return self._vector(self._REG_EUL_DATA, 900.0)


class BNO055Full(BNO055Minimal):
    """BNO055 full interface — all outputs, calibration, modes and interrupts.

    Args:
        connection: RegisterConnection (I²C, address 0x28 or 0x29).

    Raises:
        ValueError: If ``CHIP_ID`` does not read 0xA0 within 650 ms.
    """

    MODE_CONFIG = 0x0
    MODE_ACCONLY = 0x1
    MODE_MAGONLY = 0x2
    MODE_GYROONLY = 0x3
    MODE_ACCMAG = 0x4
    MODE_ACCGYRO = 0x5
    MODE_MAGGYRO = 0x6
    MODE_AMG = 0x7
    MODE_IMU = 0x8
    MODE_COMPASS = 0x9
    MODE_M4G = 0xA
    MODE_NDOF_FMC_OFF = 0xB
    MODE_NDOF = 0xC

    SOURCE_ACC_BSX_DRDY = 0x01
    SOURCE_MAG_DRDY = 0x02
    SOURCE_GYRO_AM = 0x04
    SOURCE_GYR_HIGH_RATE = 0x08
    SOURCE_GYR_DRDY = 0x10
    SOURCE_ACC_HIGH_G = 0x20
    SOURCE_ACC_AM = 0x40
    SOURCE_ACC_NM = 0x80

    def __init__(self, connection):
        self._int_callback = None
        self._int_handler = None
        self._int_pin_used = None
        self._clk_sel = 0x00
        super().__init__(connection)

    # -- helpers ---------------------------------------------------------

    def _enter_config(self):
        """Switch to CONFIGMODE (19 ms) and return the mode to restore."""
        prev = self._mode
        self._connection.write_reg(self._REG_OPR_MODE, self._MODE_CONFIG)
        time.sleep(0.019)
        return prev

    def _leave_config(self, mode):
        """Restore ``mode`` after :meth:`_enter_config` (7 ms)."""
        if mode != self._MODE_CONFIG:
            self._connection.write_reg(self._REG_OPR_MODE, mode)
            time.sleep(0.007)

    # -- operation mode --------------------------------------------------

    def set_mode(self, mode):
        """Select the operation mode (goes through CONFIGMODE).

        Args:
            mode: 0 (CONFIGMODE) … 12 (NDOF); see the ``MODE_*`` constants.
        """
        if mode < 0 or mode > 12:
            raise ValueError('mode must be 0..12')
        self._connection.write_reg(self._REG_OPR_MODE, self._MODE_CONFIG)
        time.sleep(0.019)
        if mode != self._MODE_CONFIG:
            self._connection.write_reg(self._REG_OPR_MODE, mode)
            time.sleep(0.007)
        self._mode = mode

    def get_mode(self):
        """Read ``OPR_MODE``.

        Returns:
            int: Current operation mode, 0–12.
        """
        return self._connection.read_reg(self._REG_OPR_MODE, 1)[0] & 0x0F

    # -- measurements ----------------------------------------------------

    def quaternion(self):
        """Read the fused orientation quaternion.

        Returns:
            tuple: ``(w, x, y, z)`` unit-quaternion components (raw / 16384).
        """
        raw = self._connection.read_reg(self._REG_QUA_DATA, 8)
        return (self._s16(raw, 0) / 16384.0, self._s16(raw, 2) / 16384.0,
                self._s16(raw, 4) / 16384.0, self._s16(raw, 6) / 16384.0)

    def linear_acceleration(self):
        """Read the gravity-free linear acceleration.

        Returns:
            tuple: ``(x, y, z)`` in m/s² (raw / 100).
        """
        return self._vector(self._REG_LIA_DATA, 100.0)

    def gravity(self):
        """Read the gravity vector.

        Returns:
            tuple: ``(x, y, z)`` in m/s² (raw / 100).
        """
        return self._vector(self._REG_GRV_DATA, 100.0)

    def acceleration(self):
        """Read the accelerometer (raw sensor data).

        Returns:
            tuple: ``(x, y, z)`` in m/s² (raw / 100).
        """
        return self._vector(self._REG_ACC_DATA, 100.0)

    def magnetic_field(self):
        """Read the magnetometer (raw sensor data).

        Returns:
            tuple: ``(x, y, z)`` in µT (raw / 16).
        """
        return self._vector(self._REG_MAG_DATA, 16.0)

    def angular_rate(self):
        """Read the gyroscope (raw sensor data).

        Returns:
            tuple: ``(x, y, z)`` in rad/s (raw / 900).
        """
        return self._vector(self._REG_GYR_DATA, 900.0)

    def temperature(self):
        """Read the temperature.

        Returns:
            int: Temperature in °C (signed 8 bit, 1 LSB = 1 °C).
        """
        v = self._connection.read_reg(self._REG_TEMP, 1)[0]
        return v - 256 if v & 0x80 else v

    # -- calibration -----------------------------------------------------

    def calibration_status(self):
        """Read ``CALIB_STAT``.

        Returns:
            tuple: ``(sys, gyr, acc, mag)``, each 0 (uncalibrated) … 3 (calibrated).
        """
        v = self._connection.read_reg(self._REG_CALIB_STAT, 1)[0]
        return ((v >> 6) & 3, (v >> 4) & 3, (v >> 2) & 3, v & 3)

    def is_calibrated(self):
        """Check whether sys, gyr, acc and mag are all fully calibrated.

        Returns:
            bool: True if every status value is 3.
        """
        return self._connection.read_reg(self._REG_CALIB_STAT, 1)[0] == 0xFF

    def get_calibration(self):
        """Read the calibration offsets and radii (CONFIGMODE, mode restored).

        Returns:
            bytes: 22 bytes from ``ACC_OFFSET_X_LSB`` (0x55) to ``MAG_RADIUS_MSB`` (0x6A).
        """
        prev = self._enter_config()
        data = bytes(self._connection.read_reg(self._REG_ACC_OFFSET, 22))
        self._leave_config(prev)
        return data

    def set_calibration(self, data):
        """Write the calibration offsets and radii (CONFIGMODE, mode restored).

        Args:
            data: 22 bytes as returned by :meth:`get_calibration`.
        """
        if len(data) != 22:
            raise ValueError('calibration data must be 22 bytes')
        prev = self._enter_config()
        for i in range(22):
            self._connection.write_reg(self._REG_ACC_OFFSET + i, data[i])
        self._leave_config(prev)

    # -- configuration ---------------------------------------------------

    def set_axis_remap(self, config, sign):
        """Remap the sensor axes (CONFIGMODE, mode restored).

        Args:
            config: ``AXIS_MAP_CONFIG`` value (bits 1:0 X, 3:2 Y, 5:4 Z source axis; default 0x24).
            sign: ``AXIS_MAP_SIGN`` value (bit 2 X, bit 1 Y, bit 0 Z inverted; default 0x00).
        """
        prev = self._enter_config()
        self._connection.write_reg(self._REG_AXIS_MAP_CONFIG, config)
        self._connection.write_reg(self._REG_AXIS_MAP_SIGN, sign)
        self._leave_config(prev)

    def set_power_mode(self, mode):
        """Select the power mode (CONFIGMODE, mode restored).

        Args:
            mode: ``'normal'``, ``'low_power'`` or ``'suspend'``.
        """
        codes = {'normal': 0, 'low_power': 1, 'suspend': 2}
        if mode not in codes:
            raise ValueError("mode must be 'normal', 'low_power' or 'suspend'")
        prev = self._enter_config()
        self._connection.write_reg(self._REG_PWR_MODE, codes[mode])
        self._leave_config(prev)

    def set_temperature_source(self, source):
        """Select the temperature sensor (CONFIGMODE, mode restored).

        Args:
            source: ``'accelerometer'`` or ``'gyroscope'``.
        """
        codes = {'accelerometer': 0, 'gyroscope': 1}
        if source not in codes:
            raise ValueError("source must be 'accelerometer' or 'gyroscope'")
        prev = self._enter_config()
        self._connection.write_reg(self._REG_TEMP_SOURCE, codes[source])
        self._leave_config(prev)

    def use_external_crystal(self, on):
        """Select the external 32.768 kHz crystal (CONFIGMODE, mode restored).

        Only use this if a crystal is fitted to XIN32/XOUT32.

        Args:
            on: True for the external crystal, False for the internal oscillator.
        """
        prev = self._enter_config()
        self._clk_sel = 0x80 if on else 0x00
        self._connection.write_reg(self._REG_SYS_TRIGGER, self._clk_sel)
        self._leave_config(prev)

    # -- diagnostics -----------------------------------------------------

    def self_test(self):
        """Run the power-on self-test (CONFIGMODE, mode restored).

        Returns:
            dict: ``{'acc': bool, 'mag': bool, 'gyr': bool, 'mcu': bool}``, True = passed.
        """
        prev = self._enter_config()
        self._connection.write_reg(self._REG_SYS_TRIGGER, self._clk_sel | 0x01)
        time.sleep(0.5)
        v = self._connection.read_reg(self._REG_ST_RESULT, 1)[0]
        self._leave_config(prev)
        return {'acc': bool(v & 0x01), 'mag': bool(v & 0x02),
                'gyr': bool(v & 0x04), 'mcu': bool(v & 0x08)}

    def system_status(self):
        """Read ``SYS_STATUS`` and ``SYS_ERR``.

        Returns:
            tuple: ``(status, error)`` — status 0–6 (5 = fusion running), error 0–10.
        """
        raw = self._connection.read_reg(self._REG_SYS_STATUS, 2)
        return (raw[0], raw[1])

    def reset(self):
        """Reset the chip (``RST_SYS``), wait 650 ms and re-initialise into NDOF."""
        self._connection.write_reg(self._REG_SYS_TRIGGER, self._clk_sel | 0x20)
        time.sleep(0.65)
        self._clk_sel = 0x00
        self._initialize()

    def revisions(self):
        """Read the identification and revision registers.

        Returns:
            dict: ``chip_id``, ``acc_id``, ``mag_id``, ``gyr_id``, ``sw_revision`` (16 bit), ``bootloader``.
        """
        raw = self._connection.read_reg(self._REG_CHIP_ID, 7)
        return {'chip_id': raw[0], 'acc_id': raw[1], 'mag_id': raw[2], 'gyr_id': raw[3],
                'sw_revision': raw[4] | (raw[5] << 8), 'bootloader': raw[6]}

    def unique_id(self):
        """Read the 16-byte unique ID (page 1, page 0 restored).

        Returns:
            bytes: 16 bytes from ``UNIQUE_ID`` (0x50).
        """
        self._connection.write_reg(self._REG_PAGE_ID, 0x01)
        try:
            data = bytes(self._connection.read_reg(self._REG_UNIQUE_ID, 16))
        finally:
            self._connection.write_reg(self._REG_PAGE_ID, 0x00)
        return data

    # -- interrupts ------------------------------------------------------

    def _update_int_reg(self, source, enable):
        self._connection.write_reg(self._REG_PAGE_ID, 0x01)
        try:
            for reg in (self._REG_INT_EN, self._REG_INT_MSK):
                v = self._connection.read_reg(reg, 1)[0]
                v = (v | source) if enable else (v & ~source & 0xFF)
                self._connection.write_reg(reg, v)
        finally:
            self._connection.write_reg(self._REG_PAGE_ID, 0x00)

    def enable_interrupt(self, source):
        """Enable an interrupt source in ``INT_EN`` and ``INT_MSK`` (page 1).

        Args:
            source: One of the ``SOURCE_*`` constants (bits may be OR-ed).
        """
        self._update_int_reg(source, True)

    def disable_interrupt(self, source):
        """Disable an interrupt source in ``INT_EN`` and ``INT_MSK`` (page 1).

        Args:
            source: One of the ``SOURCE_*`` constants (bits may be OR-ed).
        """
        self._update_int_reg(source, False)

    def poll_interrupt(self):
        """Read ``INT_STA`` (clears it).

        Returns:
            int: Status bits using the ``SOURCE_*`` layout.
        """
        return self._connection.read_reg(self._REG_INT_STA, 1)[0]

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to INT assertions (rising edge).

        Uses ``int_pin`` or the connection's ``int_pin``; without either, a
        100 ms polling task calls :meth:`poll_interrupt`.

        Args:
            callback: Function called with the ``INT_STA`` bits.
            int_pin: Optional input pin overriding ``connection.int_pin``.
        """
        self._int_callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        if pin is not None and hasattr(pin, 'on_edge'):
            self._int_pin_used = pin
            self._int_handler = lambda p: self._dispatch_int()
            pin.on_edge(self._int_handler, getattr(pin, 'RISING', 1))
        else:
            self._start_polling_fallback()

    def off_interrupt(self):
        """Unsubscribe from INT assertions."""
        self._int_callback = None
        pin = self._int_pin_used
        if pin is not None and self._int_handler is not None and hasattr(pin, 'off_edge'):
            pin.off_edge(self._int_handler)
        self._int_pin_used = None
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

            def _run_mp():
                while self._int_callback is not None:
                    self._dispatch_int()
                    time.sleep(0.1)
            _thread.start_new_thread(_run_mp, ())
        except ImportError:
            import threading

            def _run():
                while self._int_callback is not None:
                    self._dispatch_int()
                    time.sleep(0.1)
            threading.Thread(target=_run, daemon=True).start()
