"""ADS1114 — 16-bit ΔΣ ADC, one differential input, PGA and comparator (Texas Instruments).

AIN0 − AIN1 (wire AIN1 to GND for single-ended use), programmable gain
(±6.144 V … ±0.256 V), data rates of 8–860 SPS, single-shot or continuous
conversion, and a programmable digital comparator on the ALERT/RDY pin that
can also signal conversion-ready. Four addresses (0x48-0x4B) via the ADDR
strap. The same driver file is used on MicroPython, CircuitPython and Linux
hosts.

Registers are 16-bit big-endian behind a write-only Address Pointer; the chip
has no ID register. Implementation shared with ADS1113/ADS1115 via
``_ads111x_base``.

Args:
    connection: RegisterConnection (I²C or SMBus) pointing at the device
        (0x48-0x4B, per the ADDR strapping). Its optional int_pin receives
        ALERT/RDY.
"""

from ._ads111x_base import (
    _ADS111xBase, _RateMixin, _GainMixin, _ComparatorMixin, I2C_ADDRESS,
    SOURCE_CONVERSION_READY, SOURCE_COMPARATOR, _reset_registers)


class ADS1114Minimal(_ADS111xBase):
    """ADS1114 minimal interface — single-shot AIN0 − AIN1 at ±2.048 V, 128 SPS.

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
        """Run a single-shot conversion and return volts (raw × 62.5 µV at the default range).

        Returns:
            float: Voltage AIN0 − AIN1 in V.
        """
        return self.read_raw() * 0.0000625


class ADS1114Full(_GainMixin, _RateMixin, _ComparatorMixin, ADS1114Minimal):
    """ADS1114 full interface — adds gain, data rate, continuous conversion,
    comparator, thresholds, reset and the ALERT/RDY interrupt API.

    Args:
        connection: RegisterConnection pointing at the device.
    """

    def __init__(self, connection):
        super().__init__(connection)
        self._init_comparator()

    def read_voltage(self):
        """Run a single-shot conversion and return volts at the current gain."""
        return self.read_raw() * self.lsb()

    def start_continuous(self):
        """Start continuous conversion at the current data rate (MODE ← 0)."""
        self._start_continuous(0)

    def reset(self):
        """Write the reset values back to CONFIG, LO_THRESH and HI_THRESH and
        clear cached settings. Not an I²C general-call reset."""
        _reset_registers(self, True)
