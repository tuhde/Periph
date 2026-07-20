class PCF8591Minimal:
    """PCF8591 8-bit quad ADC + DAC — minimal interface.

    Provides single-ended reads of the four analog inputs in 4 single-ended
    mode (AIP=00). No configuration beyond the transport is required. Each
    read transaction returns 5 bytes: the first is the previous conversion
    result and must be discarded; the next four are fresh channel samples.

    Args:
        transport: Configured I²C transport pointing at the device (0x48–0x4F).
    """

    _CONTROL_DEFAULT = 0x00  # AIP=00 (4 single-ended), AOE=0, AI=0, CHN=0
    _NUM_CHANNELS    = 4

    def __init__(self, transport):
        """Initialize PCF8591Minimal and store the transport.

        Args:
            transport: Configured I²C transport pointing at the device.
        """
        self._transport = transport

    def read_channel(self, channel):
        """Read a single channel as an unsigned 8-bit value.

        Uses single-shot conversion: writes the control byte selecting the
        channel, then reads 2 bytes (discarding the stale first byte).

        Args:
            channel: Channel number 0–3. Clamped to the valid range.

        Returns:
            int: Raw 8-bit value (0–255).
        """
        ch = channel if 0 <= channel < self._NUM_CHANNELS else 0
        if ch < 0:
            ch = 0
        ctrl = self._CONTROL_DEFAULT | (ch & 0x03)
        self._transport.write(bytes([ctrl]))
        buf = self._transport.read(2)
        return buf[1]

    def read_all(self):
        """Read all four channels as unsigned 8-bit values.

        Uses auto-increment (AI=1) to read all four channels in one
        transaction. Reads 5 bytes and discards the stale first byte.

        Returns:
            list[int]: Four raw 8-bit values [ch0, ch1, ch2, ch3].
        """
        ctrl = self._CONTROL_DEFAULT | 0x04  # AI=1
        self._transport.write(bytes([ctrl]))
        buf = self._transport.read(self._NUM_CHANNELS + 1)
        return [buf[1], buf[2], buf[3], buf[4]]


class PCF8591Full(PCF8591Minimal):
    """PCF8591 full interface — extends PCF8591Minimal with differential, voltage, and DAC output.

    Adds analog input mode selection (single-ended, differential, mixed),
    auto-increment, DAC enable/disable, raw and voltage-calibrated ADC reads,
    and signed differential reads.

    Args:
        transport: Configured I²C transport pointing at the device (0x48–0x4F).
    """

    MODE_4_SINGLE_ENDED = 0  # 4 single-ended inputs (AIN0–AIN3)
    MODE_3_DIFFERENTIAL = 1  # 3 differential inputs (vs AIN3)
    MODE_MIXED          = 2  # AIN0/1 single-ended, AIN2-AIN3 differential
    MODE_2_DIFFERENTIAL = 3  # 2 differential inputs

    def __init__(self, transport):
        """Initialize PCF8591Full and store the transport.

        Args:
            transport: Configured I²C transport pointing at the device.
        """
        super().__init__(transport)
        self._control = self._CONTROL_DEFAULT
        self._input_mode = self.MODE_4_SINGLE_ENDED
        self._dac_enabled = False
        self._auto_increment = False
        self._last_channel = 0

    def configure(self, input_mode, auto_increment, dac_enabled):
        """Set the analog input mode, auto-increment, and DAC enable.

        Writes the control byte to the chip and caches the configuration.

        Args:
            input_mode: Analog input programming 0–3 (see MODE_* constants).
            auto_increment: If True, AI=1 — channel increments after each conversion.
            dac_enabled: If True, AOE=1 — AOUT is active; AOUT returns to
                high-impedance when False.
        """
        aip = input_mode & 0x03
        ai = 1 if auto_increment else 0
        aoe = 1 if dac_enabled else 0
        chn = self._last_channel & 0x03
        self._control = (aip << 4) | (aoe << 6) | (ai << 2) | chn
        self._input_mode = aip
        self._auto_increment = bool(ai)
        self._dac_enabled = bool(aoe)
        self._transport.write(bytes([self._control]))

    def _read_with_control(self, n_bytes):
        buf = self._transport.read(n_bytes)
        return buf[1:]

    def read_channel_voltage(self, channel, vref, vagnd):
        """Read a single channel and convert to voltage.

        Args:
            channel: Channel number 0–3.
            vref: Reference voltage in volts.
            vagnd: Analog ground voltage in volts.

        Returns:
            float: Channel voltage in volts.
        """
        raw = self.read_channel(channel)
        return vagnd + raw * (vref - vagnd) / 256.0

    def read_all_voltage(self, vref, vagnd):
        """Read all four channels and convert each to voltage.

        Args:
            vref: Reference voltage in volts.
            vagnd: Analog ground voltage in volts.

        Returns:
            list[float]: Four channel voltages in volts [ch0, ch1, ch2, ch3].
        """
        raws = self.read_all()
        vfs = vref - vagnd
        return [vagnd + r * vfs / 256.0 for r in raws]

    def read_differential(self, channel):
        """Read a differential channel as a signed value.

        The chip must be configured in a differential mode (input_mode 1, 2,
        or 3). The result is interpreted as a signed 8-bit two's complement
        number.

        Args:
            channel: Differential channel index (0–2 for 3-diff mode, 0–1
                for 2-diff and mixed modes).

        Returns:
            int: Signed 8-bit value (-128 to 127).
        """
        ch = channel & 0x03
        ctrl = self._control | (ch & 0x03)
        self._last_channel = ch
        self._transport.write(bytes([ctrl]))
        buf = self._read_with_control(2)
        raw = buf[0]
        return raw - 256 if raw >= 128 else raw

    def set_dac(self, value):
        """Enable the DAC and write a raw 8-bit value.

        Sets the AOE bit so AOUT becomes active, then writes the DAC value
        in the byte following the control byte.

        Args:
            value: Raw 8-bit DAC value (0–255). Output voltage is
                V_AGND + value × (V_REF − V_AGND) / 256.
        """
        v = max(0, min(255, value))
        ctrl = (self._control | 0x40) & ~0x04  # AOE=1, AI=0
        self._control = ctrl
        self._dac_enabled = True
        self._transport.write(bytes([ctrl, v]))

    def set_dac_voltage(self, voltage_fraction):
        """Enable the DAC and set the output as a fraction of (VREF−VAGND).

        Args:
            voltage_fraction: Output level as a fraction of (VREF−VAGND)
                (0.0 = V_AGND, 1.0 = V_REF). Clamped to [0.0, 1.0].
        """
        f = voltage_fraction
        if f < 0.0:
            f = 0.0
        if f > 1.0:
            f = 1.0
        value = int(round(f * 255))
        self.set_dac(value)

    def disable_dac(self):
        """Disable the DAC output; AOUT returns to high-impedance."""
        ctrl = self._control & ~0x40  # AOE=0
        self._control = ctrl
        self._dac_enabled = False
        self._transport.write(bytes([ctrl]))
