import os

try:
    from machine import Pin as _Pin
    from .hx711_micropython import HX711Connection as _HX711Connection

    def HX711Connection(dout, pd_sck, en_pin=None):
        """Create an HX711 connection for MicroPython.

        Args:
            dout:   GPIO pin number for DOUT (data-out from chip, input).
            pd_sck: GPIO pin number for PD_SCK (clock / power-down, output).
            en_pin: Optional OutputPin for hardware enable/power control.
        """
        return _HX711Connection(_Pin(dout, _Pin.IN), _Pin(pd_sck, _Pin.OUT), en_pin=en_pin)

except ImportError:
    try:
        import digitalio as _digitalio
        import board as _board
        from .hx711_circuitpython import HX711Connection as _HX711Connection

        def HX711Connection(dout, pd_sck, en_pin=None):
            """Create an HX711 connection for CircuitPython.

            Args:
                dout:   Pin object or board pin for DOUT (input).
                pd_sck: Pin object or board pin for PD_SCK (output).
                en_pin: Optional OutputPin for hardware enable/power control.
            """
            if isinstance(dout, int):
                dout_pin = _digitalio.DigitalInOut(getattr(_board, f'D{dout}'))
                dout_pin.direction = _digitalio.Direction.INPUT
            else:
                dout_pin = dout
            if isinstance(pd_sck, int):
                sck_pin = _digitalio.DigitalInOut(getattr(_board, f'D{pd_sck}'))
                sck_pin.direction = _digitalio.Direction.OUTPUT
            else:
                sck_pin = pd_sck
            return _HX711Connection(dout_pin, sck_pin, en_pin=en_pin)

    except ImportError:
        import gpiod as _gpiod
        from .hx711_linux import HX711Connection as _HX711Connection

        def HX711Connection(dout, pd_sck, chip=None, en_pin=None):
            """Create an HX711 connection for Linux (gpiod v2).

            Args:
                dout:   GPIO line offset for DOUT (input from chip).
                pd_sck: GPIO line offset for PD_SCK (clock output).
                chip:   GPIO chip path; defaults to LINUX_GPIO_CHIP env var, then /dev/gpiochip0.
                en_pin: Optional OutputPin for hardware enable/power control.
            """
            if chip is None:
                chip = os.environ.get('LINUX_GPIO_CHIP', '/dev/gpiochip0')
            request = _gpiod.request_lines(
                chip,
                consumer='hx711',
                config={
                    dout:   _gpiod.LineSettings(direction=_gpiod.line.Direction.INPUT),
                    pd_sck: _gpiod.LineSettings(direction=_gpiod.line.Direction.OUTPUT),
                },
            )
            return _HX711Connection(request, dout, pd_sck, en_pin=en_pin)
