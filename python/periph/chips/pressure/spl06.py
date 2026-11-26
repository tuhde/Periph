import time

from periph.connection.register import to_signed


def _now():
    """Monotonic time in seconds (millisecond resolution on MicroPython)."""
    if hasattr(time, 'ticks_ms'):
        return time.ticks_ms() / 1000.0
    return time.time()


class SPL06Minimal:
    """SPL06-007 I²C barometric pressure and temperature sensor (Goertek) — minimal interface.

    One call returns compensated temperature and pressure. The chip stays in
    standby between readings; each :meth:`read` runs one temperature and one
    pressure measurement in command mode.

    Default configuration (written by :meth:`init`):
        - TMP_CFG = 0x80: external (MEMS) temperature sensor, 1× oversampling
        - PRS_CFG = 0x04: 16× oversampling (standard precision, 0.5 Pa RMS)
        - CFG_REG = 0x04: P_SHIFT (required above 8× oversampling)
        - command mode, FIFO off, interrupts off

    I²C address 0x77 (SDO high / open, default) or 0x76 (SDO low).

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    _REG_PRS_B2   = 0x00
    _REG_TMP_B2   = 0x03
    _REG_PRS_CFG  = 0x06
    _REG_TMP_CFG  = 0x07
    _REG_MEAS_CFG = 0x08
    _REG_CFG_REG  = 0x09
    _REG_INT_STS  = 0x0A
    _REG_FIFO_STS = 0x0B
    _REG_RESET    = 0x0C
    _REG_ID       = 0x0D
    _REG_COEF     = 0x10

    _CHIP_ID      = 0x10

    _MEAS_COEF_RDY   = 0x80
    _MEAS_SENSOR_RDY = 0x40
    _MEAS_TMP_RDY    = 0x20
    _MEAS_PRS_RDY    = 0x10

    _CMD_IDLE        = 0x00
    _CMD_PRESSURE    = 0x01
    _CMD_TEMPERATURE = 0x02

    _CFG_T_SHIFT  = 0x08
    _CFG_P_SHIFT  = 0x04
    _TMP_EXT      = 0x80

    # Scale factors kP / kT indexed by log2(oversampling) (datasheet Table 4)
    _SCALE = (524288, 1572864, 3670016, 7864320, 253952, 516096, 1040384, 2088960)
    # Typical measurement time in seconds indexed by log2(oversampling)
    _MEAS_TIME = (0.0036, 0.0052, 0.0084, 0.0148, 0.0276, 0.0532, 0.1044, 0.2068)

    def __init__(self, connection):
        self._connection = connection
        self._coef = None
        self._p_os = 4      # log2 of the pressure oversampling (16×)
        self._t_os = 0      # log2 of the temperature oversampling (1×)
        self._cfg = 0x04
        self._last_traw = 0

    def init(self):
        """Verify the chip, read the calibration coefficients and apply the defaults.

        Raises:
            OSError: If the ID register is not 0x10 or the chip does not become ready.
        """
        chip_id = self._connection.read_reg(self._REG_ID, 1)[0]
        if chip_id != self._CHIP_ID:
            raise OSError('Unexpected chip ID 0x{:02X}, expected 0x{:02X}'.format(
                chip_id, self._CHIP_ID))
        self._wait_ready()
        self._read_coefficients()
        self._apply_defaults()

    def _wait_ready(self):
        """Wait for SENSOR_RDY and COEF_RDY (≤ 40 ms after power-up or reset)."""
        mask = self._MEAS_COEF_RDY | self._MEAS_SENSOR_RDY
        deadline = _now() + 0.2
        while True:
            try:
                if self._connection.read_reg(self._REG_MEAS_CFG, 1)[0] & mask == mask:
                    return
            except OSError:
                pass  # the chip may not acknowledge right after a reset
            if _now() > deadline:
                raise OSError('SPL06 not ready')
            time.sleep(0.005)

    def _read_coefficients(self):
        b = self._connection.read_reg(self._REG_COEF, 18)
        self._coef = {
            'c0': to_signed((b[0] << 4) | (b[1] >> 4), 12),
            'c1': to_signed(((b[1] & 0x0F) << 8) | b[2], 12),
            'c00': to_signed((b[3] << 12) | (b[4] << 4) | (b[5] >> 4), 20),
            'c10': to_signed(((b[5] & 0x0F) << 16) | (b[6] << 8) | b[7], 20),
            'c01': to_signed((b[8] << 8) | b[9], 16),
            'c11': to_signed((b[10] << 8) | b[11], 16),
            'c20': to_signed((b[12] << 8) | b[13], 16),
            'c21': to_signed((b[14] << 8) | b[15], 16),
            'c30': to_signed((b[16] << 8) | b[17], 16),
        }

    def _apply_defaults(self):
        self._p_os = 4
        self._t_os = 0
        self._cfg = self._CFG_P_SHIFT
        self._connection.write_reg(self._REG_TMP_CFG, self._TMP_EXT)
        self._connection.write_reg(self._REG_PRS_CFG, 0x04)
        self._connection.write_reg(self._REG_CFG_REG, self._cfg)

    def _measure(self, command, flag, os_log2):
        """Start one command-mode measurement, wait for its ready flag, return the raw value."""
        self._connection.write_reg(self._REG_MEAS_CFG, command)
        deadline = _now() + self._MEAS_TIME[os_log2] * 2 + 0.05
        time.sleep(self._MEAS_TIME[os_log2])
        while not (self._connection.read_reg(self._REG_MEAS_CFG, 1)[0] & flag):
            if _now() > deadline:
                raise OSError('SPL06 measurement timeout')
            time.sleep(0.002)
        reg = self._REG_PRS_B2 if command == self._CMD_PRESSURE else self._REG_TMP_B2
        return self._raw24(self._connection.read_reg(reg, 3))

    @staticmethod
    def _raw24(b):
        return to_signed((b[0] << 16) | (b[1] << 8) | b[2], 24)

    def _measure_raw(self):
        traw = self._measure(self._CMD_TEMPERATURE, self._MEAS_TMP_RDY, self._t_os)
        praw = self._measure(self._CMD_PRESSURE, self._MEAS_PRS_RDY, self._p_os)
        self._last_traw = traw
        return praw, traw

    def _temperature(self, traw):
        c = self._coef
        return c['c0'] * 0.5 + c['c1'] * (traw / self._SCALE[self._t_os])

    def _pressure(self, praw, traw):
        c = self._coef
        p = praw / self._SCALE[self._p_os]
        t = traw / self._SCALE[self._t_os]
        return (c['c00'] + p * (c['c10'] + p * (c['c20'] + p * c['c30']))
                + t * c['c01'] + t * p * (c['c11'] + p * c['c21']))

    def read(self):
        """Measure temperature, then pressure (command mode) and compensate both.

        Returns:
            dict: ``{temperature_c, pressure_pa}`` — degrees Celsius and pascals.
        """
        praw, traw = self._measure_raw()
        return {
            'temperature_c': self._temperature(traw),
            'pressure_pa': self._pressure(praw, traw),
        }


class SPL06Full(SPL06Minimal):
    """SPL06-007 full interface — extends SPL06Minimal with oversampling and rate
    configuration, background (continuous) mode, the 32-entry FIFO, raw values and
    compensation, altitude, soft reset and the data-ready / FIFO-full interrupt.

    The interrupt output shares the SDO pin. ``INT_HL`` stays at its reset value 0
    (active low), whose idle-high level matches the default address 0x77.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    SOURCE_PRESSURE    = 0x01
    SOURCE_TEMPERATURE = 0x02
    SOURCE_FIFO_FULL   = 0x04

    _CFG_INT_HL   = 0x80
    _CFG_INT_FIFO = 0x40
    _CFG_INT_PRS  = 0x20
    _CFG_INT_TMP  = 0x10
    _CFG_FIFO_EN  = 0x02

    _VALID_OS = (1, 2, 4, 8, 16, 32, 64, 128)

    def __init__(self, connection):
        super().__init__(connection)
        self._p_rate = 0
        self._t_rate = 0
        self._continuous = 0
        self._int_callback = None
        self._int_handler = None
        self._int_src_pin = None
        self._poll_stop = None

    @classmethod
    def _log2(cls, value, name):
        if value not in cls._VALID_OS:
            raise ValueError('%s must be 1, 2, 4, 8, 16, 32, 64 or 128' % name)
        return cls._VALID_OS.index(value)

    def _write_cfg(self):
        self._connection.write_reg(self._REG_CFG_REG, self._cfg)

    def _int_bits(self, source):
        bits = 0
        if source & self.SOURCE_PRESSURE:
            bits |= self._CFG_INT_PRS
        if source & self.SOURCE_TEMPERATURE:
            bits |= self._CFG_INT_TMP
        if source & self.SOURCE_FIFO_FULL:
            bits |= self._CFG_INT_FIFO
        return bits

    # -- measurement -----------------------------------------------------

    def read_pressure(self):
        """Measure temperature and pressure and return the compensated pressure.

        Returns:
            float: Pressure in pascals.
        """
        return self.read()['pressure_pa']

    def read_temperature(self):
        """Measure and return the temperature.

        Returns:
            float: Temperature in degrees Celsius.
        """
        if self._continuous:
            return self.read_continuous()['temperature_c']
        traw = self._measure(self._CMD_TEMPERATURE, self._MEAS_TMP_RDY, self._t_os)
        self._last_traw = traw
        return self._temperature(traw)

    def read(self):
        """Measure temperature, then pressure, and compensate both.

        In background mode this returns the latest results (see :meth:`read_continuous`)
        instead of starting a command-mode measurement.

        Returns:
            dict: ``{temperature_c, pressure_pa}`` — degrees Celsius and pascals.
        """
        if self._continuous:
            return self.read_continuous()
        return super().read()

    def read_raw(self):
        """Return the raw 24-bit two's-complement results.

        In command mode this measures temperature, then pressure; in background
        mode it reads the latest results without starting a measurement.

        Returns:
            tuple: ``(praw, traw)`` — signed raw pressure and temperature.
        """
        if self._continuous:
            b = self._connection.read_reg(self._REG_PRS_B2, 6)
            praw, traw = self._raw24(b[0:3]), self._raw24(b[3:6])
            self._last_traw = traw
            return praw, traw
        return self._measure_raw()

    def set_pressure_config(self, oversampling=16, rate=1):
        """Set the pressure oversampling and background rate.

        Args:
            oversampling: 1, 2, 4, 8, 16, 32, 64 or 128 (P_SHIFT is set above 8).
            rate: Background measurements per second: 1, 2, 4, ..., 128.

        Raises:
            ValueError: For values other than powers of two 1-128.
        """
        os_log2 = self._log2(oversampling, 'oversampling')
        rate_log2 = self._log2(rate, 'rate')
        self._connection.write_reg(self._REG_PRS_CFG, (rate_log2 << 4) | os_log2)
        self._p_os = os_log2
        self._p_rate = rate_log2
        if oversampling > 8:
            self._cfg |= self._CFG_P_SHIFT
        else:
            self._cfg &= ~self._CFG_P_SHIFT & 0xFF
        self._write_cfg()

    def set_temperature_config(self, oversampling=1, rate=1):
        """Set the temperature oversampling and background rate (always TMP_EXT = 1).

        Args:
            oversampling: 1, 2, 4, 8, 16, 32, 64 or 128 (T_SHIFT is set above 8).
            rate: Background measurements per second: 1, 2, 4, ..., 128.

        Raises:
            ValueError: For values other than powers of two 1-128.
        """
        os_log2 = self._log2(oversampling, 'oversampling')
        rate_log2 = self._log2(rate, 'rate')
        self._connection.write_reg(self._REG_TMP_CFG, self._TMP_EXT | (rate_log2 << 4) | os_log2)
        self._t_os = os_log2
        self._t_rate = rate_log2
        if oversampling > 8:
            self._cfg |= self._CFG_T_SHIFT
        else:
            self._cfg &= ~self._CFG_T_SHIFT & 0xFF
        self._write_cfg()

    def start_continuous(self, pressure=True, temperature=True):
        """Start background mode (MEAS_CTRL = 101 / 110 / 111).

        Args:
            pressure: Measure pressure continuously.
            temperature: Measure temperature continuously.

        Raises:
            ValueError: If both are False.
        """
        if not pressure and not temperature:
            raise ValueError('select pressure and/or temperature')
        mode = 0x04 | (0x01 if pressure else 0) | (0x02 if temperature else 0)
        self._connection.write_reg(self._REG_MEAS_CFG, mode)
        self._continuous = mode

    def stop(self):
        """Stop background mode and return to standby (MEAS_CTRL = 000)."""
        self._connection.write_reg(self._REG_MEAS_CFG, self._CMD_IDLE)
        self._continuous = 0

    def read_continuous(self):
        """Wait for the background ready flags and return the latest results.

        Does not start a measurement. Without temperature in background mode the
        last temperature result is used for the compensation.

        Returns:
            dict: ``{temperature_c, pressure_pa}`` — degrees Celsius and pascals.
        """
        flags = 0
        if self._continuous & 0x01:
            flags |= self._MEAS_PRS_RDY
        if self._continuous & 0x02:
            flags |= self._MEAS_TMP_RDY
        if flags:
            period = 1.0 / (1 << min(self._p_rate, self._t_rate))
            deadline = _now() + period * 2 + 0.5
            while self._connection.read_reg(self._REG_MEAS_CFG, 1)[0] & flags != flags:
                if _now() > deadline:
                    raise OSError('SPL06 background data not ready')
                time.sleep(0.002)
        b = self._connection.read_reg(self._REG_PRS_B2, 6)
        praw, traw = self._raw24(b[0:3]), self._raw24(b[3:6])
        self._last_traw = traw
        return {
            'temperature_c': self._temperature(traw),
            'pressure_pa': self._pressure(praw, traw),
        }

    def data_ready(self):
        """Read the MEAS_CFG ready flags.

        Returns:
            tuple: ``(pressure_ready, temperature_ready)`` booleans.
        """
        m = self._connection.read_reg(self._REG_MEAS_CFG, 1)[0]
        return bool(m & self._MEAS_PRS_RDY), bool(m & self._MEAS_TMP_RDY)

    # -- FIFO ------------------------------------------------------------

    def enable_fifo(self):
        """Enable the 32-entry result FIFO (CFG_REG.FIFO_EN)."""
        self._cfg |= self._CFG_FIFO_EN
        self._write_cfg()

    def disable_fifo(self):
        """Disable the result FIFO."""
        self._cfg &= ~self._CFG_FIFO_EN & 0xFF
        self._write_cfg()

    def read_fifo(self):
        """Drain the FIFO until FIFO_EMPTY (at most 32 entries).

        The LSB of each entry flags its kind (1 = pressure, 0 = temperature); it is
        cleared before scaling. Pressure entries are compensated with the most
        recent temperature entry (or the last measured temperature).

        Returns:
            list: ``(kind, value)`` tuples — ``"pressure"`` in pascals or
            ``"temperature"`` in degrees Celsius.
        """
        out = []
        for _ in range(32):
            if self._connection.read_reg(self._REG_FIFO_STS, 1)[0] & 0x01:
                break
            raw = self._raw24(self._connection.read_reg(self._REG_PRS_B2, 3))
            if raw == -0x800000:
                break
            if raw & 0x01:
                out.append(('pressure', self._pressure(raw & ~1, self._last_traw)))
            else:
                self._last_traw = raw
                out.append(('temperature', self._temperature(raw)))
        return out

    def flush_fifo(self):
        """Discard the FIFO contents (RESET.FIFO_FLUSH)."""
        self._connection.write_reg(self._REG_RESET, 0x80)

    def fifo_status(self):
        """Read FIFO_STS.

        Returns:
            tuple: ``(empty, full)`` booleans.
        """
        s = self._connection.read_reg(self._REG_FIFO_STS, 1)[0]
        return bool(s & 0x01), bool(s & 0x02)

    # -- misc ------------------------------------------------------------

    def reset(self):
        """Soft reset (RESET = 0x09), wait for the ready flags, re-read the
        coefficients and restore the :meth:`init` defaults."""
        try:
            self._connection.write_reg(self._REG_RESET, 0x09)
        except OSError:
            pass  # the chip may not acknowledge while it resets
        time.sleep(0.012)
        self._wait_ready()
        self._read_coefficients()
        self._apply_defaults()
        self._p_rate = 0
        self._t_rate = 0
        self._continuous = 0

    def coefficients(self):
        """Return the decoded calibration coefficients.

        Returns:
            dict: ``c0, c1, c00, c10, c01, c11, c20, c21, c30`` as signed integers.
        """
        return dict(self._coef)

    def compensate(self, praw, traw, p_oversampling, t_oversampling):
        """Compensate raw values with the stored coefficients (no bus access).

        Args:
            praw: Raw signed 24-bit pressure.
            traw: Raw signed 24-bit temperature.
            p_oversampling: Pressure oversampling the raw value was measured with.
            t_oversampling: Temperature oversampling the raw value was measured with.

        Returns:
            dict: ``{temperature_c, pressure_pa}`` — degrees Celsius and pascals.

        Raises:
            ValueError: For an oversampling other than a power of two 1-128.
        """
        kp = self._SCALE[self._log2(p_oversampling, 'p_oversampling')]
        kt = self._SCALE[self._log2(t_oversampling, 't_oversampling')]
        c = self._coef
        p = praw / kp
        t = traw / kt
        return {
            'temperature_c': c['c0'] * 0.5 + c['c1'] * t,
            'pressure_pa': (c['c00'] + p * (c['c10'] + p * (c['c20'] + p * c['c30']))
                            + t * c['c01'] + t * p * (c['c11'] + p * c['c21'])),
        }

    def altitude(self, sea_level_pa=101325.0):
        """Measure the pressure and convert it to altitude (barometric formula).

        Args:
            sea_level_pa: Reference sea-level pressure in pascals.

        Returns:
            float: Altitude in metres.
        """
        p = self.read_pressure()
        return 44330.0 * (1.0 - (p / sea_level_pa) ** (1.0 / 5.255))

    # -- interrupts ------------------------------------------------------

    def enable_interrupt(self, source):
        """Enable an interrupt source on SDO (CFG_REG.INT_PRS / INT_TMP / INT_FIFO).

        Args:
            source: SOURCE_PRESSURE, SOURCE_TEMPERATURE, SOURCE_FIFO_FULL or a bitwise OR.
        """
        self._cfg |= self._int_bits(source)
        self._write_cfg()

    def disable_interrupt(self, source):
        """Disable an interrupt source.

        Args:
            source: SOURCE_PRESSURE, SOURCE_TEMPERATURE, SOURCE_FIFO_FULL or a bitwise OR.
        """
        self._cfg &= ~self._int_bits(source) & 0xFF
        self._write_cfg()

    def poll_interrupt(self):
        """Read INT_STS (cleared on read).

        Returns:
            int: Bitmask of SOURCE_* flags (0 = none).
        """
        return self._connection.read_reg(self._REG_INT_STS, 1)[0] & 0x07

    def _dispatch_int(self):
        callback = self._int_callback
        if callback is None:
            return
        try:
            status = self.poll_interrupt()
            if status:
                callback(status)
        except Exception:
            pass

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to the interrupt.

        Uses ``int_pin`` (or the connection's ``int_pin``) when present, with the edge
        matching INT_HL; otherwise a 5 ms polling thread on :meth:`poll_interrupt`.

        Args:
            callback: Function called with the SOURCE_* status bitmask.
            int_pin: Optional InputPin wired to SDO.
        """
        self._int_callback = callback
        ipin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        if ipin is not None and hasattr(ipin, 'on_edge'):
            self._int_handler = lambda p: self._dispatch_int()
            if self._cfg & self._CFG_INT_HL:
                edge = getattr(ipin, 'RISING', 1)
            else:
                edge = getattr(ipin, 'FALLING', 2)
            ipin.on_edge(self._int_handler, edge)
            self._int_src_pin = ipin
        else:
            self._int_src_pin = None
            self._start_polling()

    def _start_polling(self):
        try:
            import _thread
        except ImportError:
            return
        stop = [False]
        self._poll_stop = stop

        def _loop():
            while not stop[0]:
                self._dispatch_int()
                time.sleep(0.005)

        _thread.start_new_thread(_loop, ())

    def off_interrupt(self):
        """Unsubscribe from the interrupt."""
        self._int_callback = None
        ipin = self._int_src_pin
        handler = self._int_handler
        self._int_handler = None
        self._int_src_pin = None
        if ipin is not None and handler is not None and hasattr(ipin, 'off_edge'):
            ipin.off_edge(handler)
        if self._poll_stop is not None:
            self._poll_stop[0] = True
            self._poll_stop = None
