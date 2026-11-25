"""APDS-9250 — digital RGB, IR and ambient light sensor (Broadcom).

Four simultaneously integrating photodiode channels (red, green, blue, IR; the
green channel doubles as the ambient-light channel) with a 13- to 20-bit ADC,
five gains (x1, x3, x6, x9, x18) and a programmable measurement rate of 25 ms to
2 s. Results are unsigned counts in 3-byte registers (LSB first). A threshold or
variation interrupt on one channel, with a persistence of 1-16 events, drives
the open-drain INT pin. The I²C address is fixed at 0x52. The same driver file
is used on MicroPython, CircuitPython, and Linux hosts.

The driver uses ``RegisterConnection`` (1-byte register address, auto-increment
on block reads and writes). Reading ``MAIN_STATUS`` clears the POWER_ON, LS_INT
and LS_DATA flags, so the driver reads it once per poll and keeps the interrupt
and power-on bits until ``poll_interrupt()`` or ``status()`` hands them out.

Args:
    connection: RegisterConnection (I²C) pointing at the device (0x52).
"""

import time

try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False

I2C_ADDRESS = 0x52

_REG_MAIN_CTRL = 0x00
_REG_LS_MEAS_RATE = 0x04
_REG_LS_GAIN = 0x05
_REG_PART_ID = 0x06
_REG_MAIN_STATUS = 0x07
_REG_DATA_IR = 0x0A
_REG_DATA_GREEN = 0x0D
_REG_DATA_BLUE = 0x10
_REG_DATA_RED = 0x13
_REG_INT_CFG = 0x19
_REG_INT_PERSISTENCE = 0x1A
_REG_THRES_UP = 0x21
_REG_THRES_LOW = 0x24
_REG_THRES_VAR = 0x27

_CTRL_SW_RESET = 0x10
_CTRL_CS_MODE = 0x04
_CTRL_LS_EN = 0x02

_INT_EN = 0x04
_INT_VAR_MODE = 0x08
_INT_SEL_MASK = 0x30

STATUS_POWER_ON = 0x20
STATUS_INTERRUPT = 0x10
STATUS_DATA_READY = 0x08

SOURCE_THRESHOLD = 0x01
SOURCE_VARIATION = 0x02

# Resolution in bits <-> LS_RESOLUTION code; integration time in seconds.
_RESOLUTION_CODE = {20: 0, 19: 1, 18: 2, 17: 3, 16: 4, 13: 5}
_RESOLUTION_BITS = (20, 19, 18, 17, 16, 13)
_INTEGRATION_S = (0.4, 0.2, 0.1, 0.05, 0.025, 0.003125)

# Measurement rate in milliseconds <-> LS_MEAS_RATE code (6 and 7 both mean 2 s).
_RATE_CODE = {25: 0, 50: 1, 100: 2, 200: 3, 500: 4, 1000: 5, 2000: 6}
_RATE_MS = (25, 50, 100, 200, 500, 1000, 2000, 2000)

_GAIN_CODE = {1: 0, 3: 1, 6: 2, 9: 3, 18: 4}
_GAIN = (1, 3, 6, 9, 18)

_CHANNEL_CODE = {'ir': 0, 'green': 1, 'red': 2, 'blue': 3}

_START_WAIT_S = 0.005       # support blocks settle after LS_EN
_POLL_INTERVAL_S = 0.005    # wait-for-data poll step
_INT_POLL_INTERVAL_S = 0.05  # interrupt polling-thread step
_WAIT_MARGIN_S = 0.05


def _count(b0, b1, b2):
    return b0 | (b1 << 8) | ((b2 & 0x0F) << 16)


