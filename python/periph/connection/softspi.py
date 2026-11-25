class SoftSPI:
    """Bit-banged SPI master (mode 0, MSB first) over the OutputPin/InputPin abstractions.

    Works on any platform that has GPIO pins, so it is the fallback where the
    platform has no software-SPI object of its own (Linux, where spidev only
    talks to a hardware controller). MicroPython and CircuitPython already ship
    machine.SoftSPI / bitbangio.SPI; the connections accept those directly.

    The interface mirrors machine.SoftSPI (write / read), so a connection can
    use either one. Chip select is not part of the bus object: the connection
    drives CS itself, as it does for machine.SPI.

    Reading needs the optional MISO input. For a display controller whose DIN
    and DOUT share one pin, MISO is wired to that pin and MOSI is joined to it
    through a series resistor, so the host can keep driving MOSI (dummy bytes)
    while the chip drives the line and wins.

    Args:
        sck: OutputPin for the clock (idles low, mode 0).
        mosi: OutputPin for the data line to the device.
        miso: Optional InputPin sampling the data line from the device; None for
            a write-only bus.
        delay_us: Half-period delay in microseconds between clock edges
            (default 0: as fast as the GPIO calls allow).
    """

    def __init__(self, sck, mosi, miso=None, delay_us=0):
        self._sck = sck
        self._mosi = mosi
        self._miso = miso
        self._delay_s = delay_us / 1000000.0
        self._sck.set(False)

    def _pause(self):
        if self._delay_s:
            import time
            time.sleep(self._delay_s)

    def write(self, data):
        """Shift data out MSB-first, SPI mode 0.

        Args:
            data: Bytes to send.
        """
        for byte in data:
            for bit in range(7, -1, -1):
                self._mosi.set(bool((byte >> bit) & 1))
                self._pause()
                self._sck.set(True)
                self._pause()
                self._sck.set(False)

    def read(self, nbytes, write=0x00):
        """Clock in nbytes bytes from MISO while sending the `write` byte.

        Args:
            nbytes: Number of bytes to read.
            write: Byte driven on MOSI during every clocked byte (default 0x00).

        Returns:
            bytes: The sampled bytes, MSB-first.

        Raises:
            RuntimeError: If no MISO pin was given.
        """
        if self._miso is None:
            raise RuntimeError('SoftSPI has no MISO pin')
        out = bytearray()
        for _ in range(nbytes):
            value = 0
            for bit in range(7, -1, -1):
                self._mosi.set(bool((write >> bit) & 1))
                self._pause()
                self._sck.set(True)
                self._pause()
                value = (value << 1) | (1 if self._miso.is_high() else 0)
                self._sck.set(False)
            out.append(value)
        return bytes(out)
