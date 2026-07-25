import os

try:
    from machine import UART as _UART, Pin as _Pin
    from .uart_micropython import UARTTransport as _UARTTransport

    def UARTTransport(port=1, baudrate=9600, tx=None, rx=None, de_pin=None):
        """Create a UART transport for MicroPython.

        Args:
            port:     UART peripheral id (default 1).
            baudrate: Baud rate (default 9600).
            tx:       TX pin number (optional; uses UART default if None).
            rx:       RX pin number (optional; uses UART default if None).
            de_pin:   RS-485 DE pin number (optional).
        """
        kwargs = {'baudrate': baudrate}
        if tx is not None:
            kwargs['tx'] = _Pin(tx)
        if rx is not None:
            kwargs['rx'] = _Pin(rx)
        uart = _UART(port, **kwargs)
        de = _Pin(de_pin, _Pin.OUT) if de_pin is not None else None
        return _UARTTransport(uart, baudrate=baudrate, de_pin=de)

except ImportError:
    try:
        import board as _board
        import busio as _busio
        from .uart_circuitpython import UARTTransport as _UARTTransport

        def UARTTransport(port=None, baudrate=9600, tx=None, rx=None, de_pin=None):
            """Create a UART transport for CircuitPython.

            Args:
                port:     Ignored (CircuitPython uses board TX/RX pins).
                baudrate: Baud rate (default 9600).
                tx:       TX board pin; defaults to board.TX.
                rx:       RX board pin; defaults to board.RX.
                de_pin:   RS-485 DE pin (digitalio.DigitalInOut, optional).
            """
            tx_pin = tx if tx is not None else _board.TX
            rx_pin = rx if rx is not None else _board.RX
            uart = _busio.UART(tx_pin, rx_pin, baudrate=baudrate)
            return _UARTTransport(uart, baudrate=baudrate, de_pin=de_pin)

    except ImportError:
        from .uart_linux import UARTTransport as _UARTTransport

        def UARTTransport(port=None, baudrate=9600, **kwargs):
            """Create a UART transport for Linux (pyserial).

            Args:
                port:     Serial device path or integer (e.g. '/dev/ttyS0' or 0
                          which maps to '/dev/ttyS0'). Defaults to LINUX_UART_PORT
                          env var, then '/dev/ttyS0'.
                baudrate: Baud rate (default 9600).
                **kwargs: Forwarded to UARTTransport (data_bits, stop_bits, parity,
                          rs485, de_pin_num).
            """
            if port is None:
                port = os.environ.get('LINUX_UART_PORT', '/dev/ttyS0')
            if isinstance(port, int):
                port = f'/dev/ttyS{port}'
            return _UARTTransport(port, baudrate=baudrate, **kwargs)
