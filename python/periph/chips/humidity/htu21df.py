import math
import time


def _crc8(data):
    """CRC-8 used by the HTU21D-F: polynomial 0x131 (x^8 + x^5 + x^4 + 1), init 0x00, no final XOR."""
    crc = 0x00
    for byte in data:
        crc ^= byte
        for _ in range(8):
            if crc & 0x80:
                crc = ((crc << 1) ^ 0x31) & 0xFF
            else:
                crc = (crc << 1) & 0xFF
    return crc


class HTU21DFMinimal:
    """HTU21D-F humidity and temperature sensor — minimal interface.

    Each read() triggers one temperature and one humidity measurement without hold
    master (0xF3 / 0xF5), polls the NACKed read header until the chip ACKs (timeout
    100 ms) and verifies the CRC of both results (one retry on a mismatch).

    Default configuration (power-on): 12-bit RH / 14-bit temperature, heater off,
    no hold master, CRC checked. The constructor does not touch the bus; init()
    performs the soft reset.

    Args:
        connection: Configured I²C connection pointing at the device (address 0x40).
    """

    _CMD_T_HOLD = 0xE3
    _CMD_RH_HOLD = 0xE5
    _CMD_T_POLL = 0xF3
    _CMD_RH_POLL = 0xF5
    _CMD_WRITE_USER = 0xE6
    _CMD_READ_USER = 0xE7
    _CMD_RESET = 0xFE
    _POLL_ATTEMPTS = 100

    def __init__(self, connection):
        self._connection = connection
        self._hold = False

    def init(self):
        """Wait 15 ms for power-up, soft reset (0xFE) and wait 15 ms."""
        time.sleep(0.015)
        self._connection.write(bytes([self._CMD_RESET]))
        time.sleep(0.015)

    def _measure_once(self, hold_cmd, poll_cmd):
        if self._hold:
            self._connection.write(bytes([hold_cmd]))
            return bytes(self._connection.read(3))
        self._connection.write(bytes([poll_cmd]))
        for _ in range(self._POLL_ATTEMPTS):
            try:
                return bytes(self._connection.read(3))
            except OSError:
                time.sleep(0.001)      # read header NACKed: conversion not done yet
        raise OSError('HTU21D-F measurement did not complete')

    def _measure(self, hold_cmd, poll_cmd):
        """Run one measurement; returns the verified raw 16-bit code (status bits cleared)."""
        for attempt in range(2):
            data = self._measure_once(hold_cmd, poll_cmd)
            if _crc8(data[0:2]) == data[2]:
                return ((data[0] << 8) | data[1]) & 0xFFFC
        raise ValueError('HTU21D-F CRC mismatch')

    @staticmethod
    def _to_humidity(code):
        return -6.0 + 125.0 * code / 65536.0

    @staticmethod
    def _to_temperature(code):
        return -46.85 + 175.72 * code / 65536.0

    def read(self):
        """Measure temperature and humidity.

        Returns:
            tuple: (temperature_c, humidity_pct) — degrees Celsius and %RH.

        Raises:
            ValueError: If a result fails its CRC check twice.
            OSError: If a measurement does not complete within 100 ms.
        """
        t = self._measure(self._CMD_T_HOLD, self._CMD_T_POLL)
        rh = self._measure(self._CMD_RH_HOLD, self._CMD_RH_POLL)
        return (self._to_temperature(t), self._to_humidity(rh))


