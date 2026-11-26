"""Si1133 UV index and ambient light sensor (I²C).

The chip runs up to six measurement channels. Each channel selects a
photodiode combination, an integration time, an accumulation count and an
output width; the results are packed into ``HOSTOUT0..25`` in channel order.
All configuration lives in an indirect parameter table written with
``PARAM_SET`` (value in ``HOSTIN0``) and read with ``PARAM_QUERY``; every
command is confirmed by the counter in ``RESPONSE0``. Measurements run once
on ``FORCE`` or autonomously after ``START``. Results are raw ADC counts (the
datasheet gives no lux or UV-index formula).
"""

import time


def _delay_ms(ms):
    """Sleep for `ms` milliseconds."""
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(ms)
    else:
        time.sleep(ms / 1000.0)


class Si1133Minimal:
    """Si1133 UV and ambient light sensor — minimal interface.

    One call runs a forced measurement and returns the UV and white-light
    counts.

    Default configuration (written by :meth:`init` after a software reset):
        - channel 0: UV photodiode (``ADCMUX 0x18``), ``HW_GAIN`` 9, 24-bit output
        - channel 1: white photodiode (``ADCMUX 0x0B``), ``HW_GAIN`` 7, 24-bit output
        - ``CHAN_LIST = 0x03``, decimation rate 0, no accumulation, normal range
        - ``IRQ_ENABLE = 0x3F`` so ``IRQ_STATUS`` flags finished channels
          (INT goes low until ``IRQ_STATUS`` is read; it needs a pull-up and
          must be high at power-up)

    I²C address 0x55 (AD pulled up, default) or 0x52 (AD pulled down).

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    _REG_PART_ID    = 0x00
    _REG_HW_ID      = 0x01
    _REG_REV_ID     = 0x02
    _REG_HOSTIN0    = 0x0A
    _REG_COMMAND    = 0x0B
    _REG_IRQ_ENABLE = 0x0F
    _REG_RESPONSE1  = 0x10
    _REG_RESPONSE0  = 0x11
    _REG_IRQ_STATUS = 0x12
    _REG_HOSTOUT0   = 0x13

    _PART_ID = 0x33

    _CMD_RESET_CMD_CTR = 0x00
    _CMD_RESET_SW      = 0x01
    _CMD_FORCE         = 0x11
    _CMD_PAUSE         = 0x12
    _CMD_START         = 0x13
    _CMD_PARAM_QUERY   = 0x40
    _CMD_PARAM_SET     = 0x80

    RESP_RUNNING = 0x80
    RESP_SUSPEND = 0x40
    RESP_SLEEP   = 0x20
    RESP_CMD_ERR = 0x10

    _PARAM_I2C_ADDR   = 0x00
    _PARAM_CHAN_LIST  = 0x01
    _PARAM_ADCCONFIG0 = 0x02
    _PARAM_ADCSENS0   = 0x03
    _PARAM_ADCPOST0   = 0x04
    _PARAM_MEASCONFIG0 = 0x05
    _PARAM_MEASRATE_H = 0x1A
    _PARAM_MEASRATE_L = 0x1B
    _PARAM_MEASCOUNT0 = 0x1C
    _PARAM_THRESHOLD0_H = 0x25
    _PARAM_BURST      = 0x2B

    MUX_SMALL_IR    = 0x00
    MUX_MEDIUM_IR   = 0x01
    MUX_LARGE_IR    = 0x02
    MUX_WHITE       = 0x0B
    MUX_LARGE_WHITE = 0x0D
    MUX_UV          = 0x18
    MUX_UV_DEEP     = 0x19

    _CMD_POLLS = 50          # 1 ms each
    _MEASURE_POLLS = 15000   # 1 ms each; covers HW_GAIN 11 with SW_GAIN 128

    def __init__(self, connection):
        self._connection = connection
        self._chan_list = 0
        self._bits24 = [False] * 6
        self._irq_enable = 0

    def init(self):
        """Verify the chip, reset it and configure the UV and white channels.

        Raises:
            OSError: If PART_ID is not 0x33 or a command fails.
        """
        _delay_ms(25)
        part = self._read_reg(self._REG_PART_ID)
        if part != self._PART_ID:
            raise OSError('Unexpected Si1133 PART_ID 0x%02X, expected 0x33' % part)
        self._software_reset()
        self._apply_defaults()

    # --- low-level helpers --------------------------------------------------

    def _read_reg(self, reg):
        return self._connection.read_reg(reg, 1)[0]

    def _write_reg(self, reg, value):
        self._connection.write_reg(reg, value & 0xFF)

    def _software_reset(self):
        """RESET_SW, then wait until RESPONSE0 shows SLEEP."""
        self._write_reg(self._REG_COMMAND, self._CMD_RESET_SW)
        _delay_ms(10)
        for _ in range(self._CMD_POLLS):
            if self._read_reg(self._REG_RESPONSE0) & self.RESP_SLEEP:
                break
            _delay_ms(1)
        else:
            raise OSError('Si1133 reset timeout')
        self._chan_list = 0
        self._bits24 = [False] * 6
        self._irq_enable = 0

    def _apply_defaults(self):
        self._set_channel(0, self.MUX_UV, 9, 0, 0, False, True, 0)
        self._set_channel(1, self.MUX_WHITE, 7, 0, 0, False, True, 0)
        self._param_set(self._PARAM_CHAN_LIST, 0x03)
        self._chan_list = 0x03
        self._irq_enable = 0x3F
        self._write_reg(self._REG_IRQ_ENABLE, self._irq_enable)

    def _counter(self):
        """Read the RESPONSE0 counter, clearing a pending error first."""
        resp = self._read_reg(self._REG_RESPONSE0)
        if resp & self.RESP_CMD_ERR:
            self._write_reg(self._REG_COMMAND, self._CMD_RESET_CMD_CTR)
            resp = self._read_reg(self._REG_RESPONSE0)
        return resp & 0x0F

    def _wait_counter(self, before):
        for _ in range(self._CMD_POLLS):
            resp = self._read_reg(self._REG_RESPONSE0)
            if resp & self.RESP_CMD_ERR:
                self._write_reg(self._REG_COMMAND, self._CMD_RESET_CMD_CTR)
                raise OSError('Si1133 command error 0x%02X' % (resp & 0x1F))
            if (resp & 0x0F) != before:
                return
            _delay_ms(1)
        raise OSError('Si1133 command timeout')

    def _command(self, cmd):
        """Send a command and wait for the RESPONSE0 counter to move."""
        before = self._counter()
        self._write_reg(self._REG_COMMAND, cmd)
        self._wait_counter(before)

    def _param_set(self, address, value):
        """PARAM_SET: HOSTIN0 and COMMAND in one write, then the counter check."""
        before = self._counter()
        self._connection.write_reg(self._REG_HOSTIN0,
                                   bytes((value & 0xFF, self._CMD_PARAM_SET | (address & 0x3F))))
        self._wait_counter(before)

    def _param_query(self, address):
        """PARAM_QUERY, then read RESPONSE1."""
        self._command(self._CMD_PARAM_QUERY | (address & 0x3F))
        return self._read_reg(self._REG_RESPONSE1)

    def _set_channel(self, channel, adcmux, hw_gain, sw_gain, decim_rate,
                     high_signal, bits24, postshift):
        base = 4 * channel
        self._param_set(self._PARAM_ADCCONFIG0 + base, (decim_rate << 5) | adcmux)
        self._param_set(self._PARAM_ADCSENS0 + base,
                        (0x80 if high_signal else 0) | (sw_gain << 4) | hw_gain)
        self._param_set(self._PARAM_ADCPOST0 + base,
                        (0x40 if bits24 else 0) | (postshift << 3))
        self._bits24[channel] = bool(bits24)

    def _decode(self, data):
        """Split HOSTOUT bytes into per-channel values for the enabled channels."""
        values = []
        pos = 0
        for ch in range(6):
            if not self._chan_list & (1 << ch):
                continue
            if self._bits24[ch]:
                v = (data[pos] << 16) | (data[pos + 1] << 8) | data[pos + 2]
                if v & 0x800000:
                    v -= 0x1000000
                pos += 3
            else:
                v = (data[pos] << 8) | data[pos + 1]
                pos += 2
            values.append(v)
        return values

    def _output_length(self):
        n = 0
        for ch in range(6):
            if self._chan_list & (1 << ch):
                n += 3 if self._bits24[ch] else 2
        return n

    def _read_outputs(self):
        n = self._output_length()
        if n == 0:
            return []
        return self._decode(bytes(self._connection.read_reg(self._REG_HOSTOUT0, n)))

    def _measure(self):
        """FORCE the enabled channels, wait for their IRQ_STATUS bits, decode."""
        self._read_reg(self._REG_IRQ_STATUS)          # clear stale flags
        self._command(self._CMD_FORCE)
        wait = self._chan_list & self._irq_enable
        if wait == 0:
            _delay_ms(100)
        else:
            seen = 0
            for _ in range(self._MEASURE_POLLS):
                seen |= self._read_reg(self._REG_IRQ_STATUS)
                if seen & wait == wait:
                    break
                _delay_ms(1)
            else:
                raise OSError('Si1133 measurement timeout')
        return self._read_outputs()

    # --- public API ---------------------------------------------------------

    def read(self):
        """Run one forced measurement and return the UV and white counts.

        Returns:
            dict: ``{uv, white}`` — signed 24-bit raw counts of channels 0 and 1
            (values near zero or slightly negative in the dark).

        Raises:
            OSError: If channel 0 or 1 is disabled, a command fails or the
                measurement does not finish.
        """
        if self._chan_list & 0x03 != 0x03:
            raise OSError('Si1133 channels 0 and 1 must be enabled for read()')
        values = self._measure()
        return {'uv': values[0], 'white': values[1]}


class Si1133Full(Si1133Minimal):
    """Si1133 full interface — extends Si1133Minimal with per-channel
    configuration, single-photodiode reads, autonomous and burst modes,
    thresholds, direct parameter access, the I²C address and the INT
    interrupt.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    SOURCE_CHANNEL0 = 0x01
    SOURCE_CHANNEL1 = 0x02
    SOURCE_CHANNEL2 = 0x04
    SOURCE_CHANNEL3 = 0x08
    SOURCE_CHANNEL4 = 0x10
    SOURCE_CHANNEL5 = 0x20

    _SCRATCH = 5

    def __init__(self, connection):
        super().__init__(connection)
        self._counter_index = [0] * 6
        self._int_callback = None
        self._int_handler = None
        self._int_pin = None
        self._poll_stop = None

    @staticmethod
    def _check(name, value, lo, hi):
        if not lo <= value <= hi:
            raise ValueError('%s must be %d..%d' % (name, lo, hi))

    # --- identity -------------------------------------------------------------

    def read_id(self):
        """Read the identification registers.

        Returns:
            tuple: ``(part_id, hw_id, rev_id)`` — 0x33, implementation/silicon
            revision code and product revision.
        """
        b = self._connection.read_reg(self._REG_PART_ID, 3)
        return b[0], b[1], b[2]

    # --- channels ---------------------------------------------------------------

    def configure_channel(self, channel, adcmux, hw_gain=0, sw_gain=0, decim_rate=0,
                          high_signal=False, bits24=True, postshift=0):
        """Configure one measurement channel (ADCCONFIG, ADCSENS, ADCPOST).

        Clears the channel's threshold selection (see :meth:`set_channel_threshold`).

        Args:
            channel: Channel 0–5.
            adcmux: Photodiode selection (one of the ``MUX_*`` constants).
            hw_gain: Integration time code 0–11 (48.8 µs × 2^n at decimation 0).
            sw_gain: Accumulate 2^n samples, n = 0–7.
            decim_rate: Decimation rate 0–3.
            high_signal: Divide the sensitivity by 14.5 (direct sunlight).
            bits24: 24-bit signed output (True) or 16-bit unsigned.
            postshift: Right shift 0–7 of the accumulated result.

        Raises:
            ValueError: If a value is out of range.
        """
        self._check('channel', channel, 0, 5)
        self._check('adcmux', adcmux, 0, 0x1F)
        self._check('hw_gain', hw_gain, 0, 11)
        self._check('sw_gain', sw_gain, 0, 7)
        self._check('decim_rate', decim_rate, 0, 3)
        self._check('postshift', postshift, 0, 7)
        self._set_channel(channel, adcmux, hw_gain, sw_gain, decim_rate,
                          high_signal, bits24, postshift)

    def enable_channels(self, mask):
        """Select the channels that run (CHAN_LIST).

        Args:
            mask: Bit c enables channel c (0x00–0x3F).

        Raises:
            ValueError: If mask is out of range.
        """
        self._check('mask', mask, 0, 0x3F)
        self._param_set(self._PARAM_CHAN_LIST, mask)
        self._chan_list = mask

    def read_channels(self, force=True):
        """Read the results of all enabled channels in channel order.

        Args:
            force: Run a forced measurement first (True) or only decode the
                current ``HOSTOUT`` contents (autonomous mode).

        Returns:
            list: One int per enabled channel (24-bit signed or 16-bit unsigned).
        """
        if force:
            return self._measure()
        return self._read_outputs()

    def _scratch_read(self, adcmux, hw_gain):
        ch = self._SCRATCH
        saved = self._chan_list
        self._set_channel(ch, adcmux, hw_gain, 0, 0, False, True, 0)
        if self._counter_index[ch]:
            self._param_set(self._PARAM_MEASCONFIG0 + 4 * ch, 0)
            self._counter_index[ch] = 0
        self._param_set(self._PARAM_CHAN_LIST, 1 << ch)
        self._chan_list = 1 << ch
        try:
            return self._measure()[0]
        finally:
            self._param_set(self._PARAM_CHAN_LIST, saved)
            self._chan_list = saved

    def read_uv(self):
        """Measure the UV photodiode once (channel 5 used as scratch, ``HW_GAIN`` 9).

        Returns:
            int: Signed 24-bit raw count.
        """
        return self._scratch_read(self.MUX_UV, 9)

    def read_white(self):
        """Measure the white photodiode once (channel 5 used as scratch, ``HW_GAIN`` 7).

        Returns:
            int: Signed 24-bit raw count.
        """
        return self._scratch_read(self.MUX_WHITE, 7)

    def read_ir(self, photodiode=Si1133Minimal.MUX_SMALL_IR):
        """Measure an IR photodiode once (channel 5 used as scratch, ``HW_GAIN`` 7).

        Args:
            photodiode: ``MUX_SMALL_IR``, ``MUX_MEDIUM_IR`` or ``MUX_LARGE_IR``.

        Returns:
            int: Signed 24-bit raw count.

        Raises:
            ValueError: If photodiode is not an IR selection.
        """
        self._check('photodiode', photodiode, 0, 2)
        return self._scratch_read(photodiode, 7)

    def force(self):
        """Send FORCE without waiting for the results."""
        self._command(self._CMD_FORCE)

    # --- autonomous / burst ---------------------------------------------------

    def set_channel_counter(self, channel, index):
        """Assign a rate counter to a channel (MEASCONFIG.COUNTER_INDEX).

        Args:
            channel: Channel 0–5.
            index: 0 = none (forced only), 1–3 = ``MEASCOUNT0..2``.

        Raises:
            ValueError: If a value is out of range.
        """
        self._check('channel', channel, 0, 5)
        self._check('index', index, 0, 3)
        self._param_set(self._PARAM_MEASCONFIG0 + 4 * channel, index << 6)
        self._counter_index[channel] = index

    def start_autonomous(self, rate, counters=(1, 1, 1)):
        """Start autonomous measurements.

        Writes ``MEASRATE`` (800 µs units) and ``MEASCOUNT0..2``, assigns counter
        1 (``MEASCOUNT0``) to every enabled channel without a counter, then
        sends START. Channel period = rate × MEASCOUNTk.

        Args:
            rate: Main measurement period in seconds (800 µs … 52.4 s).
            counters: Three rate multipliers 1–255.

        Raises:
            ValueError: If a value is out of range.
        """
        ticks = int(round(rate / 800e-6))
        self._check('rate ticks', ticks, 1, 0xFFFF)
        if len(counters) != 3:
            raise ValueError('counters must have three entries')
        for c in counters:
            self._check('counter', c, 1, 0xFF)
        self._param_set(self._PARAM_MEASRATE_H, ticks >> 8)
        self._param_set(self._PARAM_MEASRATE_L, ticks & 0xFF)
        for i, c in enumerate(counters):
            self._param_set(self._PARAM_MEASCOUNT0 + i, c)
        for ch in range(6):
            if self._chan_list & (1 << ch) and not self._counter_index[ch]:
                self.set_channel_counter(ch, 1)
        self._command(self._CMD_START)

    def pause(self):
        """Pause autonomous measurements (PAUSE)."""
        self._command(self._CMD_PAUSE)

    def set_burst(self, count):
        """Configure burst mode (BURST parameter).

        Args:
            count: Number of readings 1–127; 0 disables burst mode.

        Raises:
            ValueError: If count is out of range.
        """
        self._check('count', count, 0, 127)
        self._param_set(self._PARAM_BURST, (0x80 | count) if count else 0)

    # --- thresholds -----------------------------------------------------------

    def set_threshold(self, index, value):
        """Set one of the three 16-bit thresholds.

        Args:
            index: Threshold 0–2.
            value: Threshold count 0–65535.

        Raises:
            ValueError: If a value is out of range.
        """
        self._check('index', index, 0, 2)
        self._check('value', value, 0, 0xFFFF)
        self._param_set(self._PARAM_THRESHOLD0_H + 2 * index, value >> 8)
        self._param_set(self._PARAM_THRESHOLD0_H + 2 * index + 1, value & 0xFF)

    def set_channel_threshold(self, channel, index):
        """Select the threshold a channel's interrupt compares against.

        Args:
            channel: Channel 0–5.
            index: 0 = off, 1–3 = ``THRESHOLD0..2``.

        Raises:
            ValueError: If a value is out of range.
        """
        self._check('channel', channel, 0, 5)
        self._check('index', index, 0, 3)
        address = self._PARAM_ADCPOST0 + 4 * channel
        value = self._param_query(address)
        self._param_set(address, (value & 0xFC) | index)

    # --- parameters, status ---------------------------------------------------

    def read_parameter(self, address):
        """Read a parameter-table byte (PARAM_QUERY).

        Args:
            address: Parameter address 0x00–0x3F.

        Returns:
            int: Parameter value.
        """
        self._check('address', address, 0, 0x3F)
        return self._param_query(address)

    def write_parameter(self, address, value):
        """Write a parameter-table byte (PARAM_SET).

        Writing the channel parameters directly bypasses the driver's record of
        the channel layout; prefer :meth:`configure_channel`.

        Args:
            address: Parameter address 0x00–0x3F.
            value: Byte value.
        """
        self._check('address', address, 0, 0x3F)
        self._check('value', value, 0, 0xFF)
        self._param_set(address, value)

    def read_response(self):
        """Decode RESPONSE0.

        Returns:
            tuple: ``(state, error, counter)`` — state bits (``RESP_RUNNING``,
            ``RESP_SUSPEND``, ``RESP_SLEEP``), the ``CMD_ERR`` flag and the
            4-bit counter (the error code when ``error`` is True).
        """
        r = self._read_reg(self._REG_RESPONSE0)
        return r & 0xE0, bool(r & self.RESP_CMD_ERR), r & 0x0F

    def data_ready(self):
        """Read IRQ_STATUS (clears it).

        Returns:
            int: Bit c set when channel c has a new result.
        """
        return self._read_reg(self._REG_IRQ_STATUS) & 0x3F

    def set_i2c_address(self, address):
        """Change the I²C address (volatile; reverts on reset).

        The chip answers on the new address right away, so no completion check
        is made; create a new connection with the new address afterwards.

        Args:
            address: New 7-bit address 0x08–0x77.
        """
        self._check('address', address, 0x08, 0x77)
        self._read_reg(self._REG_RESPONSE0)
        self._connection.write_reg(self._REG_HOSTIN0,
                                   bytes((address, self._CMD_PARAM_SET | self._PARAM_I2C_ADDR)))

    def reset(self):
        """Software reset (RESET_SW), wait for SLEEP, re-apply the Minimal defaults."""
        self._software_reset()
        self._counter_index = [0] * 6
        self._apply_defaults()

    # --- interrupt ------------------------------------------------------------

    def enable_interrupt(self, source):
        """Enable the INT output for channels (IRQ_ENABLE).

        Args:
            source: ``SOURCE_CHANNEL*`` mask.
        """
        self._irq_enable = (self._irq_enable | source) & 0x3F
        self._write_reg(self._REG_IRQ_ENABLE, self._irq_enable)

    def disable_interrupt(self, source):
        """Disable the INT output for channels.

        Forced reads only wait for channels whose interrupt is enabled; with
        none enabled they wait a fixed 100 ms.

        Args:
            source: ``SOURCE_CHANNEL*`` mask.
        """
        self._irq_enable &= ~source & 0x3F
        self._write_reg(self._REG_IRQ_ENABLE, self._irq_enable)

    def poll_interrupt(self):
        """Read IRQ_STATUS (clears it and releases INT).

        Returns:
            int: Channel mask, 0 = nothing pending.
        """
        return self.data_ready()

    def _dispatch_int(self):
        callback = self._int_callback
        if callback is None:
            return
        status = self.poll_interrupt()
        if status:
            callback(status)

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to channel-ready events.

        Uses ``int_pin`` (or the connection's ``int_pin``) on its falling edge
        when present; otherwise a 10 ms polling thread on IRQ_STATUS.

        Args:
            callback: Function called with the IRQ_STATUS channel mask.
            int_pin: Optional InputPin wired to INT.
        """
        self.off_interrupt()
        self._int_callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        if pin is not None and hasattr(pin, 'on_edge'):
            self._int_pin = pin
            self._int_handler = lambda *a: self._dispatch_int()
            pin.on_edge(self._int_handler, getattr(pin, 'FALLING', 2))
        else:
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
                _delay_ms(10)

        _thread.start_new_thread(_loop, ())

    def off_interrupt(self):
        """Unsubscribe from the interrupt."""
        self._int_callback = None
        pin = self._int_pin
        handler = self._int_handler
        self._int_pin = None
        self._int_handler = None
        if pin is not None and handler is not None and hasattr(pin, 'off_edge'):
            pin.off_edge(handler)
        if self._poll_stop is not None:
            self._poll_stop[0] = True
            self._poll_stop = None
