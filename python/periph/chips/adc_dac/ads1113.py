"""ADS1113 — 16-bit ΔΣ ADC, one differential input, fixed ±2.048 V (Texas Instruments).

The smallest member of the ADS111x family: AIN0 − AIN1 only (wire AIN1 to GND
for single-ended use), fixed ±2.048 V range (62.5 µV per LSB), data rates of
8–860 SPS, single-shot or continuous conversion, no comparator and no
ALERT/RDY output. Four addresses (0x48-0x4B) via the ADDR strap. The same
driver file is used on MicroPython, CircuitPython and Linux hosts.

Registers are 16-bit big-endian behind a write-only Address Pointer; the chip
has no ID register. Implementation shared with ADS1114/ADS1115 via
``_ads111x_base``.

Args:
    connection: RegisterConnection (I²C or SMBus) pointing at the device
        (0x48-0x4B, per the ADDR strapping).
"""

from ._ads111x_base import (
    _ADS111xBase, _RateMixin, I2C_ADDRESS, _REG_CONFIG, _CONFIG_RESET)


class ADS1113Minimal(_ADS111xBase):
    """ADS1113 minimal interface — single-shot AIN0 − AIN1 at ±2.048 V, 128 SPS.

    Args:
        connection: RegisterConnection pointing at the device. No I²C traffic
            happens at construction.
    """

    def read_raw(self):
        """Run a single-shot conversion of AIN0 − AIN1.

        Returns:
            int: Signed 16-bit code (−32768…32767).
        """
        return self._convert(0)

    def read_voltage(self):
        """Run a single-shot conversion and return volts (raw × 62.5 µV).

        Returns:
            float: Voltage AIN0 − AIN1 in V.
        """
        return self.read_raw() * 0.0000625


class ADS1113Full(_RateMixin, ADS1113Minimal):
    """ADS1113 full interface — adds data rate, continuous conversion, status
    and reset to Minimal.

    Args:
        connection: RegisterConnection pointing at the device.
    """

    def full_scale(self):
        """Return the fixed full-scale range, 2.048 V."""
        return 2.048

    def lsb(self):
        """Return the fixed code size, 62.5 µV."""
        return 0.0000625

    def start_continuous(self):
        """Start continuous conversion at the current data rate (MODE ← 0)."""
        self._start_continuous(0)

    def reset(self):
        """Write the reset value back to CONFIG (0x8583) and clear cached settings.

        Not an I²C general-call reset.
        """
        self._write_reg(_REG_CONFIG, _CONFIG_RESET)
        _ADS111xBase.__init__(self, self._connection)
