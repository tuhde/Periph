class LTC4151Minimal:
    """LTC4151 7–80 V high-side current and voltage monitor — minimal interface.

    Provides supply voltage, load current and power with one call each. The
    chip scans SENSE, VIN and ADIN continuously after power-up, so no
    configuration is needed beyond the connection and the sense resistor.

    Default configuration (chip power-on state, not rewritten):
        - CONTROL = 0x0C: continuous scan, page read/write and stuck-bus timer enabled
        - 20 mΩ sense resistor (full-scale current 4.096 A)

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device (0x67–0x6F).
        r_sense: Sense resistor in ohms (default 0.02).
    """

    _REG_SENSE = 0x00
    _REG_VIN   = 0x02
    _REG_ADIN  = 0x04
    _REG_CONTROL = 0x06

    _LSB_SENSE = 20e-6
    _LSB_VIN   = 0.025
    _LSB_ADIN  = 0.0005

    def __init__(self, connection, r_sense=0.02):
        if r_sense <= 0:
            raise ValueError('r_sense must be > 0')
        self._connection = connection
        self._r_sense = r_sense

    @staticmethod
    def _code(high, low):
        return (high << 4) | (low >> 4)

    def voltage(self):
        """Read the supply voltage at VIN.

        Returns:
            float: VIN in volts (code × 25 mV).
        """
        d = self._connection.read_reg(self._REG_VIN, 2)
        return self._code(d[0], d[1]) * self._LSB_VIN

    def current(self):
        """Read the load current through the sense resistor.

        Returns:
            float: Current in amperes (sense voltage / r_sense).
        """
        d = self._connection.read_reg(self._REG_SENSE, 2)
        return self._code(d[0], d[1]) * self._LSB_SENSE / self._r_sense

    def power(self):
        """Read the load power from one consistent block read of SENSE and VIN.

        Returns:
            float: Power in watts (VIN × current).
        """
        d = self._connection.read_reg(self._REG_SENSE, 4)
        sense_v = self._code(d[0], d[1]) * self._LSB_SENSE
        vin = self._code(d[2], d[3]) * self._LSB_VIN
        return vin * sense_v / self._r_sense


