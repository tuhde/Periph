import os

try:
    from machine import Pin as _Pin
    from .dhtxx_micropython import DHTxxConnection

    _DHTxxConnection_base = DHTxxConnection

    def DHTxxConnection(pin, en_pin=None):
        """Create a DHTxx connection for MicroPython.

        Args:
            pin: GPIO pin number for the DATA line.
            en_pin: Optional OutputPin for hardware enable/power control.
        """
        return _DHTxxConnection_base(_Pin(pin, _Pin.IN), en_pin=en_pin)

except ImportError:
    try:
        import digitalio as _digitalio
        import board as _board
        from .dhtxx_circuitpython import DHTxxConnection as _DHTxxConnection

        def DHTxxConnection(pin, en_pin=None):
            """Create a DHTxx connection for CircuitPython.

            Args:
                pin: Integer pin number (mapped to board.D{n}) or board pin object.
                en_pin: Optional OutputPin for hardware enable/power control.
            """
            if isinstance(pin, int):
                p = _digitalio.DigitalInOut(getattr(_board, f'D{pin}'))
                p.direction = _digitalio.Direction.INPUT
            else:
                p = pin
            return _DHTxxConnection(p, en_pin=en_pin)

    except ImportError:
        import gpiod as _gpiod
        from .dhtxx_linux import DHTxxConnection as _DHTxxConnection

        def DHTxxConnection(pin, chip_num=None, en_pin=None):
            """Create a DHTxx connection for Linux (gpiod v2).

            Args:
                pin:      GPIO line offset for the DATA line.
                chip_num: GPIO chip number; defaults to LINUX_GPIO_CHIP env var (int), then 0.
                en_pin: Optional OutputPin for hardware enable/power control.
            """
            if chip_num is None:
                chip_num = int(os.environ.get('LINUX_GPIO_CHIP', '0'))
            return _DHTxxConnection(chip_num, pin, en_pin=en_pin)
