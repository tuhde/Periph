"""ISL29125 — digital red, green and blue colour light sensor (Renesas / Intersil).

RGB colour sensor with an on-chip IR-blocking filter, a 12/16-bit integrating
ADC (16 bit: 100 ms, rejects 50/60 Hz flicker), two sensing ranges (375 lux and
10 000 lux full scale), programmable IR compensation, and a threshold window
with persistence on one selectable colour channel that drives the open-drain
INT pin. Results are unsigned 16-bit counts, LSB first. The I²C address is
fixed at 0x44. The same driver file is used on MicroPython, CircuitPython, and
Linux hosts.

The driver uses ``RegisterConnection`` (1-byte register address, auto-increment
on block reads and writes). Reading ``STATUS`` clears its flags, so the driver
reads it once per poll and keeps the interrupt, conversion and brown-out bits
until ``poll_interrupt()`` or ``status()`` hands them out.

Args:
    connection: RegisterConnection (I²C) pointing at the device (0x44).
"""

import time

try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False

I2C_ADDRESS = 0x44
DEVICE_ID = 0x7D

_REG_DEVICE_ID = 0x00
_REG_CONFIG1 = 0x01
_REG_CONFIG2 = 0x02
_REG_CONFIG3 = 0x03
_REG_LOW_TH = 0x04
_REG_HIGH_TH = 0x06
_REG_STATUS = 0x08
_REG_GREEN = 0x09

_RESET_VALUE = 0x46

_CFG1_SYNC = 0x20
_CFG1_BITS_12 = 0x10
_CFG1_RNG_10000 = 0x08
_CFG1_MODE_MASK = 0x07
_CFG3_CONVEN = 0x10

STATUS_BROWNOUT = 0x04
STATUS_CONVERSION = 0x02
STATUS_THRESHOLD = 0x01

SOURCE_THRESHOLD = 0x01
SOURCE_CONVERSION = 0x02

_MODE_CODE = {
    'power_down': 0, 'green': 1, 'red': 2, 'blue': 3, 'standby': 4,
    'rgb': 5, 'green_red': 6, 'green_blue': 7,
}
_CHANNEL_CODE = {'none': 0, 'green': 1, 'red': 2, 'blue': 3}
_PERSISTENCE_CODE = {1: 0, 2: 1, 4: 2, 8: 3}

_POLL_INTERVAL_S = 0.01
_WAIT_POLLS = 41            # 41 polls x 10 ms = 400 ms
_INT_POLL_INTERVAL_S = 0.05


