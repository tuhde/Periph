"""HTS221 capacitive digital relative-humidity and temperature sensor (I²C).

The chip is factory calibrated: every device stores its own two-point
calibration coefficients in registers 0x30–0x3F. The driver reads them once
and converts the raw 16-bit outputs by linear interpolation.

Multi-byte transfers need the sub-address MSb set (register address
auto-increment), which the driver ORs into the register address itself.
"""

import math

from periph.connection.register import to_signed


def _delay_ms(ms):
    """Sleep for `ms` milliseconds on every supported Python target.

    Args:
        ms: Number of milliseconds to sleep; 0 or less returns immediately.
    """
    if ms <= 0:
        return
    import time
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(ms)
    else:
        time.sleep(ms / 1000.0)


class HTS221Minimal:
    """HTS221 relative humidity and temperature sensor — minimal interface.

    Reads the factory calibration at construction and starts the chip in
    one-shot mode: active, block data update on, averaging at the power-on
    values (16 temperature / 32 humidity samples), heater and DRDY off.
    The I²C address is fixed at 0x5F.

    Args:
        connection: ``RegisterConnection`` (I²C) bound to address 0x5F.
    """

    _REG_WHO_AM_I   = 0x0F
    _REG_AV_CONF    = 0x10
    _REG_CTRL_REG1  = 0x20
    _REG_CTRL_REG2  = 0x21
    _REG_CTRL_REG3  = 0x22
    _REG_STATUS     = 0x27
    _REG_HUMIDITY_OUT = 0x28
    _REG_CALIB      = 0x30

    _AUTO_INC       = 0x80

    _CTRL_REG1_DEFAULT = 0x84   # PD=1, BDU=1, ODR=00 (one-shot)
    _CTRL1_PD       = 0x80
    _CTRL1_BDU      = 0x04
    _CTRL1_ODR_MASK = 0x03
    _CTRL2_BOOT     = 0x80
    _CTRL2_HEATER   = 0x02
    _CTRL2_ONE_SHOT = 0x01
    _STATUS_BOTH    = 0x03

    _TIMEOUT_MS     = 100

    def __init__(self, connection):
        self._connection = connection
        self._cal = None
        self._read_calibration()
        self._write_reg(self._REG_CTRL_REG1, self._CTRL_REG1_DEFAULT)

    def _write_reg(self, reg, value):
        """Write one byte to a register."""
        self._connection.write_reg(reg, value & 0xFF)

    def _read_reg(self, reg):
        """Read one byte from a register."""
        return self._connection.read_reg(reg, 1)[0]

    def _read_burst(self, reg, length):
        """Read `length` consecutive bytes (sub-address MSb set)."""
        return self._connection.read_reg(reg | self._AUTO_INC, length)

    def _read_calibration(self):
        """Read and decode the calibration block 0x30–0x3F."""
        c = self._read_burst(self._REG_CALIB, 16)
        t0 = ((c[5] & 0x03) << 8) | c[2]
        t1 = ((c[5] & 0x0C) << 6) | c[3]
        self._cal = {
            'H0_rH': c[0] / 2.0,
            'H1_rH': c[1] / 2.0,
            'T0_degC': t0 / 8.0,
            'T1_degC': t1 / 8.0,
            'H0_T0_OUT': to_signed((c[7] << 8) | c[6], 16),
            'H1_T0_OUT': to_signed((c[11] << 8) | c[10], 16),
            'T0_OUT': to_signed((c[13] << 8) | c[12], 16),
            'T1_OUT': to_signed((c[15] << 8) | c[14], 16),
        }

    def _humidity_from_raw(self, raw):
        """Convert a raw humidity output to %rH (clamped 0–100)."""
        c = self._cal
        span = c['H1_T0_OUT'] - c['H0_T0_OUT']
        if span == 0:
            return 0.0
        value = (raw - c['H0_T0_OUT']) * (c['H1_rH'] - c['H0_rH']) / span + c['H0_rH']
        return max(0.0, min(100.0, value))

    def _temperature_from_raw(self, raw):
        """Convert a raw temperature output to °C."""
        c = self._cal
        span = c['T1_OUT'] - c['T0_OUT']
        if span == 0:
            return 0.0
        return (raw - c['T0_OUT']) * (c['T1_degC'] - c['T0_degC']) / span + c['T0_degC']

    def _read_raw_pair(self):
        """Burst-read the output registers.

        Returns:
            tuple: (raw_humidity, raw_temperature) as signed 16-bit ints.
        """
        d = self._read_burst(self._REG_HUMIDITY_OUT, 4)
        return (to_signed((d[1] << 8) | d[0], 16),
                to_signed((d[3] << 8) | d[2], 16))

    def _convert_one_shot(self):
        """Start a one-shot conversion and wait until both flags are set.

        Raises:
            OSError: If the conversion does not finish within 100 ms.
        """
        ctrl2 = self._read_reg(self._REG_CTRL_REG2)
        self._write_reg(self._REG_CTRL_REG2, ctrl2 | self._CTRL2_ONE_SHOT)
        for _ in range(self._TIMEOUT_MS):
            status = self._read_reg(self._REG_STATUS)
            if (status & self._STATUS_BOTH) == self._STATUS_BOTH:
                return
            _delay_ms(1)
        raise OSError('HTS221 conversion timeout (STATUS=0x{:02X})'.format(status))

    def _one_shot_raw(self):
        """Run a one-shot conversion and return the raw outputs."""
        self._convert_one_shot()
        return self._read_raw_pair()

    def read(self):
        """Measure temperature and relative humidity.

        Starts a one-shot conversion, waits for it (timeout 100 ms), reads
        the four output bytes in one burst and converts them with the
        calibration. Humidity is clamped to 0–100 %rH.

        Returns:
            dict: ``{'temperature_c': float °C, 'humidity_pct': float %rH}``.

        Raises:
            OSError: If the conversion times out.
        """
        raw_h, raw_t = self._one_shot_raw()
        return {
            'temperature_c': self._temperature_from_raw(raw_t),
            'humidity_pct': self._humidity_from_raw(raw_h),
        }


