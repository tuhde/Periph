from periph.connection.uart_auto import UARTConnection as _periph_uart_conn
from periph.chips.motor.tmc2209 import TMC2209Full as _TMC2209Full

_periph_tmc2209 = _TMC2209Full(_periph_uart_conn(port=${_port}, baudrate=${_baudrate}, tx=${_tx}, rx=${_rx}), r_sense_ohm=${_rsense}, address=${_addr})
