import busio
import board
import digitalio
from periph.transport.uart_circuitpython import UARTTransport

passed = 0
failed = 0


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


# Assumes a loopback jumper bridging TXD and RXD on the UART port.
uart = busio.UART(board.TX, board.RX, baudrate=9600, timeout=1.0)
transport = UARTTransport(uart, 9600)

transport.write(bytes([0x42]))
check_true('write accepted', True)

data = transport.read(1)
check_true('read returns 1 byte', len(data) == 1)
check_true('loopback byte matches', data[0] == 0x42)

result = transport.write_read(bytes([0xA5, 0x5A]), 2)
check_true('write_read returns 2 bytes', len(result) == 2)
check_true('write_read loopback matches', result == bytes([0xA5, 0x5A]))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
