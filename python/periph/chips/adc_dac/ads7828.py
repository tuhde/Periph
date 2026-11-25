"""ADS7828 — 12-bit, 8-channel sampling ADC with internal 2.5 V reference (TI / Burr-Brown).

The ADS7828 has no registers: one command byte selects the input mode
(single-ended or differential), the channel and the power-down state, and
starts a conversion. The 12-bit result is read back as two bytes. The I²C
address is 0x48–0x4B (A1/A0 tied to GND or VDD). The same driver file is
used on MicroPython, CircuitPython, and Linux hosts.

The command byte is the "register": ``connection.read_reg(command, 2)`` is
exactly the datasheet's write-command / repeated-START / read-two-bytes
sequence, so the connection must be a RegisterConnection (e.g. the I²C
connection from ``periph.connection.i2c_auto``).

Args:
    connection: Connection (I²C) pointing at the device (0x48–0x4B).
"""

import time

I2C_ADDRESS = 0x48

_INTERNAL_VREF = 2.5

# PD1:PD0 power-down modes.
_PD_ALL_DOWN = 0   # power down between conversions (internal reference off)
_PD_REF_OFF_ADC_ON = 1
_PD_REF_ON_ADC_OFF = 2
_PD_REF_ON_ADC_ON = 3

# Lone command that switches the internal reference on (SD=0, C=0, PD=10).
_CMD_REF_ON = 0x08
# Lone command that powers everything down (SD=0, C=0, PD=00).
_CMD_POWER_DOWN = 0x00

# Wait after enabling the internal reference (conservative; datasheet gives only a plot).
_REF_SETTLE_S = 0.001


def _single_ended_code(channel):
    """C2:C0 for single-ended channel n — the encoding is interleaved."""
    return ((channel & 1) << 2) | (channel >> 1)


class ADS7828Minimal:
    """ADS7828 12-bit ADC — minimal interface.

    Reads the eight single-ended inputs (CHn against COM) as raw codes or
    voltages. Defaults to the internal 2.5 V reference (LSB 610 µV, full
    scale 0 … 2.5 V), which is kept on between conversions.

    Args:
        connection: Connection (I²C) pointing at the device (0x48–0x4B).
        vref: ``None`` to use the internal 2.5 V reference (REFIN/REFOUT
            decoupled with 0.1 µF, nothing else driving it), or the external
            reference voltage in volts (0.05 V … VDD) with the internal
            reference off.
    """

    def __init__(self, connection, vref=None):
        """Initialize ADS7828Minimal; enables the internal reference when selected.

        Args:
            connection: Connection (I²C) pointing at the device.
            vref: None for the internal 2.5 V reference, or an external
                reference voltage in volts.
        """
        self._connection = connection
        self._ref_on = False
        self._select_reference(vref)

    def _select_reference(self, vref):
        if vref is None:
            self._internal = True
            self._vref = _INTERNAL_VREF
            self._pd = _PD_REF_ON_ADC_ON
            self._enable_reference()
        else:
            self._internal = False
            self._vref = float(vref)
            self._pd = _PD_ALL_DOWN
            self._ref_on = False

    def _enable_reference(self):
        self._connection.write(bytes([_CMD_REF_ON]))
        time.sleep(_REF_SETTLE_S)
        self._ref_on = True

    def _convert(self, single_ended, code):
        """Send a command byte and return the 12-bit result."""
        if self._internal and not self._ref_on:
            self._enable_reference()
        command = ((1 if single_ended else 0) << 7) | (code << 4) | (self._pd << 2)
        data = self._connection.read_reg(command, 2)
        return ((data[0] & 0x0F) << 8) | data[1]

    def read_raw(self, channel):
        """Read a single-ended channel (CHn against COM) as a 12-bit code.

        Args:
            channel: Channel number 0–7. Clamped to the valid range.

        Returns:
            int: Raw 12-bit code (0–4095).
        """
        channel = max(0, min(7, int(channel)))
        return self._convert(True, _single_ended_code(channel))

    def read_voltage(self, channel):
        """Read a single-ended channel as a voltage.

        Args:
            channel: Channel number 0–7. Clamped to the valid range.

        Returns:
            float: Input voltage in volts (raw / 4096 × vref).
        """
        return self.read_raw(channel) / 4096.0 * self._vref

    def read_all(self):
        """Read all eight single-ended channels, one conversion each.

        Returns:
            list[float]: Voltages of CH0…CH7 in volts.
        """
        return [self.read_voltage(ch) for ch in range(8)]


