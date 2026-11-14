import math
import time


def _crc8(data):
    """CRC-8 used by the Si7021: polynomial 0x31, init 0x00, no reflection, no final XOR."""
    crc = 0x00
    for byte in data:
        crc ^= byte
        for _ in range(8):
            if crc & 0x80:
                crc = ((crc << 1) ^ 0x31) & 0xFF
            else:
                crc = (crc << 1) & 0xFF
    return crc


class Si7021Minimal:
    """Si7021 humidity and temperature sensor — minimal interface.

    Each read() runs one relative-humidity measurement without hold master (the
    read header is polled until the chip ACKs, timeout 50 ms), verifies the CRC
    and then fetches the temperature of that same measurement with command 0xE0.

    Default configuration (power-on, nothing is written at construction):
        - 12-bit RH / 14-bit temperature, heater off
        - No-hold-master measurement (0xF5), polling the NACKed read header
        - CRC-8 verified on the RH result; relative humidity clamped to 0-100 %RH

    Args:
        connection: Configured I²C connection pointing at the device (address 0x40).
    """

    _CMD_RH_HOLD = 0xE5
    _CMD_RH_POLL = 0xF5
    _CMD_T_HOLD = 0xE3
    _CMD_T_POLL = 0xF3
    _CMD_T_PREVIOUS = 0xE0
    _CMD_RESET = 0xFE
    _CMD_WRITE_USER = 0xE6
    _CMD_READ_USER = 0xE7
    _POLL_ATTEMPTS = 50

    def __init__(self, connection):
        self._connection = connection
        self._stretch = False

    def _command(self, code):
        self._connection.write(bytes([code]))

    def _measure(self, hold_cmd, poll_cmd):
        """Run one measurement command; returns the verified raw 16-bit code."""
        if self._stretch:
            self._command(hold_cmd)
            data = bytes(self._connection.read(3))
        else:
            self._command(poll_cmd)
            data = None
            for _ in range(self._POLL_ATTEMPTS):
                try:
                    data = bytes(self._connection.read(3))
                    break
                except OSError:
                    time.sleep(0.001)      # read header NACKed: not ready yet
            if data is None:
                raise OSError('Si7021 measurement did not complete')
        if _crc8(data[0:2]) != data[2]:
            raise ValueError('Si7021 CRC mismatch')
        return (data[0] << 8) | data[1]

    @staticmethod
    def _to_humidity(code):
        rh = 125.0 * code / 65536.0 - 6.0
        return min(100.0, max(0.0, rh))

    @staticmethod
    def _to_temperature(code):
        return 175.72 * code / 65536.0 - 46.85

    def _previous_code(self):
        self._command(self._CMD_T_PREVIOUS)
        data = bytes(self._connection.read(2))
        return (data[0] << 8) | data[1]

    def read(self):
        """Run one RH measurement and read the temperature of the same conversion.

        Returns:
            tuple: (temperature_c, humidity_pct) — degrees Celsius and %RH (0-100).

        Raises:
            ValueError: If the RH result fails its CRC check.
            OSError: If the measurement does not complete within 50 ms.
        """
        rh = self._measure(self._CMD_RH_HOLD, self._CMD_RH_POLL)
        return (self._to_temperature(self._previous_code()), self._to_humidity(rh))


