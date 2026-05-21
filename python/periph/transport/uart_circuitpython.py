import time

from .base import Transport


class UARTTransport(Transport):
    """UART transport for CircuitPython (wraps busio.UART).

    The caller constructs and configures the UART instance before passing it
    to this transport. When de_pin is provided the transport operates in
    RS-485 mode: DE is asserted before each transmit and deasserted only after
    all bytes have physically shifted out of the hardware FIFO.

    Args:
        uart: Configured busio.UART instance.
        baudrate: Baud rate of the UART; used to calculate the TX-drain delay
            in RS-485 mode. Must match the baud rate the UART was configured
            with.
        de_pin: digitalio.DigitalInOut for RS-485 direction-enable (active
            high). Pass None (default) for plain UART mode.
    """

    def __init__(self, uart, baudrate, de_pin=None):
        self._uart = uart
        self._baudrate = baudrate
        self._de = de_pin
        if self._de is not None:
            self._de.value = False

    def _drain_delay_us(self, n_bytes):
        # 10 bits per frame (start + 8 data + stop); 100 µs margin.
        return (n_bytes * 10 * 1_000_000) // self._baudrate + 100

    def write(self, data):
        """Transmit bytes; in RS-485 mode assert DE, transmit, drain TX FIFO,
        then deassert DE.

        Args:
            data: Bytes to transmit.
        """
        if self._de is not None:
            self._de.value = True
        self._uart.write(bytes(data))
        if self._de is not None:
            time.sleep_us(self._drain_delay_us(len(data)))
            self._de.value = False

    def read(self, n):
        """Receive n bytes; blocks until n bytes arrive or the UART times out.

        Args:
            n: Number of bytes to read.

        Returns:
            bytes: Data received from the device.

        Raises:
            OSError: If the read times out or returns fewer than n bytes.
        """
        result = self._uart.read(n)
        if result is None or len(result) < n:
            raise OSError('UART read timeout')
        return bytes(result)

    def write_read(self, data, n):
        """Transmit bytes then receive n bytes.

        In RS-485 mode DE is asserted only during the transmit phase.

        Args:
            data: Bytes to transmit.
            n: Number of bytes to receive.

        Returns:
            bytes: Data received from the device.
        """
        self.write(data)
        return self.read(n)
