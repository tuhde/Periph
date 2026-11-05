"""ADS1115 — 16-bit ΔΣ ADC, 4-channel multiplexer, PGA and comparator (Texas Instruments).

Four single-ended or two differential inputs (plus AIN0/AIN1 against AIN3),
programmable gain (±6.144 V … ±0.256 V), data rates of 8–860 SPS, single-shot
or continuous conversion, and a programmable digital comparator on the
ALERT/RDY pin that can also signal conversion-ready. Four addresses
(0x48-0x4B) via the ADDR strap. The same driver file is used on MicroPython,
CircuitPython and Linux hosts.

Registers are 16-bit big-endian behind a write-only Address Pointer; the chip
has no ID register. Implementation shared with ADS1113/ADS1114 via
``_ads111x_base``.

Args:
    connection: RegisterConnection (I²C or SMBus) pointing at the device
        (0x48-0x4B, per the ADDR strapping). Its optional int_pin receives
        ALERT/RDY.
"""

from ._ads111x_base import (
    _ADS111xBase, _RateMixin, _GainMixin, _ComparatorMixin, I2C_ADDRESS,
    SOURCE_CONVERSION_READY, SOURCE_COMPARATOR, _reset_registers)


class ADS1115Minimal(_ADS111xBase):
    """ADS1115 minimal interface — single-shot single-ended reads at ±2.048 V, 128 SPS.

    Args:
        connection: RegisterConnection pointing at the device. No I²C traffic
            happens at construction.
    """

    def read_raw(self, channel=0):
        """Run a single-shot conversion of AIN<channel> against GND.

        Args:
            channel: Input 0–3 (clamped).

        Returns:
            int: Signed 16-bit code (−32768…32767).
        """
        channel = max(0, min(3, channel))
        return self._convert(4 + channel)

    def read_voltage(self, channel=0):
        """Run a single-shot conversion and return volts (raw × 62.5 µV at the default range).

        Args:
            channel: Input 0–3 (clamped).

        Returns:
            float: Voltage in V.
        """
        return self.read_raw(channel) * 0.0000625


class ADS1115Full(_GainMixin, _RateMixin, _ComparatorMixin, ADS1115Minimal):
    """ADS1115 full interface — adds gain, data rate, differential reads,
    continuous conversion, comparator, thresholds, reset and the ALERT/RDY
    interrupt API.

    Args:
        connection: RegisterConnection pointing at the device.
    """

    DIFF_0_1 = 0
    DIFF_0_3 = 1
    DIFF_1_3 = 2
    DIFF_2_3 = 3

    MUX_AIN0_AIN1 = 0
    MUX_AIN0_AIN3 = 1
    MUX_AIN1_AIN3 = 2
    MUX_AIN2_AIN3 = 3
    MUX_AIN0_GND  = 4
    MUX_AIN1_GND  = 5
    MUX_AIN2_GND  = 6
    MUX_AIN3_GND  = 7

    def __init__(self, connection):
        super().__init__(connection)
        self._init_comparator()

    def read_voltage(self, channel=0):
        """Run a single-shot conversion of AIN<channel> and return volts at the current gain."""
        return self.read_raw(channel) * self.lsb()

    def read_differential_raw(self, pair):
        """Run a single-shot signed differential conversion.

        Args:
            pair: DIFF_0_1, DIFF_0_3, DIFF_1_3 or DIFF_2_3.

        Returns:
            int: Signed 16-bit code.

        Raises:
            ValueError: If pair is outside 0–3.
        """
        if not 0 <= pair <= 3:
            raise ValueError('pair must be 0-3')
        return self._convert(pair)

    def read_differential(self, pair):
        """Run a single-shot differential conversion and return volts."""
        return self.read_differential_raw(pair) * self.lsb()

    def start_continuous(self, mux=MUX_AIN0_GND):
        """Start continuous conversion on any MUX code (MODE ← 0).

        Args:
            mux: MUX_AIN0_GND … MUX_AIN3_GND or a differential MUX_* code (0–7).

        Raises:
            ValueError: If mux is outside 0–7.
        """
        if not 0 <= mux <= 7:
            raise ValueError('mux must be 0-7')
        self._start_continuous(mux)

    def reset(self):
        """Write the reset values back to CONFIG, LO_THRESH and HI_THRESH and
        clear cached settings. Not an I²C general-call reset."""
        _reset_registers(self, True)