class ADS7828Full(ADS7828Minimal):
    """ADS7828 full interface — extends ADS7828Minimal.

    Adds differential reads (both polarities), a signed differential read,
    run-time reference switching, power-down control and multi-channel scans.

    Args:
        connection: Connection (I²C) pointing at the device (0x48–0x4B).
        vref: None for the internal 2.5 V reference, or an external reference
            voltage in volts.
    """

    POWER_DOWN_BETWEEN = _PD_ALL_DOWN       # PD=00: power down between conversions
    REF_OFF_ADC_ON     = _PD_REF_OFF_ADC_ON  # PD=01: internal reference off, ADC on
    REF_ON_ADC_OFF     = _PD_REF_ON_ADC_OFF  # PD=10: internal reference on, ADC off
    REF_ON_ADC_ON      = _PD_REF_ON_ADC_ON   # PD=11: internal reference on, ADC on

    def __init__(self, connection, vref=None):
        """Initialize ADS7828Full.

        Args:
            connection: Connection (I²C) pointing at the device.
            vref: None for the internal reference, or an external voltage in volts.
        """
        super().__init__(connection, vref)

    def read_differential_raw(self, pair, inverted=False):
        """Read a differential pair as a 12-bit code.

        Args:
            pair: Pair index 0–3 (CH0/CH1, CH2/CH3, CH4/CH5, CH6/CH7). Clamped.
            inverted: If True the positive input is CH(2·pair+1) instead of CH(2·pair).

        Returns:
            int: Raw 12-bit code (0–4095); a negative differential voltage reads 0.
        """
        pair = max(0, min(3, int(pair)))
        code = (4 | pair) if inverted else pair
        return self._convert(False, code)

    def read_differential(self, pair, inverted=False):
        """Read a differential pair as a voltage.

        Args:
            pair: Pair index 0–3. Clamped.
            inverted: If True the positive input is CH(2·pair+1).

        Returns:
            float: Differential voltage in volts (≥ 0).
        """
        return self.read_differential_raw(pair, inverted) / 4096.0 * self._vref

    def read_signed_differential(self, pair):
        """Read V(CH2p) − V(CH2p+1) with sign, using two conversions.

        Args:
            pair: Pair index 0–3. Clamped.

        Returns:
            float: Signed differential voltage in volts (−vref … +vref).
        """
        normal = self.read_differential_raw(pair, False)
        inverted = self.read_differential_raw(pair, True)
        return (normal - inverted) / 4096.0 * self._vref

    def set_reference(self, vref):
        """Switch between the internal and an external reference.

        Args:
            vref: None for the internal 2.5 V reference (re-runs the turn-on
                and settle wait), or an external reference voltage in volts
                (PD1 = 0 from then on).
        """
        self._select_reference(vref)

    def get_reference(self):
        """Return the reference voltage used for scaling.

        Returns:
            float: Reference voltage in volts (2.5 for the internal reference).
        """
        return self._vref

    def uses_internal_reference(self):
        """Return True while the internal reference is selected.

        Returns:
            bool: True for the internal reference, False for an external one.
        """
        return self._internal

    def set_power_mode(self, pd):
        """Set the PD bits used in all following conversion commands.

        Args:
            pd: 0 = POWER_DOWN_BETWEEN, 1 = REF_OFF_ADC_ON, 2 = REF_ON_ADC_OFF,
                3 = REF_ON_ADC_ON.

        Raises:
            ValueError: If pd is outside 0–3, or if the internal reference is
                selected and pd has PD1 = 0 (that would turn the reference off).
        """
        pd = int(pd)
        if pd < 0 or pd > 3:
            raise ValueError("pd must be 0-3")
        if self._internal and not (pd & 2):
            raise ValueError("internal reference requires PD1 = 1")
        self._pd = pd

    def power_down(self):
        """Power the chip down completely (≈ 400 nA).

        Sends a lone command with PD = 00. With the internal reference this
        turns it off; the next read re-runs the turn-on and settle wait.
        """
        self._connection.write(bytes([_CMD_POWER_DOWN]))
        self._ref_on = False

    def read_scan(self, channels):
        """Read several single-ended channels in order as raw codes.

        Args:
            channels: Iterable of channel numbers 0–7.

        Returns:
            list[int]: Raw 12-bit codes, one conversion per channel.
        """
        return [self.read_raw(ch) for ch in channels]

    def lsb(self):
        """Return the size of one code step.

        Returns:
            float: vref / 4096 in volts (610 µV at 2.5 V).
        """
        return self._vref / 4096.0
