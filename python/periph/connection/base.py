class Connection:
    """Base class for byte-oriented bus connections (I2C, SPI, SMBus, UART).

    A connection instance represents one device on a bus, plus its optional
    INT pin (edge notifications from the chip's INT line), EN pin (hardware
    enable/power control), and a software enable gate. Subclasses implement
    the protected _read/_write/_write_read methods; this base class gates
    every call through the enabled flag so chip drivers never need to check
    it themselves.
    """

    def __init__(self, int_pin=None, en_pin=None):
        self.int_pin = int_pin
        self.en_pin = en_pin
        self._enabled = True

    def enable(self):
        """Resume bus access; drives the hardware EN pin high if wired."""
        self._enabled = True
        if self.en_pin:
            self.en_pin.set(True)

    def disable(self):
        """Gate all subsequent bus reads and writes; drives EN pin low if wired.

        Reads silently return zero bytes; writes silently become no-ops.
        """
        self._enabled = False
        if self.en_pin:
            self.en_pin.set(False)

    def is_enabled(self):
        """Return the current software-gate state.

        Returns:
            bool: True if enabled.
        """
        return self._enabled

    def write(self, data):
        """Send bytes to the device, unless disabled.

        Args:
            data: Bytes to write.
        """
        if not self._enabled:
            return
        self._write(data)

    def read(self, n):
        """Read bytes from the device, unless disabled.

        Args:
            n: Number of bytes to read.

        Returns:
            bytes: Data received, or n zero bytes if disabled.
        """
        if not self._enabled:
            return bytes(n)
        return self._read(n)

    def write_read(self, data, n):
        """Write then read without releasing the bus between phases, unless disabled.

        Args:
            data: Bytes to write (typically a register address).
            n: Number of bytes to read back.

        Returns:
            bytes: Data received, or n zero bytes if disabled.
        """
        if not self._enabled:
            return bytes(n)
        return self._write_read(data, n)

    def _write(self, data):
        """Subclass hook: send bytes to the device.

        Raises:
            NotImplementedError: Subclasses must implement this method.
        """
        raise NotImplementedError

    def _read(self, n):
        """Subclass hook: read bytes from the device.

        Raises:
            NotImplementedError: Subclasses must implement this method.
        """
        raise NotImplementedError

    def _write_read(self, data, n):
        """Subclass hook: write then read without releasing the bus.

        Raises:
            NotImplementedError: Subclasses must implement this method.
        """
        raise NotImplementedError

    def close(self):
        """Release any resources held by this connection. No-op by default."""
        pass
