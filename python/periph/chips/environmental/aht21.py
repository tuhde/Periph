import time


class AHT21Minimal:
    """AHT21 temperature and humidity sensor — minimal interface.

    Provides temperature and humidity readings with no configuration beyond
    the transport. Handles power-on initialization, calibration check, and
    measurement triggering automatically.

    Default configuration (baked in at construction):
        - Measurement triggered on every read() call (no continuous mode)
        - 80 ms fixed wait after trigger (no busy-polling)
        - No CRC verification (reduces complexity; CRC check is Full-only)

    Args:
        transport: Configured I²C transport pointing at the device (address 0x38).
    """

    _CMD_TRIGGER = b'\xAC\x33\x00'
    _CMD_SOFT_RESET = b'\xBA'
    _CMD_CAL_INIT_1 = b'\x1B\x00\x00'
    _CMD_CAL_INIT_2 = b'\x1C\x00\x00'
    _CMD_CAL_INIT_3 = b'\x1E\x00\x00'

    _STATUS_BUSY = 0x80
    _STATUS_CAL = 0x08

    def __init__(self, transport):
        self._transport = transport
        time.sleep(0.1)
        status = self._read_status()
        if (status & 0x18) != 0x18:
            self._transport.write(self._CMD_SOFT_RESET)
            time.sleep(0.02)
            status = self._read_status()
            if (status & 0x18) != 0x18:
                self._transport.write(self._CMD_CAL_INIT_1)
                time.sleep(0.01)
                self._transport.write(self._CMD_CAL_INIT_2)
                time.sleep(0.01)
                self._transport.write(self._CMD_CAL_INIT_3)
                time.sleep(0.01)

    def _read_status(self):
        return self._transport.read(1)[0]

    def read(self):
        """Trigger a measurement and return temperature and humidity.

        Sends the trigger command, waits 80 ms, reads 6 bytes, and decodes
        the raw 20-bit values into physical units.

        Returns:
            dict: {'temperature_c': float, 'humidity_pct': float}
                temperature_c: Temperature in degrees Celsius (-50 to 150 °C).
                humidity_pct: Relative humidity in percent (0 to 100 %RH).
        """
        self._transport.write(self._CMD_TRIGGER)
        time.sleep(0.08)
        data = self._transport.read(6)
        raw_rh = (data[1] << 12) | (data[2] << 4) | (data[3] >> 4)
        raw_t = ((data[3] & 0x0F) << 16) | (data[4] << 8) | data[5]
        humidity_pct = (raw_rh / 1048576.0) * 100.0
        temperature_c = (raw_t / 1048576.0) * 200.0 - 50.0
        return {'temperature_c': temperature_c, 'humidity_pct': humidity_pct}


class AHT21Full(AHT21Minimal):
    """AHT21 full interface — extends AHT21Minimal with CRC and status support.

    Adds CRC-8 verification, explicit soft reset, calibration status inspection,
    and individual temperature/humidity readings.

    Args:
        transport: Configured I²C transport pointing at the device (address 0x38).
    """

    def __init__(self, transport):
        super().__init__(transport)

    def read_temperature(self):
        """Trigger a measurement and return temperature only.

        Returns:
            float: Temperature in degrees Celsius (-50 to 150 °C).
        """
        return self.read()['temperature_c']

    def read_humidity(self):
        """Trigger a measurement and return humidity only.

        Returns:
            float: Relative humidity in percent (0 to 100 %RH).
        """
        return self.read()['humidity_pct']

    def read_with_crc(self):
        """Trigger a measurement, read 7 bytes, and verify CRC-8.

        Uses polynomial x^8 + x^5 + x^4 + 1 (0x31) with initial value 0xFF
        to verify the CRC byte against bytes 0–5 of the response.

        Returns:
            dict: {'temperature_c': float, 'humidity_pct': float, 'crc_ok': bool}
                temperature_c: Temperature in degrees Celsius.
                humidity_pct: Relative humidity in percent.
                crc_ok: True if CRC-8 verification passed.
        """
        self._transport.write(self._CMD_TRIGGER)
        time.sleep(0.08)
        data = self._transport.read(7)
        raw_rh = (data[1] << 12) | (data[2] << 4) | (data[3] >> 4)
        raw_t = ((data[3] & 0x0F) << 16) | (data[4] << 8) | data[5]
        humidity_pct = (raw_rh / 1048576.0) * 100.0
        temperature_c = (raw_t / 1048576.0) * 200.0 - 50.0
        crc_ok = self._crc8(data[0:6]) == data[6]
        return {'temperature_c': temperature_c, 'humidity_pct': humidity_pct, 'crc_ok': crc_ok}

    def soft_reset(self):
        """Send the soft reset command and wait 20 ms for recovery."""
        self._transport.write(self._CMD_SOFT_RESET)
        time.sleep(0.02)

    def is_calibrated(self):
        """Check if the calibration bit is set in the status byte.

        Returns:
            bool: True if the sensor reports calibration enabled.
        """
        return bool(self._read_status() & self._STATUS_CAL)

    def is_busy(self):
        """Check if the busy bit is set in the status byte.

        Returns:
            bool: True if a measurement is in progress.
        """
        return bool(self._read_status() & self._STATUS_BUSY)

    def _crc8(self, data):
        crc = 0xFF
        for byte in data:
            crc ^= byte
            for _ in range(8):
                if crc & 0x80:
                    crc = ((crc << 1) ^ 0x31) & 0xFF
                else:
                    crc = (crc << 1) & 0xFF
        return crc
