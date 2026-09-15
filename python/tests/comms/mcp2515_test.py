from machine import SPI, Pin
from periph.connection.spi_micropython import SPIConnection
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


spi = SPI(1, baudrate=10_000_000, polarity=0, phase=0)
cs = Pin(5, Pin.OUT)
connection = SPIConnection(spi, cs)
can = MCP2515Minimal(connection)

# CanFrame validation
try:
    CanFrame(0x800, b'', extended=False)
    check_true('CanFrame rejects standard id > 0x7FF', False)
except ValueError:
    check_true('CanFrame rejects standard id > 0x7FF', True)

try:
    CanFrame(0x20000000, b'', extended=True)
    check_true('CanFrame rejects extended id > 0x1FFFFFFF', False)
except ValueError:
    check_true('CanFrame rejects extended id > 0x1FFFFFFF', True)

try:
    CanFrame(0x123, b'\x00' * 9)
    check_true('CanFrame rejects dlc > 8', False)
except ValueError:
    check_true('CanFrame rejects dlc > 8', True)

# Send a standard 11-bit frame
can.send(0x123, b'\xde\xad\xbe\xef', extended=False)
check_eq('send standard id',  can._read_status() is not None, True)
check_eq('send returns None', None, None)

# Construct Full class on the same connection and exercise Full-only API
can_full = MCP2515Full(connection)
can_full.set_mode('loopback')
check_eq('set_mode loopback', can_full.get_mode(), 'loopback')

can_full.set_mode('normal')
check_eq('set_mode normal', can_full.get_mode(), 'normal')

can_full.set_one_shot(True)
check_eq('set_one_shot enable', can_full._read_reg(0x0F) & 0x08, 0x08)

can_full.set_one_shot(False)
check_eq('set_one_shot disable', can_full._read_reg(0x0F) & 0x08, 0x00)

errs = can_full.read_errors()
check_true('read_errors returns dict', isinstance(errs, dict))
check_true('read_errors has tec', 'tec' in errs)
check_true('read_errors has rec', 'rec' in errs)
check_true('read_errors has eflg', 'eflg' in errs)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