class APDS9250Minimal:
    """APDS-9250 colour sensor — minimal interface.

    Starts the sensor in colour-sensing mode with the chip defaults (18 bit
    resolution, 100 ms rate, gain x3, interrupt disabled) and returns the four
    channel counts with one call.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    def __init__(self, connection):
        """Initialize APDS9250Minimal and start the sensor.

        Reads PART_ID (the upper nibble must be 0xB), writes the default
        configuration and sets LS_EN and CS_MODE in MAIN_CTRL.

        Args:
            connection: RegisterConnection (I²C) pointing at the device.

        Raises:
            ValueError: if PART_ID does not identify an APDS-9250.
        """
        self._connection = connection
        self._pending = 0
        part = self._read_reg(_REG_PART_ID)
        if (part & 0xF0) != 0xB0:
            raise ValueError('APDS-9250 not found (PART_ID 0x%02X)' % part)
        self._write_reg(_REG_LS_MEAS_RATE, 0x22)
        self._write_reg(_REG_LS_GAIN, 0x01)
        self._write_reg(_REG_INT_CFG, 0x10)
        self._write_reg(_REG_MAIN_CTRL, _CTRL_LS_EN | _CTRL_CS_MODE)
        self._integration_s = 0.1
        time.sleep(_START_WAIT_S)

    def _read_reg(self, reg):
        return self._connection.read_reg(reg, 1)[0]

    def _write_reg(self, reg, value):
        self._connection.write_reg(reg, bytes([value & 0xFF]))

    def _read_status(self):
        """Read MAIN_STATUS once and keep the POWER_ON / LS_INT bits until handed out."""
        value = self._read_reg(_REG_MAIN_STATUS)
        self._pending |= value & (STATUS_POWER_ON | STATUS_INTERRUPT)
        return value

    def _wait_data(self):
        """Wait for LS_DATA, at most one integration time plus a margin."""
        waited = 0.0
        limit = self._integration_s + _WAIT_MARGIN_S
        while waited <= limit:
            if self._read_status() & STATUS_DATA_READY:
                return True
            time.sleep(_POLL_INTERVAL_S)
            waited += _POLL_INTERVAL_S
        return False

    def _read_block(self):
        raw = self._connection.read_reg(_REG_DATA_IR, 12)
        ir = _count(raw[0], raw[1], raw[2])
        green = _count(raw[3], raw[4], raw[5])
        blue = _count(raw[6], raw[7], raw[8])
        red = _count(raw[9], raw[10], raw[11])
        return red, green, blue, ir

    def read(self):
        """Read all four channels from one conversion.

        Waits for a new result (at most one integration time plus 50 ms), then
        block-reads registers 0x0A-0x15.

        Returns:
            dict: ``{'red', 'green', 'blue', 'ir'}`` — unsigned counts.
        """
        self._wait_data()
        red, green, blue, ir = self._read_block()
        return {'red': red, 'green': green, 'blue': blue, 'ir': ir}


class APDS9250Full(APDS9250Minimal):
    """APDS-9250 full interface — extends Minimal with single-channel reads,
    mode, resolution, rate and gain configuration, thresholds, variation and
    persistence, and interrupt delivery.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    def __init__(self, connection):
        APDS9250Minimal.__init__(self, connection)
        self._callback = None
        self._poll_thread = None
        self._poll_stop = True
        self._int_source = None

    # --- data -----------------------------------------------------------

    def _read_channel(self, reg):
        raw = self._connection.read_reg(reg, 3)
        return _count(raw[0], raw[1], raw[2])

    def read_red(self):
        """Read the red channel.

        Returns:
            int: Red count (unsigned, up to 20 bit).
        """
        return self._read_channel(_REG_DATA_RED)

    def read_green(self):
        """Read the green / ambient-light channel.

        Returns:
            int: Green count.
        """
        return self._read_channel(_REG_DATA_GREEN)

    def read_blue(self):
        """Read the blue channel.

        Returns:
            int: Blue count.
        """
        return self._read_channel(_REG_DATA_BLUE)

    def read_ir(self):
        """Read the infrared channel.

        Returns:
            int: IR count.
        """
        return self._read_channel(_REG_DATA_IR)

    def read_channels(self):
        """Block-read all four channels without waiting (call after ``data_ready()``).

        Returns:
            tuple: ``(red, green, blue, ir)`` counts.
        """
        return self._read_block()

    def data_ready(self):
        """Report whether a new result is available.

        Reads MAIN_STATUS, which clears its flags; the interrupt and power-on
        bits are kept for ``poll_interrupt()`` and ``status()``.

        Returns:
            bool: True if LS_DATA was set.
        """
        return bool(self._read_status() & STATUS_DATA_READY)

    # --- configuration ----------------------------------------------------

    def _update_reg(self, reg, set_bits, clear_bits):
        value = self._read_reg(reg)
        self._write_reg(reg, (value & ~clear_bits) | set_bits)

    def set_mode(self, colour):
        """Select colour-sensing or ALS/IR mode.

        Args:
            colour: True for CS mode (red, green, blue, IR), False for ALS/IR
                mode (green and IR only).
        """
        self._update_reg(_REG_MAIN_CTRL, _CTRL_CS_MODE if colour else 0, _CTRL_CS_MODE)

    def enable(self):
        """Activate the light sensor (LS_EN = 1)."""
        self._update_reg(_REG_MAIN_CTRL, _CTRL_LS_EN, 0)

    def disable(self):
        """Put the light sensor in standby (LS_EN = 0)."""
        self._update_reg(_REG_MAIN_CTRL, 0, _CTRL_LS_EN)

    def set_resolution(self, bits):
        """Set the ADC resolution (and with it the integration time).

        Restarts the measurement.

        Args:
            bits: 13, 16, 17, 18, 19 or 20.

        Raises:
            ValueError: for an unsupported resolution.
        """
        if bits not in _RESOLUTION_CODE:
            raise ValueError('resolution must be 13, 16, 17, 18, 19 or 20')
        self._update_reg(_REG_LS_MEAS_RATE, _RESOLUTION_CODE[bits] << 4, 0x70)
        self._integration_s = _INTEGRATION_S[_RESOLUTION_CODE[bits]]

    def set_measurement_rate(self, milliseconds):
        """Set the measurement rate.

        Args:
            milliseconds: 25, 50, 100, 200, 500, 1000 or 2000.

        Raises:
            ValueError: for an unsupported rate.
        """
        if milliseconds not in _RATE_CODE:
            raise ValueError('rate must be 25, 50, 100, 200, 500, 1000 or 2000 ms')
        self._update_reg(_REG_LS_MEAS_RATE, _RATE_CODE[milliseconds], 0x07)

    def set_gain(self, gain):
        """Set the analog gain for all channels. Restarts the measurement.

        Args:
            gain: 1, 3, 6, 9 or 18.

        Raises:
            ValueError: for an unsupported gain.
        """
        if gain not in _GAIN_CODE:
            raise ValueError('gain must be 1, 3, 6, 9 or 18')
        self._write_reg(_REG_LS_GAIN, _GAIN_CODE[gain])

    def get_resolution(self):
        """Read the ADC resolution.

        Returns:
            int: Resolution in bits (13, 16, 17, 18, 19 or 20).
        """
        code = (self._read_reg(_REG_LS_MEAS_RATE) >> 4) & 0x07
        return _RESOLUTION_BITS[code] if code < 6 else 18

    def get_measurement_rate(self):
        """Read the measurement rate.

        Returns:
            int: Rate in milliseconds.
        """
        return _RATE_MS[self._read_reg(_REG_LS_MEAS_RATE) & 0x07]

    def get_gain(self):
        """Read the analog gain.

        Returns:
            int: Gain (1, 3, 6, 9 or 18).
        """
        code = self._read_reg(_REG_LS_GAIN) & 0x07
        return _GAIN[code] if code < 5 else 18

    def integration_time(self):
        """Integration time for the current resolution.

        Returns:
            float: Seconds (0.003125 ... 0.4).
        """
        code = (self._read_reg(_REG_LS_MEAS_RATE) >> 4) & 0x07
        return _INTEGRATION_S[code] if code < 6 else 0.1

    def normalized(self, counts):
        """Convert a count to counts per gain-second.

        Makes readings taken with different gain or resolution comparable:
        ``counts / (gain * integration_time)``.

        Args:
            counts: Raw channel count.

        Returns:
            float: Normalised count.
        """
        return counts / (self.get_gain() * self.integration_time())

    def part_id(self):
        """Read PART_ID.

        Returns:
            tuple: ``(part, revision)`` — the two nibbles (0xB, 0x5 for the APDS-9250).
        """
        value = self._read_reg(_REG_PART_ID)
        return value >> 4, value & 0x0F

    def status(self):
        """Read MAIN_STATUS (clears the flags on the chip).

        Returns:
            int: Raw status byte, with interrupt and power-on bits seen since
            the last ``poll_interrupt()`` / ``status()`` merged in.
        """
        value = self._read_status()
        value |= self._pending
        self._pending = 0
        return value

    def soft_reset(self):
        """Trigger a software reset (all registers to defaults, sensor in standby)."""
        self._write_reg(_REG_MAIN_CTRL, _CTRL_SW_RESET)
        time.sleep(0.01)
        self._pending = 0
        self._integration_s = 0.1

    # --- thresholds and interrupt configuration ----------------------------

    def set_thresholds(self, low, high):
        """Set the 20-bit threshold window.

        Args:
            low: Lower threshold (0-1048575).
            high: Upper threshold (0-1048575).

        Raises:
            ValueError: if a value is outside 20 bit or ``low`` exceeds ``high``.
        """
        if low < 0 or high < 0 or low > 0xFFFFF or high > 0xFFFFF or low > high:
            raise ValueError('thresholds must satisfy 0 <= low <= high <= 1048575')
        self._connection.write_reg(
            _REG_THRES_LOW, bytes([low & 0xFF, (low >> 8) & 0xFF, (low >> 16) & 0x0F]))
        self._connection.write_reg(
            _REG_THRES_UP, bytes([high & 0xFF, (high >> 8) & 0xFF, (high >> 16) & 0x0F]))

    def get_thresholds(self):
        """Read the threshold window.

        Returns:
            tuple: ``(low, high)`` counts.
        """
        low = self._connection.read_reg(_REG_THRES_LOW, 3)
        high = self._connection.read_reg(_REG_THRES_UP, 3)
        return _count(low[0], low[1], low[2]), _count(high[0], high[1], high[2])

    def set_interrupt_channel(self, channel):
        """Select the channel the interrupt monitors.

        Args:
            channel: ``'ir'``, ``'green'``, ``'red'`` or ``'blue'``.

        Raises:
            ValueError: for an unknown channel.
        """
        if channel not in _CHANNEL_CODE:
            raise ValueError("channel must be 'ir', 'green', 'red' or 'blue'")
        self._update_reg(_REG_INT_CFG, _CHANNEL_CODE[channel] << 4, _INT_SEL_MASK)

    def set_persistence(self, count):
        """Set the number of consecutive events before the interrupt asserts.

        Args:
            count: 1-16.

        Raises:
            ValueError: if ``count`` is outside 1-16.
        """
        if count < 1 or count > 16:
            raise ValueError('count must be 1-16')
        self._write_reg(_REG_INT_PERSISTENCE, (count - 1) << 4)

    def set_variance(self, counts):
        """Set the variation-interrupt threshold.

        Args:
            counts: 8, 16, 32, 64, 128, 256, 512 or 1024.

        Raises:
            ValueError: if ``counts`` is not 8 << n (n = 0-7).
        """
        for n in range(8):
            if counts == (8 << n):
                self._write_reg(_REG_THRES_VAR, n)
                return
        raise ValueError('counts must be 8, 16, 32, ... 1024')

    # --- interrupt ----------------------------------------------------------

    def enable_interrupt(self, source=SOURCE_THRESHOLD):
        """Select threshold or variation mode and enable the interrupt.

        Args:
            source: ``SOURCE_THRESHOLD`` (default) or ``SOURCE_VARIATION``.
        """
        mode = _INT_VAR_MODE if source == SOURCE_VARIATION else 0
        self._update_reg(_REG_INT_CFG, _INT_EN | mode, _INT_EN | _INT_VAR_MODE)

    def disable_interrupt(self, source=SOURCE_THRESHOLD):
        """Disable the interrupt (clears LS_INT_EN).

        Args:
            source: Ignored; both sources share one enable bit.
        """
        self._update_reg(_REG_INT_CFG, 0, _INT_EN)

    def poll_interrupt(self):
        """Read MAIN_STATUS (clearing the flags) and return it.

        Returns:
            int: Status byte; ``STATUS_INTERRUPT`` is set if the interrupt
            condition was fulfilled since the last poll.
        """
        return self.status()

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to INT (falling edge).

        ``callback(status)`` is called with the MAIN_STATUS byte after the
        driver has read (and thereby cleared) it. Delivery uses int_pin, else
        connection.int_pin, else a 50 ms polling thread (Linux).

        Args:
            callback: Callable(status: int).
            int_pin: Optional InputPin overriding connection.int_pin.
        """
        self._callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        if pin is not None:
            from periph.connection.input_pin import InputPin
            pin.on_edge(self._int_handler, InputPin.FALLING)
            self._int_source = pin
        elif _LINUX:
            self._poll_stop = False
            self._poll_thread = _threading.Thread(target=self._poll_loop, daemon=True)
            self._poll_thread.start()

    def off_interrupt(self):
        """Unsubscribe and stop delivery."""
        self._poll_stop = True
        src = self._int_source
        if src is not None:
            src.off_edge(self._int_handler)
            self._int_source = None
        self._callback = None

    def _int_handler(self):
        status = self.poll_interrupt()
        if (status & STATUS_INTERRUPT) and self._callback:
            self._callback(status)

    def _poll_loop(self):
        while not self._poll_stop:
            self._int_handler()
            time.sleep(_INT_POLL_INTERVAL_S)