class ISL29125Minimal:
    """ISL29125 colour sensor — minimal interface.

    Starts continuous RGB conversion (16 bit, 10 000 lux range, maximum IR
    compensation) and returns the three channel counts with one call.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    def __init__(self, connection):
        """Initialize ISL29125Minimal and start the sensor.

        Reads DEVICE_ID (must be 0x7D), clears the brown-out flag and writes
        CONFIG1 = 0x0D, CONFIG2 = 0xBF, CONFIG3 = 0x00.

        Args:
            connection: RegisterConnection (I²C) pointing at the device.

        Raises:
            ValueError: if DEVICE_ID does not identify an ISL29125.
        """
        self._connection = connection
        self._pending = 0
        ident = self._read_reg(_REG_DEVICE_ID)
        if ident != DEVICE_ID:
            raise ValueError('ISL29125 not found (DEVICE_ID 0x%02X)' % ident)
        self._write_reg(_REG_STATUS, 0x00)
        self._write_reg(_REG_CONFIG3, 0x00)
        self._write_reg(_REG_CONFIG2, 0xBF)
        self._write_reg(_REG_CONFIG1, 0x0D)

    def _read_reg(self, reg):
        return self._connection.read_reg(reg, 1)[0]

    def _write_reg(self, reg, value):
        self._connection.write_reg(reg, bytes([value & 0xFF]))

    def _read_status(self):
        """Read STATUS once and keep its flag bits until handed out."""
        value = self._read_reg(_REG_STATUS)
        self._pending |= value & (STATUS_BROWNOUT | STATUS_CONVERSION | STATUS_THRESHOLD)
        return value

    def _wait_data(self):
        """Wait for CONVENF, at most 400 ms."""
        for _ in range(_WAIT_POLLS):
            if self._read_status() & STATUS_CONVERSION:
                return True
            time.sleep(_POLL_INTERVAL_S)
        return False

    def _read_block(self):
        raw = self._connection.read_reg(_REG_GREEN, 6)
        green = raw[0] | (raw[1] << 8)
        red = raw[2] | (raw[3] << 8)
        blue = raw[4] | (raw[5] << 8)
        return red, green, blue

    def read(self):
        """Read red, green and blue from one conversion.

        Waits for a new result (at most 400 ms), then block-reads registers
        0x09-0x0E.

        Returns:
            dict: ``{'red', 'green', 'blue'}`` — unsigned 16-bit counts.
        """
        self._wait_data()
        red, green, blue = self._read_block()
        return {'red': red, 'green': green, 'blue': blue}


class ISL29125Full(ISL29125Minimal):
    """ISL29125 full interface — extends Minimal with single-channel reads,
    mode, range, resolution, synchronisation and IR-compensation configuration,
    thresholds, persistence, and interrupt delivery.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    def __init__(self, connection):
        ISL29125Minimal.__init__(self, connection)
        self._callback = None
        self._poll_thread = None
        self._poll_stop = True
        self._int_source = None

    # --- data -----------------------------------------------------------

    def _read_channel(self, reg):
        raw = self._connection.read_reg(reg, 2)
        return raw[0] | (raw[1] << 8)

    def read_red(self):
        """Read the red channel.

        Returns:
            int: Red count (unsigned 16 bit).
        """
        return self._read_channel(0x0B)

    def read_green(self):
        """Read the green channel.

        Returns:
            int: Green count.
        """
        return self._read_channel(0x09)

    def read_blue(self):
        """Read the blue channel.

        Returns:
            int: Blue count.
        """
        return self._read_channel(0x0D)

    def read_rgb(self):
        """Block-read all three channels without waiting.

        Returns:
            tuple: ``(red, green, blue)`` counts.
        """
        return self._read_block()

    def data_ready(self):
        """Report whether a conversion has completed.

        Reads STATUS, which clears its flags; the interrupt, conversion and
        brown-out bits are kept for ``poll_interrupt()`` and ``status()``.

        Returns:
            bool: True if CONVENF was set.
        """
        return bool(self._read_status() & STATUS_CONVERSION)

    # --- configuration ----------------------------------------------------

    def _update_reg(self, reg, set_bits, clear_bits):
        value = self._read_reg(reg)
        self._write_reg(reg, (value & ~clear_bits) | set_bits)

    def set_mode(self, mode):
        """Select the operating mode (conversions are continuous).

        Args:
            mode: ``'power_down'``, ``'standby'``, ``'green'``, ``'red'``,
                ``'blue'``, ``'rgb'``, ``'green_red'`` or ``'green_blue'``.

        Raises:
            ValueError: for an unknown mode.
        """
        if mode not in _MODE_CODE:
            raise ValueError('unknown mode %r' % (mode,))
        self._update_reg(_REG_CONFIG1, _MODE_CODE[mode], _CFG1_MODE_MASK)

    def set_range(self, lux):
        """Select the sensing range.

        Args:
            lux: 375 or 10000 (full-scale illuminance).

        Raises:
            ValueError: for an unsupported range.
        """
        if lux == 375:
            self._update_reg(_REG_CONFIG1, 0, _CFG1_RNG_10000)
        elif lux == 10000:
            self._update_reg(_REG_CONFIG1, _CFG1_RNG_10000, 0)
        else:
            raise ValueError('range must be 375 or 10000')

    def set_resolution(self, bits):
        """Select the ADC resolution (and with it the integration time).

        Args:
            bits: 12 or 16.

        Raises:
            ValueError: for an unsupported resolution.
        """
        if bits == 16:
            self._update_reg(_REG_CONFIG1, 0, _CFG1_BITS_12)
        elif bits == 12:
            self._update_reg(_REG_CONFIG1, _CFG1_BITS_12, 0)
        else:
            raise ValueError('resolution must be 12 or 16')

    def set_sync(self, external):
        """Select the conversion start source.

        Args:
            external: True to start on the rising edge of INT (INT becomes an
                input and cannot signal interrupts), False to start on the
                I²C write.
        """
        self._update_reg(_REG_CONFIG1, _CFG1_SYNC if external else 0, _CFG1_SYNC)

    def set_ir_compensation(self, offset, adjust):
        """Set the active IR compensation (CONFIG2).

        Args:
            offset: False for codes 0-63, True for codes 106-169.
            adjust: Compensation adjust, 0-63.

        Raises:
            ValueError: if ``adjust`` is outside 0-63.
        """
        if adjust < 0 or adjust > 63:
            raise ValueError('adjust must be 0-63')
        self._write_reg(_REG_CONFIG2, (0x80 if offset else 0) | adjust)

    def device_id(self):
        """Read DEVICE_ID.

        Returns:
            int: 0x7D for the ISL29125.
        """
        return self._read_reg(_REG_DEVICE_ID)

    def status(self):
        """Read STATUS (clears the flags on the chip).

        Returns:
            int: Raw status byte, with the flag bits seen since the last
            ``poll_interrupt()`` / ``status()`` merged in.
        """
        value = self._read_status()
        value |= self._pending
        self._pending = 0
        return value

    def soft_reset(self):
        """Reset all registers to their defaults (writes 0x46 to register 0x00)."""
        self._write_reg(_REG_DEVICE_ID, _RESET_VALUE)
        time.sleep(0.01)
        self._pending = 0

    # --- thresholds and interrupt configuration ----------------------------

    def set_thresholds(self, low, high):
        """Set the 16-bit threshold window.

        Args:
            low: Lower threshold (0-65535).
            high: Upper threshold (0-65535).

        Raises:
            ValueError: if a value is outside 16 bit or ``low`` exceeds ``high``.
        """
        if low < 0 or high < 0 or low > 0xFFFF or high > 0xFFFF or low > high:
            raise ValueError('thresholds must satisfy 0 <= low <= high <= 65535')
        self._connection.write_reg(_REG_LOW_TH, bytes([low & 0xFF, low >> 8]))
        self._connection.write_reg(_REG_HIGH_TH, bytes([high & 0xFF, high >> 8]))

    def get_thresholds(self):
        """Read the threshold window.

        Returns:
            tuple: ``(low, high)`` counts.
        """
        low = self._connection.read_reg(_REG_LOW_TH, 2)
        high = self._connection.read_reg(_REG_HIGH_TH, 2)
        return low[0] | (low[1] << 8), high[0] | (high[1] << 8)

    def set_interrupt_channel(self, channel):
        """Select the channel the threshold interrupt monitors.

        Args:
            channel: ``'none'``, ``'green'``, ``'red'`` or ``'blue'``.

        Raises:
            ValueError: for an unknown channel.
        """
        if channel not in _CHANNEL_CODE:
            raise ValueError("channel must be 'none', 'green', 'red' or 'blue'")
        self._update_reg(_REG_CONFIG3, _CHANNEL_CODE[channel], 0x03)

    def set_persistence(self, count):
        """Set the number of integration cycles before the interrupt asserts.

        Args:
            count: 1, 2, 4 or 8.

        Raises:
            ValueError: for an unsupported count.
        """
        if count not in _PERSISTENCE_CODE:
            raise ValueError('count must be 1, 2, 4 or 8')
        self._update_reg(_REG_CONFIG3, _PERSISTENCE_CODE[count] << 2, 0x0C)

    # --- interrupt ----------------------------------------------------------

    def enable_interrupt(self, source=SOURCE_THRESHOLD):
        """Enable an interrupt source.

        For ``SOURCE_THRESHOLD`` select the channel beforehand with
        ``set_interrupt_channel()``; ``SOURCE_CONVERSION`` sets CONVEN.

        Args:
            source: ``SOURCE_THRESHOLD`` (default) or ``SOURCE_CONVERSION``.
        """
        if source == SOURCE_CONVERSION:
            self._update_reg(_REG_CONFIG3, _CFG3_CONVEN, 0)
        elif self._read_reg(_REG_CONFIG3) & 0x03 == 0:
            self._update_reg(_REG_CONFIG3, 0x01, 0)

    def disable_interrupt(self, source=SOURCE_THRESHOLD):
        """Disable an interrupt source.

        Args:
            source: ``SOURCE_THRESHOLD`` (INTSEL = none) or ``SOURCE_CONVERSION``.
        """
        if source == SOURCE_CONVERSION:
            self._update_reg(_REG_CONFIG3, 0, _CFG3_CONVEN)
        else:
            self._update_reg(_REG_CONFIG3, 0, 0x03)

    def poll_interrupt(self):
        """Read STATUS (clearing the flags) and return it.

        Returns:
            int: Status byte; ``STATUS_THRESHOLD`` / ``STATUS_CONVERSION`` are
            set for events seen since the last poll.
        """
        return self.status()

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to INT (falling edge).

        ``callback(status)`` is called with the STATUS byte after the driver
        has read (and thereby cleared) it. Delivery uses int_pin, else
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
        if (status & (STATUS_THRESHOLD | STATUS_CONVERSION)) and self._callback:
            self._callback(status)

    def _poll_loop(self):
        while not self._poll_stop:
            self._int_handler()
            time.sleep(_INT_POLL_INTERVAL_S)
