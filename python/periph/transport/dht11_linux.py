"""DHT11 pin adapter for Linux (wraps ``gpiod.LineRequest`` + line offset).

The DHT11's bidirectional DATA line requires switching the line between
output (host driving) and input (host listening) within microseconds of
each other. libgpiod's ``gpiod`` v2 API does not allow changing direction
of an already-requested line; the line must be released and re-requested
with a different direction. This adapter holds a request id and line
offset pair, and re-requests the line as output or input on demand.

Args:
    chip_path: Path to the gpiochip device, e.g. ``/dev/gpiochip0``.
    line:      Line offset on the chip.
    consumer:  Consumer label string (default ``"periph-dht11"``).
"""

import gpiod
from gpiod.line import Direction, Value


class DHT11Pin:
    """DHT11 pin adapter for Linux (wraps ``gpiod.LineRequest`` + line offset).

    Holds the line offset and consumer label; re-requests the line on every
    direction change. ``close()`` releases any held request.

    Args:
        chip_path: Path to gpiochip (e.g. ``/dev/gpiochip0``).
        line:      Line offset on the chip.
        consumer:  Consumer label (default ``"periph-dht11"``).
    """

    def __init__(self, chip_path, line, consumer="periph-dht11"):
        self._chip_path = chip_path
        self._line = int(line)
        self._consumer = consumer
        self._request = None
        self._direction = None
        self._chip = gpiod.Chip(self._chip_path)

    def _release(self):
        if self._request is not None:
            try:
                self._request.release()
            except Exception:
                pass
            self._request = None

    def _request_dir(self, direction):
        if self._direction == direction and self._request is not None:
            return
        self._release()
        line_config = gpiod.LineConfig(
            direction=direction,
            output_value=Value.INACTIVE,
        )
        self._request = self._chip.request_lines(
            self._consumer, config={self._line: line_config}
        )
        self._direction = direction

    def set_output(self):
        """Configure the line as output (host drives the bus)."""
        self._request_dir(Direction.OUTPUT)

    def set_input(self):
        """Configure the line as input (host listens; pull-up holds HIGH)."""
        self._request_dir(Direction.INPUT)

    def drive(self, high):
        """Drive the line HIGH (``True``) or LOW (``False``).

        The line must currently be configured as output.
        """
        if self._request is None or self._direction != Direction.OUTPUT:
            self.set_output()
        self._request.set_value(self._line, Value.ACTIVE if high else Value.INACTIVE)

    def read(self):
        """Read the current logic level of the line.

        The line must currently be configured as input.

        Returns:
            bool: ``True`` for HIGH, ``False`` for LOW.
        """
        if self._request is None or self._direction != Direction.INPUT:
            self.set_input()
        return self._request.get_value(self._line) == Value.ACTIVE

    def close(self):
        """Release the line request and close the chip handle."""
        self._release()
        try:
            self._chip.close()
        except Exception:
            pass
