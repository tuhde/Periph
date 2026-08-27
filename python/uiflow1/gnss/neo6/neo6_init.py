from periph.connection.uart_auto import UARTConnection as _periph_uart_conn
from periph.chips.gnss.neo6 import NEO6Full as _NEO6Full

_periph_neo6 = _NEO6Full(_periph_uart_conn(port=${_port}, baudrate=${_baudrate}, tx=${_tx}, rx=${_rx}))