class HTS221Full(HTS221Minimal):
    """HTS221 full interface — extends :class:`HTS221Minimal` with output data
    rate, averaging, power modes, heater, DRDY pin, calibration access, dew
    point and data-ready interrupt support.

    Args:
        connection: ``RegisterConnection`` (I²C) bound to address 0x5F.
    """

    WHO_AM_I_VALUE = 0xBC

    STATUS_T_DA = 0x01
    STATUS_H_DA = 0x02

    _ODR_CODES = {0: 0, 1: 1, 7: 2, 12.5: 3}
    _ODR_HZ = (0.0, 1.0, 7.0, 12.5)

    def __init__(self, connection):
        super().__init__(connection)
        self._int_callback = None
        self._int_pin = None
        self._int_handler = None
        self._poll_stop = None

    # --- conversions -------------------------------------------------------

    def read_humidity(self):
        """Measure relative humidity only (one-shot).

        Returns:
            float: Relative humidity in %rH, clamped to 0–100.
        """
        raw_h, _ = self._one_shot_raw()
        return self._humidity_from_raw(raw_h)

    def read_temperature(self):
        """Measure temperature only (one-shot).

        Returns:
            float: Temperature in °C.
        """
        _, raw_t = self._one_shot_raw()
        return self._temperature_from_raw(raw_t)

    def read_raw_humidity(self):
        """Measure and return the raw humidity ADC output (one-shot).

        Returns:
            int: Signed 16-bit raw value.
        """
        return self._one_shot_raw()[0]

    def read_raw_temperature(self):
        """Measure and return the raw temperature ADC output (one-shot).

        Returns:
            int: Signed 16-bit raw value.
        """
        return self._one_shot_raw()[1]

    def read_continuous(self):
        """Read the latest sample without starting a conversion.

        Use with a continuous output data rate (see
        :meth:`set_output_data_rate`).

        Returns:
            dict: ``{'temperature_c': float °C, 'humidity_pct': float %rH}``.
        """
        raw_h, raw_t = self._read_raw_pair()
        return {
            'temperature_c': self._temperature_from_raw(raw_t),
            'humidity_pct': self._humidity_from_raw(raw_h),
        }

    def dew_point(self):
        """Measure and compute the dew point (Magnus formula, over water).

        Returns:
            float: Dew point in °C from one measurement.
        """
        m = self.read()
        t = m['temperature_c']
        h = max(m['humidity_pct'], 0.01)
        g = math.log(h / 100.0) + 17.62 * t / (243.12 + t)
        return 243.12 * g / (17.62 - g)

    # --- configuration -----------------------------------------------------

    def set_output_data_rate(self, hz):
        """Set the output data rate.

        Args:
            hz: 0 (one-shot), 1, 7 or 12.5 Hz.

        Raises:
            ValueError: For any other value.
        """
        if hz not in self._ODR_CODES:
            raise ValueError('output data rate must be 0, 1, 7 or 12.5 Hz')
        ctrl1 = self._read_reg(self._REG_CTRL_REG1)
        self._write_reg(self._REG_CTRL_REG1,
                        (ctrl1 & ~self._CTRL1_ODR_MASK) | self._ODR_CODES[hz])
        # Clear flags left over from continuous sampling so the next
        # one-shot wait does not return early.
        self._read_raw_pair()

    def get_output_data_rate(self):
        """Read the output data rate.

        Returns:
            float: 0.0 (one-shot), 1.0, 7.0 or 12.5 Hz.
        """
        return self._ODR_HZ[self._read_reg(self._REG_CTRL_REG1) & self._CTRL1_ODR_MASK]

    def set_averaging(self, temperature_samples, humidity_samples):
        """Set the internal averaging (AV_CONF).

        Args:
            temperature_samples: 2, 4, 8, 16, 32, 64, 128 or 256.
            humidity_samples: 4, 8, 16, 32, 64, 128, 256 or 512.

        Raises:
            ValueError: For any other value.
        """
        temps = (2, 4, 8, 16, 32, 64, 128, 256)
        hums = (4, 8, 16, 32, 64, 128, 256, 512)
        if temperature_samples not in temps:
            raise ValueError('temperature_samples must be a power of two, 2..256')
        if humidity_samples not in hums:
            raise ValueError('humidity_samples must be a power of two, 4..512')
        self._write_reg(self._REG_AV_CONF,
                        (temps.index(temperature_samples) << 3) | hums.index(humidity_samples))

    def get_averaging(self):
        """Read the internal averaging.

        Returns:
            tuple: ``(temperature_samples, humidity_samples)``.
        """
        v = self._read_reg(self._REG_AV_CONF)
        return (2 << ((v >> 3) & 0x07), 4 << (v & 0x07))

    def set_block_data_update(self, enabled):
        """Enable or disable block data update (CTRL_REG1.BDU).

        Args:
            enabled: True keeps the output registers stable between reading
                the LSB and MSB.
        """
        ctrl1 = self._read_reg(self._REG_CTRL_REG1)
        if enabled:
            ctrl1 |= self._CTRL1_BDU
        else:
            ctrl1 &= ~self._CTRL1_BDU
        self._write_reg(self._REG_CTRL_REG1, ctrl1)

    def power_down(self):
        """Enter power-down mode (CTRL_REG1.PD = 0)."""
        self._write_reg(self._REG_CTRL_REG1,
                        self._read_reg(self._REG_CTRL_REG1) & ~self._CTRL1_PD)

    def power_up(self):
        """Leave power-down mode (CTRL_REG1.PD = 1)."""
        self._write_reg(self._REG_CTRL_REG1,
                        self._read_reg(self._REG_CTRL_REG1) | self._CTRL1_PD)

    def set_heater(self, on):
        """Switch the internal heater on or off (CTRL_REG2.HEATER).

        Do not measure while the heater is on.

        Args:
            on: True to heat.
        """
        ctrl2 = self._read_reg(self._REG_CTRL_REG2)
        if on:
            ctrl2 |= self._CTRL2_HEATER
        else:
            ctrl2 &= ~self._CTRL2_HEATER
        self._write_reg(self._REG_CTRL_REG2, ctrl2)

    def is_heater_on(self):
        """Read the heater state.

        Returns:
            bool: True when the heater is on.
        """
        return bool(self._read_reg(self._REG_CTRL_REG2) & self._CTRL2_HEATER)

    def reboot(self):
        """Reload the trimming registers (CTRL_REG2.BOOT) and re-read the
        calibration.

        Raises:
            OSError: If BOOT does not clear within 100 ms.
        """
        ctrl2 = self._read_reg(self._REG_CTRL_REG2)
        self._write_reg(self._REG_CTRL_REG2, ctrl2 | self._CTRL2_BOOT)
        for _ in range(self._TIMEOUT_MS):
            if not (self._read_reg(self._REG_CTRL_REG2) & self._CTRL2_BOOT):
                self._read_calibration()
                return
            _delay_ms(1)
        raise OSError('HTS221 reboot timeout')

    # --- status and identity -----------------------------------------------

    def who_am_i(self):
        """Read the WHO_AM_I register.

        Returns:
            int: Device ID (0xBC for the HTS221).
        """
        return self._read_reg(self._REG_WHO_AM_I)

    def status(self):
        """Read STATUS_REG.

        Returns:
            int: Raw status (H_DA = 0x02, T_DA = 0x01).
        """
        return self._read_reg(self._REG_STATUS)

    def data_ready(self):
        """Check whether new humidity and temperature data are both available.

        Returns:
            bool: True when H_DA and T_DA are both set.
        """
        return (self.status() & self._STATUS_BOTH) == self._STATUS_BOTH

    def read_calibration(self):
        """Return the decoded calibration coefficients.

        Returns:
            dict: ``H0_rH``, ``H1_rH``, ``T0_degC``, ``T1_degC``,
            ``H0_T0_OUT``, ``H1_T0_OUT``, ``T0_OUT``, ``T1_OUT``.
        """
        return dict(self._cal)

    def configure_drdy(self, enabled, active_high=True, open_drain=False):
        """Configure the DRDY output pin (CTRL_REG3).

        Args:
            enabled: True routes the data-ready signal to the DRDY pin.
            active_high: False selects active-low polarity.
            open_drain: True selects an open-drain output (default push-pull).
        """
        value = 0
        if not active_high:
            value |= 0x80
        if open_drain:
            value |= 0x40
        if enabled:
            value |= 0x04
        self._write_reg(self._REG_CTRL_REG3, value)

    # --- interrupt ---------------------------------------------------------

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to data-ready (DRDY) events.

        Enables DRDY (active high, push-pull). Uses ``int_pin`` or the
        connection's ``int_pin`` (rising edge); when neither is available a
        polling thread calls :meth:`poll_interrupt` at the output data rate.

        Args:
            callback: Function called with the STATUS_REG value.
            int_pin: Optional ``InputPin`` wired to DRDY.
        """
        self.off_interrupt()
        self.configure_drdy(True)
        self._int_callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        if pin is not None and hasattr(pin, 'on_edge'):
            self._int_pin = pin
            self._int_handler = lambda *a: self._dispatch_int()
            pin.on_edge(self._int_handler, getattr(pin, 'RISING', 1))
        else:
            self._start_polling_fallback()

    def off_interrupt(self):
        """Unsubscribe from data-ready events and disable the DRDY output."""
        had = (self._int_callback is not None or self._poll_stop is not None)
        self._int_callback = None
        if self._int_pin is not None and self._int_handler is not None \
                and hasattr(self._int_pin, 'off_edge'):
            self._int_pin.off_edge(self._int_handler)
        self._int_pin = None
        self._int_handler = None
        if self._poll_stop is not None:
            self._poll_stop[0] = True
            self._poll_stop = None
        if had:
            self.configure_drdy(False)

    def poll_interrupt(self):
        """Read STATUS_REG.

        The flags clear when the output registers are read.

        Returns:
            int: Raw status (H_DA = 0x02, T_DA = 0x01).
        """
        return self.status()

    def _dispatch_int(self):
        """Read the status and hand it to the callback."""
        callback = self._int_callback
        if callback is None:
            return
        try:
            callback(self.poll_interrupt())
        except Exception:
            pass

    def _start_polling_fallback(self):
        """Poll STATUS_REG at the output data rate when no DRDY pin is wired."""
        try:
            import _thread
        except ImportError:
            return
        stop = [False]
        self._poll_stop = stop

        def _loop():
            while not stop[0]:
                if self._int_callback is not None and self.data_ready():
                    self._dispatch_int()
                hz = self.get_output_data_rate()
                _delay_ms(int(1000 / hz) if hz > 0 else 100)

        _thread.start_new_thread(_loop, ())
