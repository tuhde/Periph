import busio
import time
from periph.connection.spi_circuitpython import SPIConnection
from periph.chips.comms.mcp2515 import MCP2515Minimal, MCP2515Full, CanFrame

passed = 0
failed = 0

def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label); passed += 1
    else:
        print('FAIL {}: got {!r}, expected {!r}'.format(label, got, expected)); failed += 1

def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label); passed += 1
    else:
        print('FAIL', label); failed += 1


import board
import digitalio

cs = digitalio.DigitalInOut(board.D5)
cs.direction = digitalio.Direction.OUTPUT
spi = busio.SPI(board.SCK, MOSI=board.MOSI, MISO=board.MISO)
connection = SPIConnection(spi, cs)
can = MCP2515Minimal(connection)

try:
    CanFrame(0x800, b'', extended=False)
    check_true('CanFrame rejects standard id > 0x7FF', False)
except ValueError:
    check_true('CanFrame rejects standard id > 0x7FF', True)

can_full = MCP2515Full(connection)
can_full.set_mode('loopback')
check_eq('set_mode loopback', can_full.get_mode(), 'loopback')

errs = can_full.read_errors()
check_true('read_errors has tec', 'tec' in errs)
check_true('read_errors has rec', 'rec' in errs)
check_true('read_errors has eflg', 'eflg' in errs)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
