import _testconfig as cfg
from machine import UART, Pin
from periph.transport.uart_micropython import UARTTransport

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
uart = UART(cfg.UART_ID, baudrate=cfg.UART_BAUD, tx=Pin(cfg.UART_TX), rx=Pin(cfg.UART_RX),
            timeout=1000)
transport = UARTTransport(uart, cfg.UART_BAUD)

transport.write(bytes([0x42]))
check_true('write accepted', True)

data = transport.read(1)
check_true('read returns 1 byte', len(data) == 1)
check_true('loopback byte matches', data[0] == 0x42)

result = transport.write_read(bytes([0xA5, 0x5A]), 2)
check_true('write_read returns 2 bytes', len(result) == 2)
check_true('write_read loopback matches', result == bytes([0xA5, 0x5A]))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