class LTC4151Full(LTC4151Minimal):
    """LTC4151 full interface — extends LTC4151Minimal with all channels, snapshot mode and CONTROL access.

    Channel constants (pass to read_raw, snapshot, is_busy):
        SENSE = 0, VIN = 1, ADIN = 2

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device (0x67–0x6F).
        r_sense: Sense resistor in ohms (default 0.02).
    """

    SENSE = 0
    VIN = 1
    ADIN = 2

    _CTRL_SNAPSHOT = 0x80
    _CTRL_TEST = 0x10
    _CTRL_PAGE_RW = 0x08
    _CTRL_STUCK_BUS = 0x04
    _SNAPSHOT_TIMEOUT_S = 0.1

    def __init__(self, connection, r_sense=0.02):
        super().__init__(connection, r_sense)
        self._page_rw = True

    def _read_block(self, reg, length):
        if self._page_rw or length == 1:
            return self._connection.read_reg(reg, length)
        out = bytearray()
        for i in range(length):
            out += self._connection.read_reg(reg + i, 1)
        return bytes(out)

    def _read_control_raw(self):
        return self._connection.read_reg(self._REG_CONTROL, 1)[0]

    def sense_voltage(self):
        """Read the voltage across the sense resistor.

        Returns:
            float: SENSE+ − SENSE− in volts (code × 20 µV).
        """
        d = self._read_block(self._REG_SENSE, 2)
        return self._code(d[0], d[1]) * self._LSB_SENSE

    def adin_voltage(self):
        """Read the ADIN pin voltage.

        Returns:
            float: ADIN in volts (code × 0.5 mV).
        """
        d = self._read_block(self._REG_ADIN, 2)
        return self._code(d[0], d[1]) * self._LSB_ADIN

    def voltage(self):
        """Read the supply voltage at VIN.

        Returns:
            float: VIN in volts (code × 25 mV).
        """
        d = self._read_block(self._REG_VIN, 2)
        return self._code(d[0], d[1]) * self._LSB_VIN

    def current(self):
        """Read the load current through the sense resistor.

        Returns:
            float: Current in amperes (sense voltage / r_sense).
        """
        return self.sense_voltage() / self._r_sense

    def power(self):
        """Read the load power from one consistent block read of SENSE and VIN.

        Returns:
            float: Power in watts (VIN × current).
        """
        d = self._read_block(self._REG_SENSE, 4)
        sense_v = self._code(d[0], d[1]) * self._LSB_SENSE
        vin = self._code(d[2], d[3]) * self._LSB_VIN
        return vin * sense_v / self._r_sense

    def read_all(self):
        """Read all three channels from one block read of registers 0x00–0x05.

        Returns:
            dict: {'vin': V, 'sense_v': V, 'current': A, 'power': W, 'adin': V}.
        """
        d = self._read_block(self._REG_SENSE, 6)
        sense_v = self._code(d[0], d[1]) * self._LSB_SENSE
        vin = self._code(d[2], d[3]) * self._LSB_VIN
        adin = self._code(d[4], d[5]) * self._LSB_ADIN
        current = sense_v / self._r_sense
        return {'vin': vin, 'sense_v': sense_v, 'current': current,
                'power': vin * current, 'adin': adin}

    def read_raw(self, channel):
        """Read the 12-bit code of a channel.

        Args:
            channel: SENSE (0), VIN (1) or ADIN (2).

        Returns:
            int: 12-bit conversion code, 0–4095.
        """
        if channel not in (0, 1, 2):
            raise ValueError('channel must be 0, 1 or 2')
        d = self._read_block(channel * 2, 2)
        return self._code(d[0], d[1])

    def set_sense_resistor(self, ohms):
        """Set the sense resistor value used for current and power.

        Args:
            ohms: Sense resistor in ohms; must be greater than 0.
        """
        if ohms <= 0:
            raise ValueError('ohms must be > 0')
        self._r_sense = ohms

    def get_sense_resistor(self):
        """Get the sense resistor value.

        Returns:
            float: Sense resistor in ohms.
        """
        return self._r_sense

    def snapshot(self, channel):
        """Run one snapshot conversion of a channel, then return to continuous scan.

        Clears the snapshot bit first, then writes the channel with the snapshot
        bit set, polls the channel's BUSY bit (timeout 100 ms), reads the result
        and restores continuous scan.

        Args:
            channel: SENSE (0), VIN (1) or ADIN (2).

        Returns:
            float: Converted value in volts (sense voltage, VIN or ADIN).
        """
        import time
        if channel not in (0, 1, 2):
            raise ValueError('channel must be 0, 1 or 2')
        base = self._read_control_raw() & (self._CTRL_PAGE_RW | self._CTRL_STUCK_BUS)
        self._connection.write_reg(self._REG_CONTROL, bytes([base]))
        self._connection.write_reg(
            self._REG_CONTROL, bytes([base | self._CTRL_SNAPSHOT | (channel << 5)]))
        start = time.time()
        while self.is_busy(channel):
            if time.time() - start > self._SNAPSHOT_TIMEOUT_S:
                self._connection.write_reg(self._REG_CONTROL, bytes([base]))
                raise OSError('LTC4151 snapshot timeout')
            time.sleep(0.002)
        code = self.read_raw(channel)
        self._connection.write_reg(self._REG_CONTROL, bytes([base]))
        lsb = (self._LSB_SENSE, self._LSB_VIN, self._LSB_ADIN)[channel]
        return code * lsb

    def set_continuous(self):
        """Switch to continuous scan mode (snapshot bit cleared)."""
        value = self._read_control_raw() & ~(self._CTRL_SNAPSHOT | self._CTRL_TEST) & 0xFF
        self._connection.write_reg(self._REG_CONTROL, bytes([value]))

    def is_snapshot_mode(self):
        """Read the snapshot bit of CONTROL.

        Returns:
            bool: True if the chip is in snapshot mode.
        """
        return bool(self._read_control_raw() & self._CTRL_SNAPSHOT)

    def is_busy(self, channel):
        """Read the BUSY bit of a channel (snapshot mode).

        Args:
            channel: SENSE (0), VIN (1) or ADIN (2).

        Returns:
            bool: True while that channel is being converted.
        """
        if channel not in (0, 1, 2):
            raise ValueError('channel must be 0, 1 or 2')
        return bool(self._connection.read_reg(channel * 2 + 1, 1)[0] & 0x08)

    def set_stuck_bus_timer(self, enabled):
        """Enable or disable the stuck-bus reset timer (CONTROL.G2, read-modify-write).

        Args:
            enabled: True to enable (power-on default).
        """
        value = self._read_control_raw() & ~self._CTRL_TEST
        value = (value | self._CTRL_STUCK_BUS) if enabled else (value & ~self._CTRL_STUCK_BUS)
        self._connection.write_reg(self._REG_CONTROL, bytes([value & 0xFF]))

    def set_page_rw(self, enabled):
        """Enable or disable I²C page read/write (CONTROL.G3, read-modify-write).

        When disabled the chip only supports byte/word transfers, so this driver
        then reads each register separately.

        Args:
            enabled: True to enable (power-on default).
        """
        value = self._read_control_raw() & ~self._CTRL_TEST
        value = (value | self._CTRL_PAGE_RW) if enabled else (value & ~self._CTRL_PAGE_RW)
        self._connection.write_reg(self._REG_CONTROL, bytes([value & 0xFF]))
        self._page_rw = bool(enabled)

    def read_control(self):
        """Read the CONTROL register.

        Returns:
            int: Raw CONTROL value (power-on default 0x0C).
        """
        return self._read_control_raw()

    def write_control(self, value):
        """Write the CONTROL register. Test mode (bit 4) is always masked to 0.

        Args:
            value: Raw CONTROL value.
        """
        value &= ~self._CTRL_TEST & 0xFF
        self._connection.write_reg(self._REG_CONTROL, bytes([value]))
        self._page_rw = bool(value & self._CTRL_PAGE_RW)

    @staticmethod
    def temperature_from_adin(adin_code, vin_code):
        """Convert an ADIN/VIN code pair to temperature (datasheet NTC example circuit).

        Valid for roughly 20–60 °C and only for the datasheet circuit.

        Args:
            adin_code: 12-bit ADIN code.
            vin_code: 12-bit VIN code.

        Returns:
            float: Temperature in °C, 58.82 × (adin_code / vin_code − 0.1066).
        """
        return 58.82 * (adin_code / vin_code - 0.1066)
