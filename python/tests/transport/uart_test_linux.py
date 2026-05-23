import os
import sys

from periph.transport.uart_linux import UARTTransport

UART_PORT     = os.environ.get('UART_PORT',     '/dev/ttyS0')
UART_BAUDRATE = int(os.environ.get('UART_BAUD', '9600'))

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
transport = UARTTransport(UART_PORT, baudrate=UART_BAUDRATE, timeout_s=1.0)

transport.write(bytes([0x42]))
check_true('write accepted', True)

data = transport.read(1)
check_true('read returns 1 byte', len(data) == 1)
check_true('loopback byte matches', data[0] == 0x42)

result = transport.write_read(bytes([0xA5, 0x5A]), 2)
check_true('write_read returns 2 bytes', len(result) == 2)
check_true('write_read loopback matches', result == bytes([0xA5, 0x5A]))

transport.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
