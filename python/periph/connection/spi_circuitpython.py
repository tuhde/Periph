from .register_connection import RegisterConnection


class SPIConnection(RegisterConnection):
    """SPI connection for CircuitPython (wraps busio.SPI).

    Acquires and releases the bus lock around every operation. CS is a
    digitalio.DigitalInOut driven manually (False = asserted, True = deasserted).

    Args:
        bus: Configured busio.SPI instance.
        cs: digitalio.DigitalInOut for chip select (active low).
        baudrate: Clock frequency in Hz (default 1 000 000).
        polarity: CPOL — 0 or 1 (default 0).
        phase: CPHA — 0 or 1 (default 0).
        read_bit: Bit ORed into the command byte for a read; 0 if the chip
            has no such bit. Default 0x80.
        multi_byte_bit: Bit ORed in for multi-byte (burst) transfers when
            length > 1; None if the chip has no such bit and always
            auto-increments. Default None.
        int_pin: Optional InputPin for INT-line delivery.
        en_pin: Optional OutputPin for hardware enable/power control.
        en_active_high: True if the EN pin is active-high (default); False for active-low.
    """

    def __init__(self, bus, cs, baudrate=1_000_000, polarity=0, phase=0,
                 read_bit=0x80, multi_byte_bit=None, int_pin=None, en_pin=None, en_active_high=True):
        super().__init__(int_pin, en_pin, en_active_high=en_active_high)
        self._bus = bus
        self._cs = cs
        self._baudrate = baudrate
        self._polarity = polarity
        self._phase = phase
        self._cs.value = True
        self._read_bit = read_bit
        self._multi_byte_bit = multi_byte_bit
        self._held = False

    def read_reg(self, reg: int, length: int) -> bytes:
        """Read `length` bytes starting at register `reg`, building the SPI command byte.

        Args:
            reg: Register address.
            length: Number of bytes to read.

        Returns:
            bytes: Data received from the device.
        """
        cmd = reg | self._read_bit
        if length > 1 and self._multi_byte_bit:
            cmd |= self._multi_byte_bit
        return self.write_read(bytes([cmd]), length)

    def write_reg(self, reg: int, data) -> None:
        """Write `data` to register `reg`, building the SPI command byte.

        Args:
            reg: Register address.
            data: Bytes to write, or a single int for a 1-byte register.
        """
        payload = bytes([data]) if isinstance(data, int) else bytes(data)
        cmd = (reg & ~self._read_bit) | (self._multi_byte_bit if len(payload) > 1 and self._multi_byte_bit else 0)
        self.write(bytes([cmd]) + payload)

    def _lock(self):
        """Take the bus lock and configure it (no-op while select() holds it)."""
        if self._held:
            return
        while not self._bus.try_lock():
            pass
        self._bus.configure(baudrate=self._baudrate, polarity=self._polarity, phase=self._phase)

    def _unlock(self):
        if not self._held:
            self._bus.unlock()

    def idle_clocks(self, n):
        """Clock ``n`` bits of 0xFF with CS deasserted (rounded up to whole bytes).

        Args:
            n: Number of clock bits.
        """
        held = self._held
        if held:
            self.deselect()
        while not self._bus.try_lock():
            pass
        try:
            self._bus.configure(baudrate=self._baudrate, polarity=self._polarity, phase=self._phase)
            self._cs.value = True
            self._bus.write(b"\xff" * ((n + 7) // 8))
        finally:
            self._bus.unlock()
        if held:
            self.select()

    def set_speed(self, hz):
        """Change the SCK frequency; applied by the next ``configure()``.

        Args:
            hz: Clock frequency in Hz.

        Returns:
            bool: True.
        """
        self._baudrate = hz
        if self._held:
            self._bus.configure(baudrate=hz, polarity=self._polarity, phase=self._phase)
        return True

    def select(self):
        """Take the bus lock and assert CS; both stay held until deselect()."""
        if self._held:
            return
        while not self._bus.try_lock():
            pass
        self._bus.configure(baudrate=self._baudrate, polarity=self._polarity, phase=self._phase)
        self._cs.value = False
        self._held = True

    def deselect(self):
        """Release CS and the bus lock taken by select()."""
        if not self._held:
            return
        self._cs.value = True
        self._held = False
        self._bus.unlock()

    def read_into(self, buf):
        """Fill ``buf`` with ``len(buf)`` bytes read from the device (no allocation).

        Args:
            buf: Writable buffer.
        """
        self._lock()
        try:
            if not self._held:
                self._cs.value = False
            self._bus.readinto(buf, write_value=0xFF)
        finally:
            if not self._held:
                self._cs.value = True
            self._unlock()

    def _write(self, data):
        """Send bytes (CS and the bus lock stay held while select() is active).

        Args:
            data: Bytes to send.
        """
        self._lock()
        try:
            if not self._held:
                self._cs.value = False
            self._bus.write(bytes(data))
        finally:
            if not self._held:
                self._cs.value = True
            self._unlock()

    def _read(self, n):
        """Read n bytes, clocking 0xFF on MOSI.

        Args:
            n: Number of bytes to read.

        Returns:
            bytes: Data received from the device.
        """
        buf = bytearray(n)
        self._lock()
        try:
            if not self._held:
                self._cs.value = False
            self._bus.readinto(buf, write_value=0xFF)
        finally:
            if not self._held:
                self._cs.value = True
            self._unlock()
        return bytes(buf)

    def _write_read(self, data, n):
        """Full-duplex write then read of n bytes (MOSI high during the read phase).

        Builds a combined output buffer (command bytes + n 0xFF bytes) and a
        same-length input buffer, calls write_readinto, then returns the trailing
        n bytes (the chip's response after the command phase).

        Args:
            data: Command bytes to send.
            n: Number of response bytes expected.

        Returns:
            bytes: The n response bytes captured after the command phase.
        """
        data = bytes(data)
        out_buf = data + b"\xff" * n
        in_buf = bytearray(len(out_buf))
        self._lock()
        try:
            if not self._held:
                self._cs.value = False
            self._bus.write_readinto(out_buf, in_buf)
        finally:
            if not self._held:
                self._cs.value = True
            self._unlock()
        return bytes(in_buf[len(data):])
