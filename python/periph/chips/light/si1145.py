"""Si1145 proximity, UV index and ambient light sensor (I²C).

The chip drives one IR LED (Si1146: two, Si1147: three), measures the
reflected light on up to three proximity channels (PS1–PS3) and the visible
and infrared ambient light, and reports the UV index in ``AUX_DATA`` (× 100)
when the UV coefficients ``UCOEF0..3`` are set. Configuration is split
between I²C registers and an indirect parameter RAM written with
``PARAM_SET`` (value in ``PARAM_WR``) and read with ``PARAM_QUERY``
(``PARAM_RD``); every command increments the counter in ``RESPONSE``.
Measurements run once on a ``*_FORCE`` command or autonomously after a
``*_AUTO`` command at the ``MEAS_RATE`` interval. Proximity and ambient
values are raw counts (the datasheet gives no lux formula).
"""

import time


def _delay_ms(ms):
    """Sleep for `ms` milliseconds."""
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(ms)
    else:
        time.sleep(ms / 1000.0)


class Si1145Minimal:
    """Si1145 proximity / UV / ambient light sensor — minimal interface.

    One call runs a forced PS + ALS measurement and returns the proximity
    count, the UV index and the visible/infrared counts.

    Default configuration (written by :meth:`init`):
        - ``HW_KEY = 0x17``, ``UCOEF0..3 = 0x7B, 0x6B, 0x01, 0x00``
        - ``CHLIST = 0xB1`` (UV, ALS IR, ALS VIS, PS1)
        - PS1 on the large IR photodiode with LED1 (``PSLED12_SELECT = 0x01``)
        - ``PS_LED21 = 0x03`` (LED1 at current level 3 of 15)
        - gain 0, normal signal range, forced mode
        - ``IRQ_ENABLE = ALS_IE | PS1_IE`` so ``IRQ_STATUS`` flags finished
          measurements (the INT pin stays undriven until
          :meth:`Si1145Full.enable_interrupt`)

    I²C address 0x60 (fixed; older silicon used 0x5A).

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    _REG_PART_ID     = 0x00
    _REG_REV_ID      = 0x01
    _REG_SEQ_ID      = 0x02
    _REG_INT_CFG     = 0x03
    _REG_IRQ_ENABLE  = 0x04
    _REG_HW_KEY      = 0x07
    _REG_MEAS_RATE0  = 0x08
    _REG_MEAS_RATE1  = 0x09
    _REG_PS_RATE     = 0x0A
    _REG_PS_LED21    = 0x0F
    _REG_PS_LED3     = 0x10
    _REG_UCOEF0      = 0x13
    _REG_PARAM_WR    = 0x17
    _REG_COMMAND     = 0x18
    _REG_RESPONSE    = 0x20
    _REG_IRQ_STATUS  = 0x21
    _REG_ALS_VIS     = 0x22
    _REG_ALS_IR      = 0x24
    _REG_PS1         = 0x26
    _REG_AUX         = 0x2C
    _REG_PARAM_RD    = 0x2E

    _PART_IDS = (0x45, 0x46, 0x47)
    _HW_KEY = 0x17
    UCOEF_DEFAULT = (0x7B, 0x6B, 0x01, 0x00)

    _CMD_NOP         = 0x00
    _CMD_RESET       = 0x01
    _CMD_BUSADDR     = 0x02
    _CMD_PS_FORCE    = 0x05
    _CMD_ALS_FORCE   = 0x06
    _CMD_PSALS_FORCE = 0x07
    _CMD_PSALS_PAUSE = 0x0B
    _CMD_PS_AUTO     = 0x0D
    _CMD_ALS_AUTO    = 0x0E
    _CMD_PSALS_AUTO  = 0x0F
    _CMD_GET_CAL     = 0x12
    _CMD_PARAM_QUERY = 0x80
    _CMD_PARAM_SET   = 0xA0

    _PARAM_I2C_ADDR       = 0x00
    _PARAM_CHLIST         = 0x01
    _PARAM_PSLED12_SELECT = 0x02
    _PARAM_PSLED3_SELECT  = 0x03
    _PARAM_PS_ENCODING    = 0x05
    _PARAM_ALS_ENCODING   = 0x06
    _PARAM_PS1_ADCMUX     = 0x07
    _PARAM_PS_ADC_COUNTER = 0x0A
    _PARAM_PS_ADC_GAIN    = 0x0B
    _PARAM_PS_ADC_MISC    = 0x0C
    _PARAM_AUX_ADCMUX     = 0x0F
    _PARAM_ALS_VIS_ADC_COUNTER = 0x10
    _PARAM_ALS_VIS_ADC_GAIN    = 0x11
    _PARAM_ALS_VIS_ADC_MISC    = 0x12
    _PARAM_ALS_IR_ADC_COUNTER  = 0x1D
    _PARAM_ALS_IR_ADC_GAIN     = 0x1E
    _PARAM_ALS_IR_ADC_MISC     = 0x1F

    CH_PS1     = 0x01
    CH_PS2     = 0x02
    CH_PS3     = 0x04
    CH_ALS_VIS = 0x10
    CH_ALS_IR  = 0x20
    CH_AUX     = 0x40
    CH_UV      = 0x80
    _CH_ALS = CH_ALS_VIS | CH_ALS_IR | CH_AUX | CH_UV

    SOURCE_ALS = 0x01
    SOURCE_PS1 = 0x04
    SOURCE_PS2 = 0x08
    SOURCE_PS3 = 0x10
    SOURCE_CMD = 0x20
    _SOURCE_MASK = 0x3D

    _CMD_POLLS = 50          # 1 ms each
    _MEASURE_POLLS = 1000    # 1 ms each; a gain-7 ALS measurement takes ~200 ms

    def __init__(self, connection):
        self._connection = connection
        self._part_id = 0x45
        self._seq_id = 0
        self._chlist = 0
        self._irq_enable = 0

    def init(self):
        """Verify the chip, unlock it and configure UV, ALS and PS1.

        Raises:
            OSError: If PART_ID is not 0x45/0x46/0x47 or a command fails.
        """
        _delay_ms(25)
        part = self._read_reg(self._REG_PART_ID)
        if part not in self._PART_IDS:
            raise OSError('Unexpected Si1145 PART_ID 0x%02X, expected 0x45/0x46/0x47' % part)
        self._part_id = part
        self._seq_id = self._read_reg(self._REG_SEQ_ID)
        self._apply_defaults()

    # --- low-level helpers --------------------------------------------------

    def _read_reg(self, reg):
        return self._connection.read_reg(reg, 1)[0]

    def _write_reg(self, reg, value):
        self._connection.write_reg(reg, value & 0xFF)

    def _apply_defaults(self):
        self._write_reg(self._REG_HW_KEY, self._HW_KEY)
        self._connection.write_reg(self._REG_UCOEF0, bytes(self.UCOEF_DEFAULT))
        self._param_set(self._PARAM_CHLIST, self.CH_UV | self.CH_ALS_IR | self.CH_ALS_VIS | self.CH_PS1)
        self._chlist = self.CH_UV | self.CH_ALS_IR | self.CH_ALS_VIS | self.CH_PS1
        self._param_set(self._PARAM_PSLED12_SELECT, 0x01)
        self._write_reg(self._REG_PS_LED21, 0x03)
        self._irq_enable = self.SOURCE_ALS | self.SOURCE_PS1
        self._write_reg(self._REG_IRQ_ENABLE, self._irq_enable)

    def _counter(self):
        """Read the RESPONSE counter, clearing a pending error with NOP first."""
        resp = self._read_reg(self._REG_RESPONSE)
        if resp & 0x80:
            self._write_reg(self._REG_COMMAND, self._CMD_NOP)
            resp = self._read_reg(self._REG_RESPONSE)
        return resp & 0x0F

    def _command(self, cmd):
        """Send a command and wait for the RESPONSE counter to move."""
        before = self._counter()
        self._write_reg(self._REG_COMMAND, cmd)
        for _ in range(self._CMD_POLLS):
            resp = self._read_reg(self._REG_RESPONSE)
            if resp & 0x80:
                self._write_reg(self._REG_COMMAND, self._CMD_NOP)
                raise OSError('Si1145 command 0x%02X error 0x%02X' % (cmd, resp))
            if (resp & 0x0F) != before:
                return
            _delay_ms(1)
        raise OSError('Si1145 command 0x%02X timeout' % cmd)

    def _param_set(self, offset, value):
        """PARAM_SET: PARAM_WR, then COMMAND = 0xA0 | offset."""
        self._write_reg(self._REG_PARAM_WR, value)
        self._command(self._CMD_PARAM_SET | (offset & 0x1F))

    def _param_query(self, offset):
        """PARAM_QUERY, then read PARAM_RD."""
        self._command(self._CMD_PARAM_QUERY | (offset & 0x1F))
        return self._read_reg(self._REG_PARAM_RD)

    def _clear_irq(self):
        status = self._read_reg(self._REG_IRQ_STATUS)
        if status:
            self._write_reg(self._REG_IRQ_STATUS, status)
        return status

    def _expected_sources(self, proximity, als):
        mask = 0
        if als and self._chlist & self._CH_ALS:
            mask |= self.SOURCE_ALS
        if proximity:
            mask |= (self._chlist & 0x07) << 2
        return mask & self._irq_enable

    def _measure(self, proximity, als):
        """Force a measurement and wait for the matching IRQ_STATUS bits."""
        if proximity and als:
            cmd = self._CMD_PSALS_FORCE
        elif proximity:
            cmd = self._CMD_PS_FORCE
        else:
            cmd = self._CMD_ALS_FORCE
        self._clear_irq()
        self._counter()
        self._write_reg(self._REG_COMMAND, cmd)
        wait = self._expected_sources(proximity, als)
        if wait == 0:
            _delay_ms(100)
            return
        seen = 0
        for _ in range(self._MEASURE_POLLS):
            status = self._read_reg(self._REG_IRQ_STATUS)
            if status:
                self._write_reg(self._REG_IRQ_STATUS, status)
                seen |= status
            if seen & wait == wait:
                return
            _delay_ms(1)
        raise OSError('Si1145 measurement timeout')

    def _read_data(self):
        b = self._connection.read_reg(self._REG_ALS_VIS, 12)
        return [b[i] | (b[i + 1] << 8) for i in range(0, 12, 2)]

    # --- public API ---------------------------------------------------------

    def read(self):
        """Run one forced PS + ALS measurement.

        Returns:
            dict: ``{proximity, uv_index, visible, infrared}`` — raw PS1 count,
            UV index (``AUX_DATA / 100``) and raw visible/infrared counts.

        Raises:
            OSError: If a command fails or the measurement does not finish.
        """
        self._measure(True, True)
        vis, ir, ps1, _ps2, _ps3, aux = self._read_data()
        return {'proximity': ps1, 'uv_index': aux / 100.0,
                'visible': vis, 'infrared': ir}


class Si1145Full(Si1145Minimal):
    """Si1145 full interface — extends Si1145Minimal with per-channel reads,
    channel list, LED and photodiode selection, gains, ranges, alignment,
    UV coefficients, calibration, autonomous mode, the I²C address, reset
    and the INT interrupt.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    AUX_TEMPERATURE = 0x65
    AUX_VDD = 0x75

    def __init__(self, connection):
        super().__init__(connection)
        self._int_callback = None
        self._int_handler = None
        self._int_pin = None
        self._poll_stop = None

    @staticmethod
    def _check(name, value, lo, hi):
        if not lo <= value <= hi:
            raise ValueError('%s must be %d..%d' % (name, lo, hi))

    def _led_count(self):
        return self._part_id - 0x44

    # --- identity -------------------------------------------------------------

    def read_id(self):
        """Read the identification registers.

        Returns:
            tuple: ``(part_id, rev_id, seq_id)`` — 0x45/0x46/0x47, hardware
            revision and sequencer firmware revision.
        """
        b = self._connection.read_reg(self._REG_PART_ID, 3)
        return b[0], b[1], b[2]

    # --- measurements -----------------------------------------------------------

    def read_proximity(self, channel=1):
        """Run a forced PS measurement and return one proximity channel.

        Args:
            channel: PS channel 1–3 (Si1145: 1, Si1146: 1–2, Si1147: 1–3);
                it must be enabled in the channel list.

        Returns:
            int: Raw proximity count (rises as an object approaches).

        Raises:
            ValueError: If the channel is not supported or not enabled.
        """
        self._check('channel', channel, 1, self._led_count())
        if not self._chlist & (1 << (channel - 1)):
            raise ValueError('PS%d is not enabled (set_channels)' % channel)
        self._measure(True, False)
        return self._read_data()[1 + channel]

    def read_uv_index(self):
        """Run a forced ALS measurement and return the UV index.

        Returns:
            float: ``AUX_DATA / 100``.

        Raises:
            ValueError: If the UV channel is not enabled.
        """
        if not self._chlist & self.CH_UV:
            raise ValueError('UV is not enabled (set_channels)')
        self._measure(False, True)
        return self._read_data()[5] / 100.0

    def read_visible(self):
        """Run a forced ALS measurement and return the visible count.

        Returns:
            int: Raw ``ALS_VIS_DATA`` count.

        Raises:
            ValueError: If the visible channel is not enabled.
        """
        if not self._chlist & self.CH_ALS_VIS:
            raise ValueError('visible is not enabled (set_channels)')
        self._measure(False, True)
        return self._read_data()[0]

    def read_infrared(self):
        """Run a forced ALS measurement and return the infrared count.

        Returns:
            int: Raw ``ALS_IR_DATA`` count.

        Raises:
            ValueError: If the infrared channel is not enabled.
        """
        if not self._chlist & self.CH_ALS_IR:
            raise ValueError('infrared is not enabled (set_channels)')
        self._measure(False, True)
        return self._read_data()[1]

    def read_raw(self):
        """Read the latest data registers without measuring.

        Returns:
            dict: ``{visible, infrared, ps1, ps2, ps3, aux}`` raw 16-bit values
            (``aux`` is the UV index × 100 while UV is enabled).
        """
        vis, ir, ps1, ps2, ps3, aux = self._read_data()
        return {'visible': vis, 'infrared': ir, 'ps1': ps1, 'ps2': ps2,
                'ps3': ps3, 'aux': aux}

    def _read_aux(self, mux):
        saved = self._chlist
        self._param_set(self._PARAM_AUX_ADCMUX, mux)
        self._param_set(self._PARAM_CHLIST, self.CH_AUX)
        self._chlist = self.CH_AUX
        try:
            self._measure(False, True)
            return self._read_data()[5]
        finally:
            self._param_set(self._PARAM_CHLIST, saved)
            self._chlist = saved

    def read_temperature_raw(self):
        """Measure the die temperature on the auxiliary channel.

        Temporarily switches ``CHLIST`` to ``EN_AUX`` with ``AUX_ADCMUX = 0x65``
        and restores the channel list afterwards.

        Returns:
            int: Raw relative temperature count (no absolute calibration).
        """
        return self._read_aux(self.AUX_TEMPERATURE)

    def read_vdd_raw(self):
        """Measure the supply voltage on the auxiliary channel.

        Temporarily switches ``CHLIST`` to ``EN_AUX`` with ``AUX_ADCMUX = 0x75``
        and restores the channel list afterwards.

        Returns:
            int: Raw VDD count.
        """
        return self._read_aux(self.AUX_VDD)

    # --- configuration ----------------------------------------------------------

    def set_channels(self, uv, visible, infrared, ps1, ps2=False, ps3=False, aux=False):
        """Select the measured channels (CHLIST).

        Args:
            uv: UV index (in ``AUX_DATA``).
            visible: Visible ambient light.
            infrared: Infrared ambient light.
            ps1: Proximity channel 1.
            ps2: Proximity channel 2 (Si1146/47).
            ps3: Proximity channel 3 (Si1147).
            aux: Auxiliary channel (``AUX_ADCMUX``); excludes ``uv``.

        Raises:
            ValueError: If ``uv`` and ``aux`` are both set or the part lacks
                the PS channel.
        """
        if uv and aux:
            raise ValueError('uv and aux share AUX_DATA; enable only one')
        if (ps2 and self._led_count() < 2) or (ps3 and self._led_count() < 3):
            raise ValueError('PS channel not available on this part')
        mask = ((self.CH_UV if uv else 0) | (self.CH_AUX if aux else 0) |
                (self.CH_ALS_IR if infrared else 0) | (self.CH_ALS_VIS if visible else 0) |
                (self.CH_PS3 if ps3 else 0) | (self.CH_PS2 if ps2 else 0) |
                (self.CH_PS1 if ps1 else 0))
        self._param_set(self._PARAM_CHLIST, mask)
        self._chlist = mask

    def set_led_current(self, led, level):
        """Set an LED drive current (PS_LED21 / PS_LED3).

        Args:
            led: LED 1–3 (Si1145: 1 only).
            level: Current level 0 (off) … 15 (maximum, ~359 mA).

        Raises:
            ValueError: If a value is out of range.
        """
        self._check('led', led, 1, self._led_count())
        self._check('level', level, 0, 15)
        if led == 3:
            self._write_reg(self._REG_PS_LED3, level)
        else:
            cur = self._read_reg(self._REG_PS_LED21)
            if led == 1:
                cur = (cur & 0xF0) | level
            else:
                cur = (cur & 0x0F) | (level << 4)
            self._write_reg(self._REG_PS_LED21, cur)

    def set_ps_led(self, channel, leds):
        """Select the LEDs a PS channel drives (PSLED12_SELECT / PSLED3_SELECT).

        Args:
            channel: PS channel 1–3.
            leds: LED bit mask (bit 0 LED1, bit 1 LED2, bit 2 LED3); only the
                part's LEDs are allowed (Si1145: 0 or 1).

        Raises:
            ValueError: If a value is out of range.
        """
        self._check('channel', channel, 1, 3)
        self._check('leds', leds, 0, (1 << self._led_count()) - 1)
        if channel == 3:
            self._param_set(self._PARAM_PSLED3_SELECT, leds)
            return
        cur = self._param_query(self._PARAM_PSLED12_SELECT)
        if channel == 1:
            cur = (cur & 0x70) | leds
        else:
            cur = (cur & 0x07) | (leds << 4)
        self._param_set(self._PARAM_PSLED12_SELECT, cur)

    def set_ps_photodiode(self, channel, large):
        """Select the photodiode of a PS channel (PSn_ADCMUX).

        Args:
            channel: PS channel 1–3.
            large: Large IR photodiode (True, ``0x03``) or small (``0x00``).
        """
        self._check('channel', channel, 1, 3)
        self._param_set(self._PARAM_PS1_ADCMUX + channel - 1, 0x03 if large else 0x00)

    def set_gain(self, visible=0, infrared=0, proximity=0):
        """Set the ADC gains (integration time × 2^n) and recovery counts.

        The recovery count is set to the datasheet recommendation, the one's
        complement of the gain. Do not combine a high proximity gain with a
        high LED current.

        Args:
            visible: ``ALS_VIS_ADC_GAIN`` 0–7.
            infrared: ``ALS_IR_ADC_GAIN`` 0–7.
            proximity: ``PS_ADC_GAIN`` 0–7 (LED pulse width × 2^n).

        Raises:
            ValueError: If a value is out of range.
        """
        self._check('visible', visible, 0, 7)
        self._check('infrared', infrared, 0, 7)
        self._check('proximity', proximity, 0, 7)
        for gain, g_off, c_off in ((visible, self._PARAM_ALS_VIS_ADC_GAIN, self._PARAM_ALS_VIS_ADC_COUNTER),
                                   (infrared, self._PARAM_ALS_IR_ADC_GAIN, self._PARAM_ALS_IR_ADC_COUNTER),
                                   (proximity, self._PARAM_PS_ADC_GAIN, self._PARAM_PS_ADC_COUNTER)):
            self._param_set(g_off, gain)
            self._param_set(c_off, ((~gain) & 0x07) << 4)

    def set_high_range(self, visible, infrared, proximity):
        """Select the high signal range (sensitivity ÷ 14.5) per measurement.

        Use the visible/infrared high range for UV in direct sunlight.

        Args:
            visible: ``ALS_VIS_ADC_MISC.VIS_RANGE``.
            infrared: ``ALS_IR_ADC_MISC.IR_RANGE``.
            proximity: ``PS_ADC_MISC.PS_RANGE`` (``PS_ADC_MODE`` stays 1).
        """
        self._param_set(self._PARAM_ALS_VIS_ADC_MISC, 0x20 if visible else 0)
        self._param_set(self._PARAM_ALS_IR_ADC_MISC, 0x20 if infrared else 0)
        self._param_set(self._PARAM_PS_ADC_MISC, 0x24 if proximity else 0x04)

    def set_alignment(self, visible=False, infrared=False, ps1=False, ps2=False, ps3=False):
        """Report the 16 LSBs instead of the 16 MSBs of the ADC result.

        Args:
            visible: ``ALS_ENCODING.ALS_VIS_ALIGN``.
            infrared: ``ALS_ENCODING.ALS_IR_ALIGN``.
            ps1: ``PS_ENCODING.PS1_ALIGN``.
            ps2: ``PS_ENCODING.PS2_ALIGN``.
            ps3: ``PS_ENCODING.PS3_ALIGN``.
        """
        self._param_set(self._PARAM_ALS_ENCODING,
                        (0x20 if infrared else 0) | (0x10 if visible else 0))
        self._param_set(self._PARAM_PS_ENCODING,
                        (0x40 if ps3 else 0) | (0x20 if ps2 else 0) | (0x10 if ps1 else 0))

    def set_uv_coefficients(self, ucoef):
        """Write the UV coefficients UCOEF0..3.

        Args:
            ucoef: Four bytes; ``UCOEF_DEFAULT`` gives the UV index × 100.

        Raises:
            ValueError: If not four bytes.
        """
        if len(ucoef) != 4:
            raise ValueError('ucoef must have four entries')
        for c in ucoef:
            self._check('ucoef', c, 0, 0xFF)
        self._connection.write_reg(self._REG_UCOEF0, bytes(ucoef))

    def read_calibration(self):
        """Read the factory calibration (GET_CAL).

        Overwrites the data registers ``0x22``–``0x2D``.

        Returns:
            list: 12 calibration bytes.
        """
        self._command(self._CMD_GET_CAL)
        return list(self._connection.read_reg(self._REG_ALS_VIS, 12))

    # --- autonomous mode -----------------------------------------------------

    def start_autonomous(self, interval, proximity=True, als=True):
        """Start autonomous measurements.

        Writes ``MEAS_RATE = round(interval / 31.25 µs)`` (byte order swapped
        on parts with ``SEQ_ID = 0x01``), then ``PS_AUTO``, ``ALS_AUTO`` or
        ``PSALS_AUTO``.

        Args:
            interval: Measurement interval in seconds (31.25 µs … 2.048 s).
            proximity: Run the PS channels.
            als: Run the ALS/UV channels.

        Raises:
            ValueError: If the interval is out of range or nothing is selected.
        """
        rate = int(round(interval / 31.25e-6))
        self._check('rate ticks', rate, 1, 0xFFFF)
        if not (proximity or als):
            raise ValueError('select proximity and/or als')
        if self._seq_id == 0x01:
            self._write_reg(self._REG_PS_RATE, rate & 0xFF)
            self._write_reg(self._REG_MEAS_RATE0, rate >> 8)
        else:
            self._connection.write_reg(self._REG_MEAS_RATE0, bytes((rate & 0xFF, rate >> 8)))
        if proximity and als:
            self._command(self._CMD_PSALS_AUTO)
        elif proximity:
            self._command(self._CMD_PS_AUTO)
        else:
            self._command(self._CMD_ALS_AUTO)

    def pause(self):
        """Pause the autonomous loop (PSALS_PAUSE)."""
        self._command(self._CMD_PSALS_PAUSE)

    def force(self, proximity=True, als=True):
        """Start a forced measurement without waiting (PS/ALS/PSALS_FORCE).

        Args:
            proximity: Measure the PS channels.
            als: Measure the ALS/UV channels.

        Raises:
            ValueError: If nothing is selected.
        """
        if not (proximity or als):
            raise ValueError('select proximity and/or als')
        cmd = (self._CMD_PSALS_FORCE if proximity and als
               else self._CMD_PS_FORCE if proximity else self._CMD_ALS_FORCE)
        self._command(cmd)

    # --- status, address, reset -----------------------------------------------

    def read_response(self):
        """Decode RESPONSE.

        Returns:
            tuple: ``(counter, error)`` — 4-bit command counter and the error
            code (0 = none, ``0x80`` invalid setting, ``0x88``–``0x8E`` ADC
            overflow).
        """
        r = self._read_reg(self._REG_RESPONSE)
        if r & 0x80:
            return 0, r
        return r & 0x0F, 0

    def set_i2c_address(self, address):
        """Change the I²C address (volatile; reverts on power-up).

        Writes ``I2C_ADDR`` and sends ``BUSADDR``; the chip answers on the new
        address right away, so create a new connection afterwards.

        Args:
            address: New 7-bit address 0x08–0x77.
        """
        self._check('address', address, 0x08, 0x77)
        self._param_set(self._PARAM_I2C_ADDR, address)
        self._write_reg(self._REG_COMMAND, self._CMD_BUSADDR)

    def reset(self):
        """Software reset, wait, write HW_KEY and re-apply the Minimal defaults."""
        self._write_reg(self._REG_COMMAND, self._CMD_RESET)
        _delay_ms(10)
        self._apply_defaults()

    # --- interrupt ------------------------------------------------------------

    def enable_interrupt(self, source):
        """Drive the INT pin for sources (INT_CFG.INT_OE and IRQ_ENABLE).

        Args:
            source: ``SOURCE_*`` mask.
        """
        self._irq_enable = (self._irq_enable | source) & self._SOURCE_MASK
        self._write_reg(self._REG_INT_CFG, 0x01)
        self._write_reg(self._REG_IRQ_ENABLE, self._irq_enable)

    def disable_interrupt(self, source):
        """Clear sources from IRQ_ENABLE.

        Forced reads only wait for enabled sources; with none enabled they
        wait a fixed 100 ms.

        Args:
            source: ``SOURCE_*`` mask.
        """
        self._irq_enable &= ~source & self._SOURCE_MASK
        self._write_reg(self._REG_IRQ_ENABLE, self._irq_enable)

    def poll_interrupt(self):
        """Read IRQ_STATUS and write it back to clear it (releases INT).

        Returns:
            int: Pending ``SOURCE_*`` mask among the enabled sources, 0 = none.
        """
        return self._clear_irq() & self._irq_enable

    def _dispatch_int(self):
        callback = self._int_callback
        if callback is None:
            return
        status = self.poll_interrupt()
        if status:
            callback(status)

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to measurement events.

        Uses ``int_pin`` (or the connection's ``int_pin``) on its falling edge
        when present; otherwise a 10 ms polling thread on IRQ_STATUS.

        Args:
            callback: Function called with the ``SOURCE_*`` mask.
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
