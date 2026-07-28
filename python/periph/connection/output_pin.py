from abc import ABC, abstractmethod


class OutputPin(ABC):
    """Drives a chip's hardware enable or power pin."""

    @abstractmethod
    def set(self, high):
        """Drive the pin high (True) or low (False).

        Args:
            high: True to drive the pin high, False to drive it low.
        """
        raise NotImplementedError


class MicroPythonOutputPin(OutputPin):
    """OutputPin for MicroPython, backed by machine.Pin(n, machine.Pin.OUT).

    Args:
        pin_num: GPIO pin number, or an already-configured machine.Pin instance.
    """

    def __init__(self, pin_num):
        if isinstance(pin_num, int):
            import machine
            self._pin = machine.Pin(pin_num, machine.Pin.OUT)
        else:
            self._pin = pin_num

    def set(self, high):
        self._pin.value(1 if high else 0)


class CircuitPythonOutputPin(OutputPin):
    """OutputPin for CircuitPython, backed by digitalio.DigitalInOut.

    Args:
        pin: Board pin (e.g. board.D18) or an already-configured
            digitalio.DigitalInOut instance.
    """

    def __init__(self, pin):
        import digitalio

        if isinstance(pin, digitalio.DigitalInOut):
            self._pin = pin
        else:
            self._pin = digitalio.DigitalInOut(pin)
            self._pin.direction = digitalio.Direction.OUTPUT

    def set(self, high):
        self._pin.value = bool(high)


class LinuxOutputPin(OutputPin):
    """OutputPin for Linux, using a gpiod v2 output line request.

    Args:
        chip_path: GPIO chip device path (e.g. '/dev/gpiochip0').
        line_offset: GPIO line offset to drive.
        initial_high: Initial output level (default False).
    """

    def __init__(self, chip_path, line_offset, initial_high=False):
        import gpiod
        from gpiod.line import Direction, Value

        self._value_cls = Value
        settings = gpiod.LineSettings(
            direction=Direction.OUTPUT,
            output_value=Value.ACTIVE if initial_high else Value.INACTIVE,
        )
        chip = gpiod.Chip(chip_path)
        self._request = chip.request_lines(
            consumer="periph-en", line_config={line_offset: settings})
        self._line_offset = line_offset

    def set(self, high):
        self._request.set_value(
            self._line_offset, self._value_cls.ACTIVE if high else self._value_cls.INACTIVE)
