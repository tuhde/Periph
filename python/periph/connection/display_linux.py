from .display_base import DisplayConnectionBase, WireMode

try:
    import spidev
except ImportError:
    spidev = None


class DisplayConnection(DisplayConnectionBase):
    """Display connection (SPI + D/C#, RES#, BUSY) for Linux.

    Two ways to reach the controller; give exactly one:

    - Hardware SPI: bus_num and device_num open /dev/spidevBUS.DEVICE (spidev).
      The kernel drives CS# and releases it after every transfer.
    - Software SPI: `spi` is a write/read object such as SoftSPI (bit-banged
      GPIO) and `cs` is the OutputPin for CS#, driven here for each transfer.

    Wire modes: SIMPLEX writes only. HALF_DUPLEX_HW reads through spidev's
    hardware 3-wire mode (SPI_3WIRE; the SPI controller must support it) and is
    available only with hardware SPI. HALF_DUPLEX_PIN reads full-duplex with
    MISO wired to the controller's DIN/DOUT line (MOSI joined to it through a
    series resistor): spidev xfer2() in hardware mode, or SoftSPI with a MISO
    input in software mode.

    Args:
        dc: OutputPin for D/C# (low = command, high = data).
        res: OutputPin for RES# (active low), or None if not wired.
        busy: InputPin for BUSY, read with is_high(), or None if the controller has no BUSY line.
        wire_mode: A WireMode value (default SIMPLEX).
        bus_num: SPI bus number for hardware mode.
        device_num: SPI chip-select line for hardware mode.
        max_speed_hz: Hardware SPI clock in Hz (default 4 000 000).
        spi: Software SPI object (write(data), read(n)) for software mode.
        cs: OutputPin for CS# in software mode.
        reset_pulse_us: RES# low time in microseconds (default 200).
        reset_settle_ms: Wait after RES# is released in milliseconds (default 10).
        en_pin: Optional OutputPin for hardware enable/power control.
        en_active_high: True if the EN pin is active-high (default); False for active-low.
    """

    def __init__(self, dc, res, busy, wire_mode=WireMode.SIMPLEX, bus_num=None, device_num=None,
                 max_speed_hz=4000000, spi=None, cs=None, reset_pulse_us=200,
                 reset_settle_ms=10, en_pin=None, en_active_high=True):
        hardware = bus_num is not None
        software = spi is not None
        if hardware == software:
            raise ValueError('specify exactly one of (bus_num, device_num) or (spi, cs)')
        if software and cs is None:
            raise ValueError('software SPI needs a cs OutputPin')
        if software and wire_mode == WireMode.HALF_DUPLEX_HW:
            raise ValueError('HALF_DUPLEX_HW needs hardware SPI (bus_num, device_num)')
        if software and wire_mode == WireMode.HALF_DUPLEX_PIN and not hasattr(spi, 'read'):
            raise ValueError('HALF_DUPLEX_PIN needs a software SPI with read() and a MISO pin')
        super().__init__(dc, res, busy, wire_mode, reset_pulse_us, reset_settle_ms,
                         en_pin, en_active_high)
        self._cs = cs
        if hardware:
            if spidev is None:
                raise RuntimeError('spidev is not installed (pip install spidev)')
            self._soft = None
            self._hw = spidev.SpiDev()
            self._hw.open(bus_num, device_num)
            self._hw.mode = 0
            self._hw.max_speed_hz = max_speed_hz
            if wire_mode == WireMode.HALF_DUPLEX_HW:
                self._hw.threewire = True
        else:
            self._hw = None
            self._soft = spi
            self._cs.set(True)

    def _spi_write(self, data):
        if self._hw is not None:
            self._hw.writebytes2(data)
        else:
            self._cs.set(False)
            self._soft.write(data)
            self._cs.set(True)

    def _spi_read(self, length):
        if self._hw is not None:
            if self.wire_mode == WireMode.HALF_DUPLEX_HW:
                return bytes(self._hw.readbytes(length))
            return bytes(self._hw.xfer2([0] * length))
        self._cs.set(False)
        result = self._soft.read(length)
        self._cs.set(True)
        return bytes(result)

    def close(self):
        """Release the spidev device (if opened)."""
        if self._hw is not None:
            self._hw.close()
            self._hw = None
