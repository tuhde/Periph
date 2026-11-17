try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False


class HT16K33Minimal:
    """HT16K33 16x8 LED matrix driver — minimal interface.

    Drives up to 16 ROW x 8 COM = 128 LEDs through a 16-byte display RAM
    shadowed in the driver. Typical I2C address 0x70 (A0-A2 select 0x70-0x77).
    Construction runs the initialization sequence: oscillator on, ROW15 as a
    ROW output, brightness 16/16, blink off, RAM cleared, display on.

    Args:
        connection: Configured I2C connection pointing at the device.
    """

    COMMONS = 8
    ROWS = 16

    _CMD_SYSTEM = 0x20
    _CMD_DISPLAY = 0x80
    _CMD_ROW_INT = 0xA0
    _CMD_DIMMING = 0xE0
    _CMD_KEY_PTR = 0x40
    _CMD_INT_PTR = 0x60

    def __init__(self, connection):
        self._connection = connection
        self._ram = bytearray(16)
        self._brightness = 15
        self._blink = 0
        self._display = True
        self._row_int = 0xA0
        self._command(self._CMD_SYSTEM | 0x01)
        self._command(self._row_int)
        self._command(self._CMD_DIMMING | self._brightness)
        self.show()
        self._apply_display()

    def _command(self, byte):
        self._connection.write(bytes([byte]))

    def _apply_display(self):
        self._command(self._CMD_DISPLAY | (self._blink << 1) | (1 if self._display else 0))

    def clear(self):
        """Zero the shadow RAM and write it to the chip."""
        for i in range(16):
            self._ram[i] = 0
        self.show()

    def set_pixel(self, com, row, on=True):
        """Set or clear one LED in the shadow RAM (does not write to the chip).

        Args:
            com: COM line, 0-7.
            row: ROW line, 0-15.
            on: True to light the LED, False to extinguish it.
        """
        if com < 0 or com > 7:
            raise ValueError('com must be in 0..7')
        if row < 0 or row > 15:
            raise ValueError('row must be in 0..15')
        idx = 2 * com + (row >> 3)
        mask = 1 << (row & 7)
        if on:
            self._ram[idx] |= mask
        else:
            self._ram[idx] &= ~mask & 0xFF

    def write_raw(self, data, address=0):
        """Write raw RAM bytes at ``address`` and update the shadow RAM.

        Args:
            data: 1-16 bytes of display data.
            address: start RAM address, 0-15.
        """
        data = bytes(data)
        if address < 0 or len(data) < 1 or address + len(data) > 16:
            raise ValueError('address + len(data) must be within 0..16')
        self._ram[address:address + len(data)] = data
        self._connection.write(bytes([address]) + data)

    def show(self):
        """Write the whole 16-byte shadow RAM to the chip."""
        self._connection.write(bytes([0x00]) + bytes(self._ram))

    def set_brightness(self, level):
        """Set the dimming level.

        Args:
            level: 0-15, giving (level + 1)/16 duty.
        """
        if level < 0 or level > 15:
            raise ValueError('level must be in 0..15')
        self._brightness = level
        self._command(self._CMD_DIMMING | level)


class HT16K33Full(HT16K33Minimal):
    """HT16K33 full interface — display control, standby, key scan, INT output.

    Args:
        connection: Configured I2C connection pointing at the device.
    """

    def __init__(self, connection):
        HT16K33Minimal.__init__(self, connection)
        self._callback = None
        self._poll_thread = None
        self._poll_stop = True
        self._int_source = None

    def display_on(self):
        """Turn the display output on (keeps the blink setting)."""
        self._display = True
        self._apply_display()

    def display_off(self):
        """Turn the display output off (keeps the blink setting)."""
        self._display = False
        self._apply_display()

    def set_blink(self, frequency):
        """Set the blink frequency.

        Args:
            frequency: 0 (off), 2, 1 or 0.5 Hz.
        """
        codes = {0: 0, 2: 1, 1: 2, 0.5: 3}
        if frequency not in codes:
            raise ValueError('frequency must be 0, 2, 1 or 0.5')
        self._blink = codes[frequency]
        self._apply_display()

    def standby(self):
        """Stop the oscillator (display off, key scan stopped)."""
        self._command(self._CMD_SYSTEM)

    def wake(self):
        """Start the oscillator and restore display, dimming, ROW/INT and RAM."""
        self._command(self._CMD_SYSTEM | 0x01)
        self._command(self._row_int)
        self._command(self._CMD_DIMMING | self._brightness)
        self.show()
        self._apply_display()

    def set_int_output(self, enable, active_high=False):
        """Select the function of the ROW15/INT pin.

        Args:
            enable: True makes the pin an interrupt output (ROW15/K13 unusable).
            active_high: INT polarity when enabled (default active low).
        """
        if enable:
            self._row_int = 0xA3 if active_high else 0xA1
        else:
            self._row_int = 0xA0
        self._command(self._row_int)

    def read_keys(self):
        """Read and clear the key data RAM.

        Returns:
            int: key bitmask, bit ``k * 13 + (K - 1)`` for key input K
            (1-13) on key source KS``k`` (0-2).
        """
        self._connection.write(bytes([self._CMD_KEY_PTR]))
        b = self._connection.read(6)
        mask = 0
        for k in range(3):
            word = (b[2 * k] | (b[2 * k + 1] << 8)) & 0x1FFF
            mask |= word << (13 * k)
        return mask

    def key_pressed(self):
        """Read the INT flag.

        Returns:
            bool: True if a key press is pending.
        """
        self._connection.write(bytes([self._CMD_INT_PTR]))
        return (self._connection.read(1)[0] & 0x01) != 0

    def get_pixel(self, com, row):
        """Read one LED state from the shadow RAM.

        Returns:
            bool: True if the LED is set.
        """
        if com < 0 or com > 7 or row < 0 or row > 15:
            raise ValueError('com 0..7, row 0..15')
        return (self._ram[2 * com + (row >> 3)] >> (row & 7)) & 1 == 1

    def poll_interrupt(self):
        """Check the INT flag and, if set, read (and clear) the key data.

        Returns:
            int: key bitmask, or 0 if no key is pending.
        """
        if not self.key_pressed():
            return 0
        return self.read_keys()

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to key-press events.

        ``callback(keys)`` receives the key bitmask. Uses ``int_pin`` or
        ``connection.int_pin`` (edge per the configured polarity), else a
        20 ms polling thread (Linux).

        Args:
            callback: Callable(keys: int).
            int_pin: Optional InputPin overriding connection.int_pin.
        """
        self._callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        if pin is not None:
            from periph.connection.input_pin import InputPin
            trig = InputPin.RISING if self._row_int == 0xA3 else InputPin.FALLING
            pin.on_edge(self._int_handler, trig)
            self._int_source = pin
        elif _LINUX:
            self._poll_stop = False
            self._poll_thread = _threading.Thread(target=self._poll_loop, daemon=True)
            self._poll_thread.start()

    def off_interrupt(self):
        """Unsubscribe and stop delivery."""
        self._poll_stop = True
        if self._int_source is not None:
            self._int_source.off_edge(self._int_handler)
            self._int_source = None
        self._callback = None

    def _int_handler(self):
        keys = self.poll_interrupt()
        if keys and self._callback:
            self._callback(keys)

    def _poll_loop(self):
        import time
        while not self._poll_stop:
            self._int_handler()
            time.sleep(0.02)
