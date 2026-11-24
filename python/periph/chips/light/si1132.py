"""Si1132 UV index and ambient light sensor (I²C).

The chip is a small sequencer: configuration lives partly in I²C registers
and partly in an indirect parameter RAM, written with ``PARAM_WR`` plus a
``PARAM_SET`` command and read back with ``PARAM_QUERY``. Measurements are
started by the ``ALS_FORCE`` command (forced) or run autonomously at a
programmed interval. The UV index is delivered as ``AUX_DATA / 100``; the
visible and infrared channels are reported as raw ADC counts (the datasheet
gives no source-independent lux formula).
"""


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


class Si1132Minimal:
    """Si1132 UV index and ambient light sensor — minimal interface.

    The constructor waits 25 ms (start-up), verifies ``PART_ID = 0x32``,
    writes ``HW_KEY``, the default UV coefficients and enables the UV,
    infrared and visible channels in forced mode (gain 0, normal range).

    Args:
        connection: ``RegisterConnection`` (I²C) bound to address 0x60.

    Raises:
        OSError: If ``PART_ID`` is not 0x32.
    """

    _REG_PART_ID    = 0x00
    _REG_SEQ_ID     = 0x02
    _REG_INT_CFG    = 0x03
    _REG_IRQ_ENABLE = 0x04
    _REG_HW_KEY     = 0x07
    _REG_MEAS_RATE0 = 0x08
    _REG_MEAS_RATE1 = 0x09
    _REG_MEAS_RATE_SWAPPED = 0x0A
    _REG_UCOEF      = 0x13
    _REG_PARAM_WR   = 0x17
    _REG_COMMAND    = 0x18
    _REG_RESPONSE   = 0x20
    _REG_IRQ_STATUS = 0x21
    _REG_DATA       = 0x22
    _REG_PARAM_RD   = 0x2E

    _PART_ID        = 0x32
    _HW_KEY_VALUE   = 0x17
    _UCOEF_DEFAULT  = (0x7B, 0x6B, 0x01, 0x00)

    _CMD_PARAM_QUERY = 0x80
    _CMD_PARAM_SET   = 0xA0
    _CMD_NOP         = 0x00
    _CMD_RESET       = 0x01
    _CMD_BUSADDR     = 0x02
    _CMD_ALS_FORCE   = 0x06
    _CMD_ALS_PAUSE   = 0x0A
    _CMD_ALS_AUTO    = 0x0E
    _CMD_GET_CAL     = 0x12

    _PARAM_I2C_ADDR     = 0x00
    _PARAM_CHLIST       = 0x01
    _PARAM_ALS_ENCODING = 0x06
    _PARAM_AUX_ADCMUX   = 0x0F
    _PARAM_VIS_COUNTER  = 0x10
    _PARAM_VIS_GAIN     = 0x11
    _PARAM_VIS_MISC     = 0x12
    _PARAM_IR_COUNTER   = 0x1D
    _PARAM_IR_GAIN      = 0x1E
    _PARAM_IR_MISC      = 0x1F

    _CHLIST_ALL     = 0xB0   # EN_UV | EN_ALS_IR | EN_ALS_VIS
    _CHLIST_AUX     = 0x40
    _IRQ_ALS_INT    = 0x01
    _IRQ_CLEAR      = 0x21   # ALS_INT | CMD_INT (write 1 to clear)
    _RESP_ERROR     = 0x80

    _TIMEOUT_MS     = 500

    def __init__(self, connection):
        self._connection = connection
        self._chlist = self._CHLIST_ALL
        _delay_ms(25)
        if self._read_reg(self._REG_PART_ID) != self._PART_ID:
            raise OSError('Si1132 not found (PART_ID mismatch)')
        self._seq_id = self._read_reg(self._REG_SEQ_ID)
        self._write_reg(self._REG_HW_KEY, self._HW_KEY_VALUE)
        self._write_regs(self._REG_UCOEF, self._UCOEF_DEFAULT)
        # ALS_IE lets IRQ_STATUS.ALS_INT flag completed measurements; the INT
        # pin stays released until INT_CFG.INT_OE is set (Full.enable_interrupt).
        self._write_reg(self._REG_IRQ_ENABLE, self._IRQ_ALS_INT)
        self._param_set(self._PARAM_CHLIST, self._chlist)

    # --- low-level helpers --------------------------------------------------

    def _write_reg(self, reg, value):
        """Write one byte to a register."""
        self._connection.write_reg(reg, value & 0xFF)

    def _write_regs(self, reg, values):
        """Write consecutive registers starting at `reg`."""
        self._connection.write_reg(reg, bytes(values))

    def _read_reg(self, reg):
        """Read one byte from a register."""
        return self._connection.read_reg(reg, 1)[0]

    def _wait_response(self, before):
        """Wait until the RESPONSE counter moved past `before`.

        Raises:
            OSError: On a RESPONSE error code or when the counter never moves.
        """
        for _ in range(20):
            resp = self._read_reg(self._REG_RESPONSE)
            if resp & self._RESP_ERROR:
                self._write_reg(self._REG_COMMAND, self._CMD_NOP)
                raise OSError('Si1132 command error 0x%02X' % resp)
            if (resp & 0x0F) != before:
                return
            _delay_ms(1)
        raise OSError('Si1132 command timeout')

    def _command(self, cmd):
        """Send a command and wait for its completion."""
        before = self._read_reg(self._REG_RESPONSE) & 0x0F
        self._write_reg(self._REG_COMMAND, cmd)
        self._wait_response(before)

    def _param_set(self, offset, value):
        """Write one parameter-RAM byte (PARAM_WR + PARAM_SET)."""
        before = self._read_reg(self._REG_RESPONSE) & 0x0F
        self._write_reg(self._REG_PARAM_WR, value)
        self._write_reg(self._REG_COMMAND, self._CMD_PARAM_SET | (offset & 0x1F))
        self._wait_response(before)

    def _param_query(self, offset):
        """Read one parameter-RAM byte (PARAM_QUERY + PARAM_RD)."""
        before = self._read_reg(self._REG_RESPONSE) & 0x0F
        self._write_reg(self._REG_COMMAND, self._CMD_PARAM_QUERY | (offset & 0x1F))
        self._wait_response(before)
        return self._read_reg(self._REG_PARAM_RD)

    def _start_measurement(self):
        """Clear the status flags and issue ALS_FORCE."""
        self._write_reg(self._REG_IRQ_STATUS, self._IRQ_CLEAR)
        self._command(self._CMD_ALS_FORCE)

    def _wait_ready(self):
        """Poll IRQ_STATUS.ALS_INT, then clear it.

        Raises:
            OSError: If no measurement completes within 500 ms.
        """
        waited = 0
        while not (self._read_reg(self._REG_IRQ_STATUS) & self._IRQ_ALS_INT):
            if waited >= self._TIMEOUT_MS:
                raise OSError('Si1132 measurement timeout')
            _delay_ms(1)
            waited += 1
        self._write_reg(self._REG_IRQ_STATUS, self._IRQ_CLEAR)

    def _read_data(self):
        """Read the data block 0x22–0x2D and return (visible, infrared, aux)."""
        d = bytes(self._connection.read_reg(self._REG_DATA, 12))
        return (d[0] | (d[1] << 8), d[2] | (d[3] << 8), d[10] | (d[11] << 8))

    def _measure(self):
        """Run one forced measurement and return (visible, infrared, aux)."""
        self._start_measurement()
        self._wait_ready()
        return self._read_data()

    # --- public API ---------------------------------------------------------

    def read(self):
        """Run one forced measurement of the enabled channels.

        Returns:
            dict: ``uv_index`` (float, ``AUX_DATA / 100``), ``visible`` and
            ``infrared`` (raw 16-bit counts).

        Raises:
            OSError: If the measurement does not complete in time.
        """
        vis, ir, aux = self._measure()
        return {'uv_index': aux / 100.0, 'visible': vis, 'infrared': ir}


