"""MCP3422 — 2-channel, 18-bit delta-sigma ADC with PGA and 2.048 V reference (Microchip).

Two differential inputs, an on-board 2.048 V reference, a programmable-gain
amplifier (x1, x2, x4, x8) and an on-board oscillator behind an I²C interface.
The resolution is selected together with the data rate: 12 bit / 240 SPS,
14 bit / 60 SPS, 16 bit / 15 SPS, 18 bit / 3.75 SPS. The full-scale
differential input range is ±2.048 V / gain. The chip converts continuously or
one-shot (then it drops to a 0.3 µA standby). The I²C address bits are
programmed at the factory (0x68 for the standard part, 0x68–0x6F for the
order-code variants). The same driver file is used on MicroPython,
CircuitPython, and Linux hosts.

The chip has no register map: a write is one configuration byte
(``RDY C1 C0 O/C S1 S0 G1 G0``) and a read returns the 2 (12/14/16 bit) or 3
(18 bit) result bytes followed by the current configuration byte, whose RDY bit
(0 = fresh result) says whether the conversion has finished. The driver
therefore uses the plain ``Connection`` ``write`` / ``read`` calls, not
register access.

Args:
    connection: Connection (I²C) pointing at the device (0x68–0x6F).
"""

import time

I2C_ADDRESS = 0x68

_CFG_RDY = 0x80
_CFG_CONT = 0x10

_VREF = 2.048
_BITS_TO_S = {12: 0, 14: 1, 16: 2, 18: 3}
_S_TO_BITS = (12, 14, 16, 18)
_CONV_S = {12: 1.0 / 240, 14: 1.0 / 60, 16: 1.0 / 15, 18: 1.0 / 3.75}   # nominal conversion times
_GAIN_TO_G = {1: 0, 2: 1, 4: 2, 8: 3}

_GC_RESET = bytes([0x00, 0x06])
_GC_CONVERT = bytes([0x00, 0x08])


def _wait(seconds):
    time.sleep(seconds)


class MCP3422Minimal:
    """MCP3422 ADC — minimal interface.

    Reads the two differential inputs as codes or voltages with one one-shot
    conversion per call. Defaults: one-shot mode (lowest power), 16 bit / 15 SPS
    (LSB 62.5 µV), gain x1 (±2.048 V full scale).

    Args:
        connection: Connection (I²C) pointing at the device.
    """

    def __init__(self, connection):
        """Initialize MCP3422Minimal and write the default configuration.

        Writes the configuration byte for one-shot, 16 bit / 15 SPS, gain x1,
        channel 1 with RDY = 0, so nothing is started and the chip goes to
        standby.

        Args:
            connection: Connection (I²C) pointing at the device.
        """
        self._connection = connection
        self._bits = 16
        self._gain = 1
        self._channel = 0
        self._continuous = False
        self._discard = False
        self._connection.write(bytes([self._config(False)]))

    def _config(self, rdy):
        c = (self._channel << 5) | (_BITS_TO_S[self._bits] << 2) | _GAIN_TO_G[self._gain]
        if rdy:
            c |= _CFG_RDY
        if self._continuous:
            c |= _CFG_CONT
        return c

    def _nbytes(self):
        return 3 if self._bits == 18 else 2

    def _decode(self, data):
        n = self._nbytes()
        raw = 0
        for b in data[:n]:
            raw = (raw << 8) | b
        raw &= (1 << self._bits) - 1
        if raw & (1 << (self._bits - 1)):
            raw -= 1 << self._bits
        return raw

    def _wait_ready(self, conversion_s):
        """Read until RDY = 0 and return the signed code; raise TimeoutError after 2x the conversion time + 10 ms."""
        deadline_polls = int((2 * conversion_s + 0.010) / 0.005) + 1
        for _ in range(deadline_polls):
            data = self._connection.read(self._nbytes() + 1)
            if not data[self._nbytes()] & _CFG_RDY:
                return self._decode(data)
            _wait(0.005)
        raise TimeoutError('MCP3422 conversion did not finish')

    def _convert(self, channel):
        self._channel = 0 if channel < 1 else 1
        self._continuous = False
        self._discard = False
        conv = _CONV_S[self._bits]
        self._connection.write(bytes([self._config(True)]))
        _wait(conv * 1.1)
        return self._wait_ready(conv)

    def read_raw(self, channel):
        """Run a one-shot conversion on a channel and return the signed code.

        Args:
            channel: 0 (CH1) or 1 (CH2); values outside 0–1 are clamped.

        Returns:
            int: Signed code, -2^(N-1) … 2^(N-1) - 1 (N = resolution bits).

        Raises:
            TimeoutError: if the conversion does not finish in time.
        """
        return self._convert(channel)

    def read_voltage(self, channel):
        """Run a one-shot conversion on a channel and return the differential voltage.

        Args:
            channel: 0 (CH1) or 1 (CH2); values outside 0–1 are clamped.

        Returns:
            float: ``CHn+ − CHn−`` in volts, ``raw * lsb / gain``.
        """
        return self._convert(channel) * self.lsb() / self._gain

    def read_all(self):
        """Convert both channels, one conversion each.

        Returns:
            list: ``[CH1, CH2]`` voltages in volts.
        """
        return [self.read_voltage(0), self.read_voltage(1)]

    def lsb(self):
        """Size of one code before the gain.

        Returns:
            float: ``2 * 2.048 / 2^bits`` in volts.
        """
        return 2.0 * _VREF / (1 << self._bits)


