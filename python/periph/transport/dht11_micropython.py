"""DHT11 pin adapter for MicroPython (wraps ``machine.Pin``).

Provides a uniform ``DHT11Pin`` interface that the DHT11 driver uses to
switch the DATA line between output and input. The user supplies a
``machine.Pin`` already created (the adapter does not own its configuration).

Args:
    pin: ``machine.Pin`` instance (any direction; the adapter reconfigures it).
"""


class DHT11Pin:
    """DHT11 pin adapter for MicroPython (wraps ``machine.Pin``).

    Reconfigures the underlying ``machine.Pin`` between ``Pin.OUT`` and
    ``Pin.IN`` on every direction change. The pin is left configured as
    input (high-impedance) when ``close()`` is called.

    Args:
        pin: ``machine.Pin`` instance.
    """

    def __init__(self, pin):
        self._pin = pin

    def set_output(self):
        """Configure the pin as output (driven by the host)."""
        self._pin.init(self._pin.OUT)

    def set_input(self):
        """Configure the pin as input (high-impedance, pulled HIGH by 4.7 kΩ)."""
        self._pin.init(self._pin.IN)

    def drive(self, high):
        """Drive the pin HIGH (``True``) or LOW (``False``)."""
        self._pin.value(1 if high else 0)

    def read(self):
        """Read the current logic level of the pin.

        Returns:
            bool: ``True`` for HIGH, ``False`` for LOW.
        """
        return self._pin.value() == 1

    def close(self):
        """Release the pin. No-op on MicroPython; provided for interface consistency."""
        pass