class Si7021Full(Si7021Minimal):
    """Si7021 full interface — resolution, heater, hold master, ID and dew point.

    Extends Si7021Minimal with single values and raw codes, the four resolution
    pairs, the on-chip heater, the raw user register, clock stretching (hold
    master), software reset, electronic serial number, firmware revision,
    conversion time and the dew point.

    Args:
        connection: Configured I²C connection pointing at the device (address 0x40).
    """

    _RESERVED = 0x3A
    _HTRE = 0x04
    _VDDS = 0x40
    # (rh_bits, temperature_bits) -> RES1:RES0
    _RESOLUTIONS = {(12, 14): 0, (8, 12): 1, (10, 13): 2, (11, 11): 3}
    # typical conversion times in seconds
    _RH_TIME = {12: 0.010, 8: 0.0026, 10: 0.0037, 11: 0.0058}
    _T_TIME = {14: 0.007, 12: 0.0024, 13: 0.004, 11: 0.0015}

    def __init__(self, connection):
        super().__init__(connection)

    def read_raw_humidity(self):
        """Run one RH measurement and return the raw 16-bit code (status bits included).

        Returns:
            int: Raw RH code, 0-65535.
        """
        return self._measure(self._CMD_RH_HOLD, self._CMD_RH_POLL)

    def read_raw_temperature(self):
        """Run one temperature measurement and return the raw 16-bit code.

        Returns:
            int: Raw temperature code, 0-65535.
        """
        return self._measure(self._CMD_T_HOLD, self._CMD_T_POLL)

    def read_humidity(self):
        """Run one RH measurement.

        Returns:
            float: Relative humidity in %RH, clamped to 0-100.
        """
        return self._to_humidity(self.read_raw_humidity())

    def read_temperature(self):
        """Run a fresh temperature measurement (0xF3, or 0xE3 with clock stretching).

        Returns:
            float: Temperature in degrees Celsius.
        """
        return self._to_temperature(self.read_raw_temperature())

    def read_temperature_previous(self):
        """Read the temperature of the last RH measurement (0xE0, no new conversion, no CRC).

        Returns:
            float: Temperature in degrees Celsius.
        """
        return self._to_temperature(self._previous_code())

    def read_user_register(self):
        """Read USER_REG_1 (command 0xE7).

        Returns:
            int: Raw register value (0x3A after reset).
        """
        return self._connection.write_read(bytes([self._CMD_READ_USER]), 1)[0]

    def write_user_register(self, value):
        """Write USER_REG_1 (command 0xE6); the reserved bits keep their current value.

        Args:
            value: New register value; bits 5:3 and 1 (reserved) and bit 6 (VDDS) are ignored.
        """
        current = self.read_user_register()
        merged = (current & (self._RESERVED | self._VDDS)) | (value & ~(self._RESERVED | self._VDDS) & 0xFF)
        self._connection.write(bytes([self._CMD_WRITE_USER, merged]))

    def set_resolution(self, rh_bits, temperature_bits):
        """Select the measurement resolution (read-modify-write of RES1:RES0).

        Args:
            rh_bits: RH resolution in bits.
            temperature_bits: Temperature resolution in bits; allowed pairs are
                (12, 14), (8, 12), (10, 13) and (11, 11).

        Raises:
            ValueError: If the pair is not one of the four allowed ones.
        """
        res = self._RESOLUTIONS.get((rh_bits, temperature_bits))
        if res is None:
            raise ValueError('resolution must be (12, 14), (8, 12), (10, 13) or (11, 11)')
        bits = ((res >> 1) << 7) | (res & 1)
        self.write_user_register((self.read_user_register() & ~0x81 & 0xFF) | bits)

    def get_resolution(self):
        """Return the configured resolution.

        Returns:
            tuple: (rh_bits, temperature_bits).
        """
        reg = self.read_user_register()
        res = (((reg >> 7) & 1) << 1) | (reg & 1)
        for pair, code in self._RESOLUTIONS.items():
            if code == res:
                return pair

    def set_heater(self, on):
        """Switch the on-chip heater (HTRE, about 3.1 mA) on or off.

        Args:
            on: True for on, False for off.
        """
        reg = self.read_user_register()
        self.write_user_register((reg | self._HTRE) if on else (reg & ~self._HTRE & 0xFF))

    def is_heater_on(self):
        """Return True if the on-chip heater is enabled."""
        return bool(self.read_user_register() & self._HTRE)

    def vdd_low(self):
        """Return True if the supply voltage is low (VDDS set, VDD below about 1.9 V)."""
        return bool(self.read_user_register() & self._VDDS)

    def set_clock_stretching(self, on):
        """Use the hold-master commands (0xE5 / 0xE3) instead of polling.

        The bus layer must support clock stretching.

        Args:
            on: True for hold master, False for the polling (NACK) variant.
        """
        self._stretch = bool(on)

    def software_reset(self):
        """Software reset (0xFE) and wait 15 ms; the user register returns to 0x3A."""
        self._command(self._CMD_RESET)
        time.sleep(0.015)

    def serial_number(self):
        """Read the 64-bit electronic serial number (SNA_3..SNA_0, SNB_3..SNB_0).

        Returns:
            int: 64-bit serial number.
        """
        a = bytes(self._connection.write_read(bytes([0xFA, 0x0F]), 8))
        b = bytes(self._connection.write_read(bytes([0xFC, 0xC9]), 6))
        parts = bytes([a[0], a[2], a[4], a[6], b[0], b[1], b[3], b[4]])
        return int.from_bytes(parts, 'big')

    def device_type(self):
        """Read SNB_3, the device type byte.

        Returns:
            int: 0x15 for the Si7021 (0x14 Si7020, 0x0D Si7013).
        """
        return bytes(self._connection.write_read(bytes([0xFC, 0xC9]), 6))[0]

    def firmware_revision(self):
        """Read the firmware revision (command 0x84 0xB8).

        Returns:
            int: 0xFF for firmware 1.0.
        """
        return self._connection.write_read(bytes([0x84, 0xB8]), 1)[0]

    def conversion_time(self):
        """Typical RH + temperature conversion time at the current resolution.

        Returns:
            float: Time in seconds.
        """
        rh_bits, t_bits = self.get_resolution()
        return self._RH_TIME[rh_bits] + self._T_TIME[t_bits]

    def dew_point(self):
        """Run one measurement and compute the dew point (Magnus formula).

        Returns:
            float: Dew point in degrees Celsius.
        """
        t, rh = self.read()
        if rh <= 0.0:
            rh = 0.01
        gamma = math.log(rh / 100.0) + (17.62 * t) / (243.12 + t)
        return 243.12 * gamma / (17.62 - gamma)
