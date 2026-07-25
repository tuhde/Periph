import os

try:
    from machine import Pin as _Pin
    from .dhtxx_micropython import DHTxxTransport

    _DHTxxTransport_base = DHTxxTransport

    def DHTxxTransport(pin):
        """Create a DHTxx transport for MicroPython.

        Args:
            pin: GPIO pin number for the DATA line.
        """
        return _DHTxxTransport_base(_Pin(pin, _Pin.IN))

except ImportError:
    try:
        import digitalio as _digitalio
        import board as _board
        from .dhtxx_circuitpython import DHTxxTransport as _DHTxxTransport

        def DHTxxTransport(pin):
            """Create a DHTxx transport for CircuitPython.

            Args:
                pin: Integer pin number (mapped to board.D{n}) or board pin object.
            """
            if isinstance(pin, int):
                p = _digitalio.DigitalInOut(getattr(_board, f'D{pin}'))
                p.direction = _digitalio.Direction.INPUT
            else:
                p = pin
            return _DHTxxTransport(p)

    except ImportError:
        import gpiod as _gpiod
        from .dhtxx_linux import DHTxxTransport as _DHTxxTransport

        def DHTxxTransport(pin, chip_num=None):
            """Create a DHTxx transport for Linux (gpiod v2).

            Args:
                pin:      GPIO line offset for the DATA line.
                chip_num: GPIO chip number; defaults to LINUX_GPIO_CHIP env var (int), then 0.
            """
            if chip_num is None:
                chip_num = int(os.environ.get('LINUX_GPIO_CHIP', '0'))
            return _DHTxxTransport(chip_num, pin)
