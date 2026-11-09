from periph.connection.uart_auto import UARTConnection as _periph_uart_conn
from periph.chips.motor.tmc2208 import TMC2208Full as _TMC2208Full

_periph_tmc2208 = _TMC2208Full(_periph_uart_conn(port=${_port}, baudrate=${_baudrate}, tx=${_tx}, rx=${_rx}), r_sense_ohm=${_rsense})
