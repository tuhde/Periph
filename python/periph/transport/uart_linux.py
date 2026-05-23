import serial
import serial.rs485

from .base import Transport


class UARTTransport(Transport):
    """UART transport for Linux (wraps pyserial).

    Opens and configures the serial port at construction. Call close() to
    release the port when done.

    For RS-485, the transport first tries kernel-level RS-485 mode via
    ``serial.rs485.RS485Settings``. If the driver does not support kernel
    RS-485 and a ``de_pin_num`` is provided, it falls back to manual GPIO
    toggling via the ``python-periphery`` ``GPIO`` class.

    Args:
        port: Serial device path (e.g. ``/dev/ttyS0``, ``/dev/ttyUSB0``).
        baudrate: Baud rate; default 9600.
        data_bits: Data bits (5–8); default 8.
        stop_bits: Stop bits (1, 1.5, or 2); default 1.
        parity: Parity — ``'N'`` none, ``'E'`` even, ``'O'`` odd; default
            ``'N'``.
        timeout_s: Read timeout in seconds; default 1.0.
        de_pin_num: GPIO line number for RS-485 direction-enable (active
            high). Enables RS-485 mode when set; default None.
    """

    def __init__(self, port, baudrate=9600, data_bits=8, stop_bits=1,
                 parity='N', timeout_s=1.0, de_pin_num=None):
        stopbits_map = {1: serial.STOPBITS_ONE, 1.5: serial.STOPBITS_ONE_POINT_FIVE,
                        2: serial.STOPBITS_TWO}
        parity_map = {'N': serial.PARITY_NONE, 'E': serial.PARITY_EVEN,
                      'O': serial.PARITY_ODD}

        self._ser = serial.Serial(
            port=port,
            baudrate=baudrate,
            bytesize=data_bits,
            stopbits=stopbits_map[stop_bits],
            parity=parity_map[parity],
            timeout=timeout_s,
        )
        self._de_gpio = None
        self._rs485_kernel = False

        if de_pin_num is not None:
            try:
                self._ser.rs485_mode = serial.rs485.RS485Settings(
                    rts_level_for_sending=True,
                    rts_level_for_receiving=False,
                )
                self._rs485_kernel = True
            except (AttributeError, serial.SerialException):
                from periphery import GPIO
                self._de_gpio = GPIO('/dev/gpiochip0', de_pin_num, 'out')
                self._de_gpio.write(False)

    def write(self, data):
        """Transmit bytes; in RS-485 mode asserts DE via kernel or GPIO,
        transmits, drains the OS buffer, then deasserts DE.

        Args:
            data: Bytes to transmit.

        Raises:
            OSError: If the write fails.
        """
        if self._de_gpio is not None:
            self._de_gpio.write(True)
        n = self._ser.write(data)
        if n != len(data):
            raise OSError('UART write failed: wrote %d of %d bytes' % (n, len(data)))
        self._ser.flush()
        if self._de_gpio is not None:
            self._de_gpio.write(False)

    def read(self, n):
        """Receive n bytes; blocks until n bytes arrive or the read timeout
        expires.

        Args:
            n: Number of bytes to read.

        Returns:
            bytes: Data received from the device.

        Raises:
            OSError: If fewer than n bytes arrive within the timeout.
        """
        data = self._ser.read(n)
        if len(data) < n:
            raise OSError('UART read timeout: got %d of %d bytes' % (len(data), n))
        return data

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

    def close(self):
        """Release the serial port and, if applicable, the DE GPIO."""
        self._ser.close()
        if self._de_gpio is not None:
            self._de_gpio.close()
