"""ADS1013 — 12-bit I²C ADC, fixed ±2.048 V range (Texas Instruments).

Smallest member of the ADS101x family: one input (AIN0 − AIN1), internal
reference and oscillator, a fixed ±2.048 V full-scale range (no PGA, no
multiplexer, no comparator, no ALERT/RDY pin), data rates of 128–3300 SPS and
single-shot or continuous conversion. The chip has no ID register. Four
selectable addresses (0x48–0x4B) via the ADDR strap. The same driver file is
used on MicroPython, CircuitPython, and Linux hosts.

Registers are 16-bit, big-endian, addressed through a persistent Address
Pointer. Config writes are always whole-word (never read-modify-write).

Args:
    connection: RegisterConnection (I²C or SMBus) pointing at the device
        (0x48–0x4B, per the board's ADDR strapping).
"""

import time

from periph.connection.register import to_signed


I2C_ADDRESS = 0x48

# Data-rate codes (DR[2:0]).
DR_128 = 0
DR_250 = 1
DR_490 = 2
DR_920 = 3
DR_1600 = 4
DR_2400 = 5
DR_3300 = 6

_SPS = (128, 250, 490, 920, 1600, 2400, 3300)

_REG_CONVERSION = 0x00
_REG_CONFIG = 0x01

_CONFIG_RESET = 0x8583

_OS = 0x8000
_MODE_SINGLE = 0x0100
# Reserved bits written at their reset values: 000010 in 14:9, 00011 in 4:0.
_RESERVED = 0x0403

_LSB = 0.001


class ADS1013Minimal:
    """ADS1013 12-bit ADC — minimal interface.

    Reads AIN0 − AIN1 with a single-shot conversion at 1600 SPS. The full
    Config word 0x8583 is written for every conversion. No I²C traffic at
    construction.

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.
    """

    def __init__(self, connection):
        self._connection = connection
        self._dr = DR_1600
        self._continuous = False

    def _read_reg(self, reg):
        data = self._connection.read_reg(reg, 2)
        return (data[0] << 8) | data[1]

    def _write_reg(self, reg, value):
        self._connection.write_reg(reg, bytes([(value >> 8) & 0xFF, value & 0xFF]))

    def _config_word(self, os_bit, continuous):
        word = _RESERVED | (self._dr << 5)
        if os_bit:
            word |= _OS
        if not continuous:
            word |= _MODE_SINGLE
        return word

    def _read_conversion(self):
        return to_signed(self._read_reg(_REG_CONVERSION), 16) >> 4

    def read_raw(self):
        """Run one single-shot conversion of AIN0 − AIN1.

        Writes the whole Config word with OS = 1, polls OS until the chip is
        idle again, then reads CONVERSION.

        Returns:
            int: 12-bit two's-complement code, −2048…2047.
        """
        self._write_reg(_REG_CONFIG, self._config_word(True, False))
        self._continuous = False
        for _ in range(100):
            if self._read_reg(_REG_CONFIG) & _OS:
                break
            time.sleep(0.0005)
        return self._read_conversion()

    def read_voltage(self):
        """Run one single-shot conversion and convert it to volts.

        Returns:
            float: Voltage AIN0 − AIN1 in volts (raw × 0.001, ±2.048 V range).
        """
        return self.read_raw() * _LSB


class ADS1013Full(ADS1013Minimal):
    """ADS1013 full interface — extends Minimal with data-rate selection and
    continuous conversion.

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.
    """

    DR_128 = DR_128
    DR_250 = DR_250
    DR_490 = DR_490
    DR_920 = DR_920
    DR_1600 = DR_1600
    DR_2400 = DR_2400
    DR_3300 = DR_3300

    def _write_config(self):
        self._write_reg(_REG_CONFIG, self._config_word(False, self._continuous))

    def full_scale(self):
        """Return the fixed full-scale range.

        Returns:
            float: Always 2.048 (volts).
        """
        return 2.048

    def lsb(self):
        """Return the size of one conversion code.

        Returns:
            float: Always 0.001 (volts).
        """
        return _LSB

    def set_data_rate(self, dr):
        """Select the conversion data rate.

        Args:
            dr: DR_128 (0) … DR_3300 (6).

        Raises:
            ValueError: If dr is outside 0–6 (7 aliases 3300 SPS).
        """
        if dr < 0 or dr > DR_3300:
            raise ValueError('dr must be 0-6')
        self._dr = dr
        if self._continuous:
            self._write_config()

    def get_data_rate(self):
        """Return the configured data-rate code.

        Returns:
            int: DR code 0–6.
        """
        return self._dr

    def data_rate_sps(self):
        """Return the current data rate in samples per second.

        Returns:
            int: 128, 250, 490, 920, 1600, 2400 or 3300.
        """
        return _SPS[self._dr]

    def start_continuous(self):
        """Start continuous conversion (MODE = 0) at the current data rate."""
        self._continuous = True
        self._write_config()

    def read_continuous_raw(self):
        """Read the most recent completed result without touching Config.

        Valid at least 1/DR after start_continuous().

        Returns:
            int: 12-bit two's-complement code, −2048…2047.
        """
        return self._read_conversion()

    def read_continuous(self):
        """Read the most recent completed result in volts.

        Returns:
            float: read_continuous_raw() × lsb() in volts.
        """
        return self._read_conversion() * _LSB

    def stop_continuous(self):
        """Stop continuous conversion (MODE = 1, single-shot / power-down)."""
        self._continuous = False
        self._write_config()

    def is_converting(self):
        """Report whether a single-shot conversion is running (OS = 0).

        Only meaningful in single-shot mode; in continuous mode OS reads 1.

        Returns:
            bool: True while converting.
        """
        return not (self._read_reg(_REG_CONFIG) & _OS)

    def read_config(self):
        """Read the raw CONFIG register.

        Returns:
            int: 16-bit CONFIG value.
        """
        return self._read_reg(_REG_CONFIG)

    def reset(self):
        """Write CONFIG = 0x8583 back and clear the cached settings.

        This is not an I²C general-call reset.
        """
        self._write_reg(_REG_CONFIG, _CONFIG_RESET)
        self._dr = DR_1600
        self._continuous = False