class Si1132Full(Si1132Minimal):
    """Si1132 full interface: single channels, gain/range, autonomous mode, interrupt.

    Args:
        connection: ``RegisterConnection`` (I²C) bound to address 0x60; an
            optional ``int_pin`` attribute (``InputPin`` on INT) is used by
            :meth:`on_interrupt`.
    """

    def __init__(self, connection):
        Si1132Minimal.__init__(self, connection)
        self._int_callback = None
        self._int_pin = None
        self._int_handler = None
        self._poll_stop = None

    def read_uv_index(self):
        """Forced UV measurement.

        Returns:
            float: UV index (``AUX_DATA / 100``); needs ``EN_UV`` in the channel list.
        """
        return self._measure()[2] / 100.0

    def read_visible(self):
        """Forced measurement; return the raw visible ambient-light count (int)."""
        return self._measure()[0]

    def read_infrared(self):
        """Forced measurement; return the raw infrared ambient-light count (int)."""
        return self._measure()[1]

    def read_raw(self):
        """Read the latest data registers without starting a measurement.

        Returns:
            dict: ``visible``, ``infrared`` and ``aux`` raw 16-bit values.
        """
        vis, ir, aux = self._read_data()
        return {'visible': vis, 'infrared': ir, 'aux': aux}

    def _read_aux(self, mux):
        """Measure the auxiliary channel with ADC multiplexer `mux`."""
        self._param_set(self._PARAM_AUX_ADCMUX, mux)
        self._param_set(self._PARAM_CHLIST, self._CHLIST_AUX)
        try:
            return self._measure()[2]
        finally:
            self._param_set(self._PARAM_CHLIST, self._chlist)

    def read_temperature_raw(self):
        """Measure the auxiliary channel as die temperature (relative only).

        Returns:
            int: Raw 16-bit value (``AUX_ADCMUX = 0x65``).
        """
        return self._read_aux(0x65)

    def read_vdd_raw(self):
        """Measure the auxiliary channel as supply voltage.

        Returns:
            int: Raw 16-bit value (``AUX_ADCMUX = 0x75``).
        """
        return self._read_aux(0x75)

    def set_channels(self, uv, visible, infrared, aux=False):
        """Select the measured channels (``CHLIST``).

        Args:
            uv: Enable the UV channel.
            visible: Enable the visible ALS channel.
            infrared: Enable the infrared ALS channel.
            aux: Enable the auxiliary channel (cannot be combined with ``uv``).

        Raises:
            ValueError: If no channel is selected or both ``uv`` and ``aux`` are set.
        """
        if not (uv or visible or infrared or aux):
            raise ValueError('at least one channel must be enabled')
        if uv and aux:
            raise ValueError('uv and aux share the data registers')
        chlist = (0x80 if uv else 0) | (0x40 if aux else 0) \
            | (0x20 if infrared else 0) | (0x10 if visible else 0)
        self._param_set(self._PARAM_CHLIST, chlist)
        self._chlist = chlist

    def set_gain(self, visible=0, infrared=0):
        """Set the ADC gain (integration time x 2^n) and the recommended recovery count.

        Args:
            visible: Visible channel gain, 0-7.
            infrared: Infrared channel gain, 0-7.

        Raises:
            ValueError: If a gain is outside 0-7.
        """
        if not (0 <= visible <= 7 and 0 <= infrared <= 7):
            raise ValueError('gain must be 0-7')
        self._param_set(self._PARAM_VIS_GAIN, visible)
        self._param_set(self._PARAM_VIS_COUNTER, ((~visible) & 0x07) << 4)
        self._param_set(self._PARAM_IR_GAIN, infrared)
        self._param_set(self._PARAM_IR_COUNTER, ((~infrared) & 0x07) << 4)

    def set_high_range(self, visible, infrared):
        """Select the high signal range (sensitivity ÷ 14.5, for direct sunlight).

        Args:
            visible: High range for the visible channel.
            infrared: High range for the infrared channel.
        """
        self._param_set(self._PARAM_VIS_MISC, 0x20 if visible else 0x00)
        self._param_set(self._PARAM_IR_MISC, 0x20 if infrared else 0x00)

    def set_alignment(self, visible_low, infrared_low):
        """Select which 16 bits of the 17-bit ADC value are reported.

        Args:
            visible_low: True = least significant 16 bits, False = 16 MSBs.
            infrared_low: Same for the infrared channel.
        """
        self._param_set(self._PARAM_ALS_ENCODING,
                        (0x10 if visible_low else 0) | (0x20 if infrared_low else 0))

    def set_uv_coefficients(self, ucoef):
        """Write the four UV index coefficients ``UCOEF0..3``.

        Args:
            ucoef: Sequence of four byte values.

        Raises:
            ValueError: If `ucoef` does not have four elements.
        """
        if len(ucoef) != 4:
            raise ValueError('four coefficients required')
        self._write_regs(self._REG_UCOEF, ucoef)

    def read_calibration(self):
        """Read the factory calibration (``GET_CAL``).

        Returns:
            list: 12 bytes from registers 0x22-0x2D.
        """
        self._command(self._CMD_GET_CAL)
        return list(self._connection.read_reg(self._REG_DATA, 12))

    def start_autonomous(self, interval):
        """Start (or stop) autonomous measurements.

        Args:
            interval: Wake-up interval in seconds (multiple of 31.25 µs, at
                most about 2.05 s); 0 returns to forced mode.

        Raises:
            ValueError: If `interval` is negative or too long.
        """
        counts = int(round(interval / 31.25e-6))
        if interval < 0 or counts > 0xFFFF:
            raise ValueError('interval out of range')
        if self._seq_id == 0x01:   # erratum: MEAS_RATE bytes swapped
            self._write_reg(self._REG_MEAS_RATE_SWAPPED, counts & 0xFF)
            self._write_reg(self._REG_MEAS_RATE0, counts >> 8)
        else:
            self._write_reg(self._REG_MEAS_RATE0, counts & 0xFF)
            self._write_reg(self._REG_MEAS_RATE1, counts >> 8)
        self._command(self._CMD_ALS_AUTO if counts else self._CMD_ALS_PAUSE)

    def pause(self):
        """Pause autonomous measurements (``ALS_PAUSE``)."""
        self._command(self._CMD_ALS_PAUSE)

    def force(self):
        """Start one forced measurement without waiting for it (``ALS_FORCE``)."""
        self._start_measurement()

    def measurement_ready(self):
        """Return True when ``IRQ_STATUS.ALS_INT`` is set."""
        return bool(self._read_reg(self._REG_IRQ_STATUS) & self._IRQ_ALS_INT)

    def read_response(self):
        """Decode the ``RESPONSE`` register.

        Returns:
            tuple: (counter, error) — the 4-bit command counter and the error
            code (0 = none, otherwise the raw 0x8x value).
        """
        resp = self._read_reg(self._REG_RESPONSE)
        if resp & self._RESP_ERROR:
            return (0, resp)
        return (resp & 0x0F, 0)

    def set_i2c_address(self, address):
        """Change the I²C address (volatile; lost at reset).

        Args:
            address: New 7-bit address written to the ``I2C_ADDR`` parameter.
        """
        self._param_set(self._PARAM_I2C_ADDR, address)
        self._command(self._CMD_BUSADDR)

    def reset(self):
        """Software reset: ``RESET`` command, 1 ms wait, ``HW_KEY`` again."""
        self._write_reg(self._REG_COMMAND, self._CMD_RESET)
        _delay_ms(2)
        self._write_reg(self._REG_HW_KEY, self._HW_KEY_VALUE)

    # --- interrupt ----------------------------------------------------------

    def enable_interrupt(self):
        """Drive INT low on completed measurements (``INT_OE`` and ``ALS_IE``)."""
        self._write_reg(self._REG_IRQ_ENABLE, self._IRQ_ALS_INT)
        self._write_reg(self._REG_INT_CFG, 0x01)

    def disable_interrupt(self):
        """Release the INT pin (clears ``INT_OE``).

        ``ALS_IE`` stays set because the forced-measurement readout polls
        ``IRQ_STATUS.ALS_INT``.
        """
        self._write_reg(self._REG_INT_CFG, 0x00)

    def poll_interrupt(self):
        """Read ``IRQ_STATUS`` and clear the reported flags.

        Returns:
            int: The status value (0 = nothing pending).
        """
        status = self._read_reg(self._REG_IRQ_STATUS)
        if status:
            self._write_reg(self._REG_IRQ_STATUS, status)
        return status

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to measurement-complete events.

        Uses `int_pin` or the connection's ``int_pin`` (falling edge); when
        neither exists a polling thread calls :meth:`poll_interrupt`.

        Args:
            callback: Function called with the ``IRQ_STATUS`` value.
            int_pin: Optional ``InputPin`` wired to INT.
        """
        self.off_interrupt()
        self.enable_interrupt()
        self._int_callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        if pin is not None and hasattr(pin, 'on_edge'):
            self._int_pin = pin
            self._int_handler = lambda *a: self._dispatch_int()
            pin.on_edge(self._int_handler, getattr(pin, 'FALLING', 0))
        else:
            self._start_polling_fallback()

    def off_interrupt(self):
        """Unsubscribe and release the INT pin."""
        had = self._int_callback is not None
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
            self.disable_interrupt()

    def _dispatch_int(self):
        """Read and clear the status, then hand it to the callback."""
        callback = self._int_callback
        if callback is None:
            return
        status = self.poll_interrupt()
        if status:
            try:
                callback(status)
            except Exception:
                pass

    def _start_polling_fallback(self):
        """Poll IRQ_STATUS every 10 ms when no INT pin is wired."""
        try:
            import _thread
        except ImportError:
            return
        stop = [False]
        self._poll_stop = stop

        def _loop():
            while not stop[0]:
                if self._int_callback is not None:
                    self._dispatch_int()
                _delay_ms(10)

        _thread.start_new_thread(_loop, ())
