import os

try:
    import machine as _machine
    from .neopixel_micropython import NeoPixelConnection as _NeoPixelConnection

    def NeoPixelConnection(mosi, sck=None, miso=None, baudrate=2_400_000, spi_id=None,
                           en_pin=None):
        """Create a NeoPixel connection for MicroPython.

        Args:
            mosi:     MOSI pin number (data to strip).
            sck:      SCK pin number (required for SoftSPI; ignored for hardware SPI).
            miso:     MISO pin number (unused by NeoPixel; required by SoftSPI).
            baudrate: SPI clock frequency (default 2 400 000).
            spi_id:   Hardware SPI peripheral id. If given, uses machine.SPI(spi_id)
                      and mosi/sck/miso are ignored.
            en_pin: Optional OutputPin for hardware enable/power control.
        """
        if spi_id is not None:
            spi = _machine.SPI(spi_id, baudrate=baudrate, polarity=0, phase=0)
        else:
            spi = _machine.SoftSPI(
                baudrate=baudrate, polarity=0, phase=0,
                sck=_machine.Pin(sck if sck is not None else mosi - 1),
                mosi=_machine.Pin(mosi),
                miso=_machine.Pin(miso if miso is not None else mosi + 1),
            )
        return _NeoPixelConnection(spi, en_pin=en_pin)

except ImportError:
    try:
        import board as _board
        import busio as _busio
        from .neopixel_circuitpython import NeoPixelConnection as _NeoPixelConnection

        def NeoPixelConnection(mosi=None, sck=None, miso=None, baudrate=2_400_000, spi_id=None,
                               en_pin=None):
            """Create a NeoPixel connection for CircuitPython.

            Args:
                mosi:     Board MOSI pin; defaults to board.MOSI.
                sck:      Board SCK pin; defaults to board.SCK.
                miso:     Board MISO pin; defaults to board.MISO.
                baudrate: SPI clock frequency (default 2 400 000).
                spi_id:   Ignored (CircuitPython uses board pins).
                en_pin: Optional OutputPin for hardware enable/power control.
            """
            spi = _busio.SPI(
                sck if sck is not None else _board.SCK,
                MOSI=mosi if mosi is not None else _board.MOSI,
            )
            spi.try_lock()
            spi.configure(baudrate=baudrate, polarity=0, phase=0)
            spi.unlock()
            return _NeoPixelConnection(spi, en_pin=en_pin)

    except ImportError:
        from .neopixel_linux import NeoPixelConnection as _NeoPixelConnection

        def NeoPixelConnection(mosi=None, sck=None, miso=None, baudrate=2_400_000, spi_id=None,
                               bus=None, device=None, en_pin=None):
            """Create a NeoPixel connection for Linux (spidev).

            Args:
                bus:    SPI bus number; defaults to LINUX_SPI_BUS env var, then 0.
                device: SPI device number; defaults to LINUX_SPI_DEVICE env var, then 0.
                mosi, sck, miso, baudrate, spi_id: Ignored (Linux uses spidev).
                en_pin: Optional OutputPin for hardware enable/power control.
            """
            if bus is None:
                bus = int(os.environ.get('LINUX_SPI_BUS', '0'))
            if device is None:
                device = int(os.environ.get('LINUX_SPI_DEVICE', '0'))
            return _NeoPixelConnection(bus, device, en_pin=en_pin)
