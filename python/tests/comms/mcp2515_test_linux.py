import os
import sys
from periph.connection.spi_linux import SPIConnection
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


bus = int(os.environ.get('SPI_BUS', '0'))
device = int(os.environ.get('SPI_DEVICE', '0'))

connection = SPIConnection(bus_num=bus, device_num=device, mode=0, max_speed_hz=10_000_000)
can = MCP2515Minimal(connection)

try:
    CanFrame(0x800, b'', extended=False)
    check_true('CanFrame rejects standard id > 0x7FF', False)
except ValueError:
    check_true('CanFrame rejects standard id > 0x7FF', True)

can_full = MCP2515Full(connection)
can_full.set_mode('loopback')
check_eq('set_mode loopback', can_full.get_mode(), 'loopback')

can_full.set_one_shot(True)
check_eq('set_one_shot enable', can_full._read_reg(0x0F) & 0x08, 0x08)

can_full.set_one_shot(False)
check_eq('set_one_shot disable', can_full._read_reg(0x0F) & 0x08, 0x00)

errs = can_full.read_errors()
check_true('read_errors has tec', 'tec' in errs)
check_true('read_errors has rec', 'rec' in errs)
check_true('read_errors has eflg', 'eflg' in errs)

connection.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