class HTU21DFFull(HTU21DFMinimal):
    """HTU21D-F full interface — resolution, heater, hold master, compensation, dew point.

    Extends HTU21DFMinimal with single values, raw codes, the four resolution
    settings, the on-chip heater, the end-of-battery flag, the raw user register,
    soft reset, hold-master mode, temperature-compensated humidity and the dew point.

    Args:
        connection: Configured I²C connection pointing at the device (address 0x40).
    """

    _HEATER = 0x04
    _VDD_LOW = 0x40
    _RES_MASK = 0x81

    def __init__(self, connection):
        super().__init__(connection)

    def read_raw(self):
        """Measure temperature and humidity and return the raw codes.

        Returns:
            tuple: (temperature_raw, humidity_raw), 16-bit with the status bits cleared.
        """
        t = self._measure(self._CMD_T_HOLD, self._CMD_T_POLL)
        rh = self._measure(self._CMD_RH_HOLD, self._CMD_RH_POLL)
        return (t, rh)

    def read_temperature(self):
        """Run one temperature measurement.

        Returns:
            float: Temperature in degrees Celsius.
        """
        return self._to_temperature(self._measure(self._CMD_T_HOLD, self._CMD_T_POLL))

    def read_humidity(self):
        """Run one humidity measurement (not clamped; the ASIC range is -6 ... 118 %RH).

        Returns:
            float: Relative humidity in %RH.
        """
        return self._to_humidity(self._measure(self._CMD_RH_HOLD, self._CMD_RH_POLL))

    def read_humidity_compensated(self):
        """Measure and correct the humidity for temperature (-0.15 %RH/°C relative to 25 °C).

        Returns:
            float: Compensated relative humidity in %RH.
        """
        t, rh = self.read()
        return rh + (25.0 - t) * (-0.15)

    def read_user_register(self):
        """Read the user register (command 0xE7).

        Returns:
            int: Raw register value (0x02 after reset).
        """
        return self._connection.write_read(bytes([self._CMD_READ_USER]), 1)[0]

    def _write_user(self, value):
        self._connection.write(bytes([self._CMD_WRITE_USER, value & 0xFF]))

    def set_resolution(self, res):
        """Select the resolution (read-modify-write of the RES bits 7 and 0).

        Args:
            res: 0 = 12-bit RH / 14-bit T, 1 = 8 / 12, 2 = 10 / 13, 3 = 11 / 11.

        Raises:
            ValueError: If res is not 0-3.
        """
        if res not in (0, 1, 2, 3):
            raise ValueError('res must be 0-3')
        bits = ((res >> 1) << 7) | (res & 1)
        self._write_user((self.read_user_register() & ~self._RES_MASK) | bits)

    def get_resolution(self):
        """Return the resolution setting.

        Returns:
            int: 0 = 12/14 bit, 1 = 8/12, 2 = 10/13, 3 = 11/11.
        """
        reg = self.read_user_register()
        return (((reg >> 7) & 1) << 1) | (reg & 1)

    def set_heater(self, on):
        """Switch the on-chip heater on or off (read-modify-write).

        Args:
            on: True for on, False for off.
        """
        reg = self.read_user_register()
        self._write_user((reg | self._HEATER) if on else (reg & ~self._HEATER))

    def heater(self):
        """Return True if the on-chip heater is enabled."""
        return bool(self.read_user_register() & self._HEATER)

    def battery_low(self):
        """Return True if the end-of-battery flag is set (VDD below about 2.25 V)."""
        return bool(self.read_user_register() & self._VDD_LOW)

    def soft_reset(self):
        """Soft reset (0xFE) and wait 15 ms; the heater bit survives."""
        self._connection.write(bytes([self._CMD_RESET]))
        time.sleep(0.015)

    def set_hold_master(self, on):
        """Use the hold-master commands (0xE3 / 0xE5, SCL stretching) instead of polling.

        The bus layer must support clock stretching.

        Args:
            on: True for hold master, False for the polling variant.
        """
        self._hold = bool(on)

    def dew_point(self):
        """Run one measurement and compute the dew point.

        Returns:
            float: Dew point in degrees Celsius.
        """
        t, rh = self.read()
        if rh <= 0.0:
            rh = 0.01
        pp = 10.0 ** (8.1332 - 1762.39 / (t + 235.66))
        return -1762.39 / (math.log10(rh * pp / 100.0) - 8.1332) - 235.66