class MCP3422Full(MCP3422Minimal):
    """MCP3422 full interface — extends Minimal with resolution, gain and channel
    selection, continuous conversion, status reads, scans and general calls.

    The configuration is write-only in the chip's result stream apart from the
    config byte, so the driver tracks resolution, gain, channel and mode.

    Args:
        connection: Connection (I²C) pointing at the device.
    """

    def set_resolution(self, bits):
        """Select the resolution (and with it the data rate).

        Args:
            bits: 12 (240 SPS), 14 (60 SPS), 16 (15 SPS) or 18 (3.75 SPS).

        Raises:
            ValueError: for any other value.
        """
        if bits not in _BITS_TO_S:
            raise ValueError('bits must be 12, 14, 16 or 18')
        self._bits = bits
        self._apply()

    def get_resolution(self):
        """Return the selected resolution.

        Returns:
            int: 12, 14, 16 or 18.
        """
        return self._bits

    def set_gain(self, gain):
        """Select the PGA gain.

        Args:
            gain: 1, 2, 4 or 8.

        Raises:
            ValueError: for any other value.
        """
        if gain not in _GAIN_TO_G:
            raise ValueError('gain must be 1, 2, 4 or 8')
        self._gain = gain
        self._apply()

    def get_gain(self):
        """Return the selected PGA gain.

        Returns:
            int: 1, 2, 4 or 8.
        """
        return self._gain

    def _apply(self):
        """In continuous mode, rewrite the configuration and discard the first sample that follows."""
        if self._continuous:
            self._connection.write(bytes([self._config(False)]))
            self._discard = True

    def set_channel(self, channel):
        """Select the channel used by continuous conversion.

        Args:
            channel: 0 (CH1) or 1 (CH2).
        """
        self._channel = 0 if channel < 1 else 1
        self._apply()

    def start_continuous(self, channel=None):
        """Switch to continuous conversion at the current rate and gain.

        Args:
            channel: Optional channel 0 or 1 to select first.
        """
        if channel is not None:
            self._channel = 0 if channel < 1 else 1
        self._continuous = True
        self._discard = True
        self._connection.write(bytes([self._config(False)]))

    def stop_continuous(self):
        """Return to one-shot mode / standby (writes O/C = 0, RDY = 0)."""
        self._continuous = False
        self._discard = False
        self._connection.write(bytes([self._config(False)]))

    def is_continuous(self):
        """Report the conversion mode.

        Returns:
            bool: True in continuous mode.
        """
        return self._continuous

    def read_continuous_raw(self):
        """Wait for a fresh result in continuous mode and return the signed code.

        The first sample after a configuration change is discarded.

        Returns:
            int: Signed code.

        Raises:
            RuntimeError: if the driver is not in continuous mode.
            TimeoutError: if no result arrives in time.
        """
        if not self._continuous:
            raise RuntimeError('not in continuous mode; call start_continuous()')
        conv = _CONV_S[self._bits]
        if self._discard:
            self._wait_ready(conv)
            self._discard = False
        return self._wait_ready(conv)

    def read_continuous(self):
        """Voltage form of :meth:`read_continuous_raw`.

        Returns:
            float: Differential voltage in volts.
        """
        return self.read_continuous_raw() * self.lsb() / self._gain

    def read_config(self):
        """Read the configuration byte (the last byte of a read).

        Returns:
            int: Configuration byte; bit 7 is the live RDY flag.
        """
        return self._connection.read(self._nbytes() + 1)[self._nbytes()]

    def is_ready(self):
        """Report whether a fresh result is available (RDY = 0).

        Returns:
            bool: True if the output register holds a new result.
        """
        return not self.read_config() & _CFG_RDY

    def read_scan(self, channels):
        """Run one one-shot conversion per entry, in order.

        Args:
            channels: List of channels (0 or 1).

        Returns:
            list: Voltages in volts, one per entry.
        """
        return [self.read_voltage(ch) for ch in channels]

    def general_call_reset(self):
        """Send the general call reset (0x00 0x06).

        Restores the power-on defaults on the chip (continuous, 12 bit, gain x1,
        CH1) and resets the driver's state to match. Needs a Connection that
        transmits the bytes to the general-call address.
        """
        self._connection.write(_GC_RESET)
        self._bits = 12
        self._gain = 1
        self._channel = 0
        self._continuous = True
        self._discard = True

    def general_call_convert(self):
        """Send the general call conversion (0x00 0x08).

        Starts a one-shot conversion on every device on the bus at once; read the
        result with :meth:`read_pending_raw`. Needs a Connection that transmits
        the bytes to the general-call address.
        """
        self._connection.write(_GC_CONVERT)
        self._continuous = False

    def read_pending_raw(self):
        """Wait for RDY = 0 and return the code of a conversion started elsewhere.

        Returns:
            int: Signed code.

        Raises:
            TimeoutError: if no result arrives in time.
        """
        return self._wait_ready(_CONV_S[self._bits])

    def full_scale(self):
        """Full-scale differential input range at the current gain.

        Returns:
            float: ``2.048 / gain`` in volts.
        """
        return _VREF / self._gain
