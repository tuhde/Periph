"""DHT11 pin adapter for CircuitPython (wraps ``digitalio.DigitalInOut``).

Provides a uniform ``DHT11Pin`` interface that the DHT11 driver uses to
switch the DATA line between output and input. The user supplies a
``digitalio.DigitalInOut`` already created (the adapter does not own its
configuration).

Args:
    pin: ``digitalio.DigitalInOut`` instance (any direction; the adapter reconfigures it).
"""

import digitalio


class DHT11Pin:
    """DHT11 pin adapter for CircuitPython (wraps ``digitalio.DigitalInOut``).

    Reconfigures the underlying ``DigitalInOut`` between ``Direction.OUTPUT``
    and ``Direction.INPUT`` on every direction change. ``close()`` deinits
    the pin.

    Args:
        pin: ``digitalio.DigitalInOut`` instance.
    """

    def __init__(self, pin):
        self._pin = pin

    def set_output(self):
        """Configure the pin as output (driven by the host)."""
        self._pin.direction = digitalio.Direction.OUTPUT

    def set_input(self):
        """Configure the pin as input (high-impedance, pulled HIGH by 4.7 kΩ)."""
        self._pin.direction = digitalio.Direction.INPUT

    def drive(self, high):
        """Drive the pin HIGH (``True``) or LOW (``False``)."""
        self._pin.value = bool(high)

    def read(self):
        """Read the current logic level of the pin.

        Returns:
            bool: ``True`` for HIGH, ``False`` for LOW.
        """
        return bool(self._pin.value)

    def close(self):
        """Deinit the underlying pin and release it back to the system."""
        self._pin.deinit()
