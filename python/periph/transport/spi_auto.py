import os

try:
    import machine as _machine
    from .spi_micropython import SPITransport as _SPITransport

    def SPITransport(bus=1, cs_pin=5, baudrate=1_000_000, polarity=0, phase=0):
        """Create an SPI transport for MicroPython.

        Args:
            bus:      SPI peripheral id (default 1).
            cs_pin:   Chip-select pin number (active low).
            baudrate: SPI clock frequency in Hz (default 1 000 000).
            polarity: CPOL — 0 or 1 (default 0).
            phase:    CPHA — 0 or 1 (default 0).
        """
        spi = _machine.SPI(bus, baudrate=baudrate, polarity=polarity, phase=phase)
        cs  = _machine.Pin(cs_pin, _machine.Pin.OUT)
        return _SPITransport(spi, cs)

except ImportError:
    try:
        import board as _board
        import busio as _busio
        import digitalio as _digitalio
        from .spi_circuitpython import SPITransport as _SPITransport

        def SPITransport(bus=None, cs_pin=None, baudrate=1_000_000, polarity=0, phase=0):
            """Create an SPI transport for CircuitPython.

            Args:
                bus:      Ignored (CircuitPython uses board SPI pins).
                cs_pin:   Board CS pin; defaults to board.CS.
                baudrate: SPI clock frequency in Hz (default 1 000 000).
                polarity: CPOL — 0 or 1 (default 0).
                phase:    CPHA — 0 or 1 (default 0).
            """
            spi = _busio.SPI(_board.SCK, MOSI=_board.MOSI, MISO=_board.MISO)
            if cs_pin is None:
                cs_pin_obj = _digitalio.DigitalInOut(_board.CS)
            elif isinstance(cs_pin, int):
                cs_pin_obj = _digitalio.DigitalInOut(getattr(_board, f'D{cs_pin}'))
            else:
                cs_pin_obj = cs_pin
            cs_pin_obj.direction = _digitalio.Direction.OUTPUT
            return _SPITransport(spi, cs_pin_obj, baudrate=baudrate,
                                 polarity=polarity, phase=phase)

    except ImportError:
        from .spi_linux import SPITransport as _SPITransport

        def SPITransport(bus=None, device=None, cs_pin=None, baudrate=1_000_000,
                         polarity=0, phase=0):
            """Create an SPI transport for Linux (spidev).

            Args:
                bus:     SPI bus number; defaults to LINUX_SPI_BUS env var, then 0.
                device:  SPI device number; defaults to LINUX_SPI_DEVICE env var, then 0.
                cs_pin:  Ignored (kernel manages CS via spidev).
                baudrate: SPI clock frequency in Hz (default 1 000 000).
                polarity: CPOL — 0 or 1 (combined with phase into SPI mode).
                phase:    CPHA — 0 or 1.
            """
            if bus is None:
                bus = int(os.environ.get('LINUX_SPI_BUS', '0'))
            if device is None:
                device = int(os.environ.get('LINUX_SPI_DEVICE', '0'))
            mode = (polarity << 1) | phase
            return _SPITransport(bus, device, mode=mode, max_speed_hz=baudrate)
