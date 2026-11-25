import time
import busio
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.memory.mb85rc import MB85RCFull

passed = 0
failed = 0


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got {!r}, expected {!r}'.format(label, got, expected))
        failed += 1


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR, reg_bytes=2)
fram = MB85RCFull(connection)

check_eq('size', fram.size(), 32768)

fram.write_byte(0x0010, 0x5A)
check_eq('write_byte/read_byte round-trip', fram.read_byte(0x0010), 0x5A)

DATA = bytes(range(100))
fram.write(0x0100, DATA)
check_eq('chunked write/read round-trip', fram.read(0x0100, len(DATA)), DATA)

fram.fill(0x0100, 100, 0xC3)
check_eq('fill', fram.read(0x0100, 100), bytes([0xC3]) * 100)
fram.fill(0x0100, 100)
check_eq('fill default zero', fram.read(0x0100, 100), bytes(100))

fram.write(0x7FFE, b'\x12\x34')
check_eq('last bytes round-trip', fram.read(0x7FFE, 2), b'\x12\x34')

try:
    fram.read(0x7FFF, 2)
    check_true('wrap rejected', False)
except ValueError:
    check_true('wrap rejected', True)

try:
    id_connection = I2CConnection(i2c, 0x7C)
    check_eq('read_device_id', fram.read_device_id(id_connection, cfg.ADDR), (0x00A, 0x5, 0x10))
except OSError:
    print('SKIP read_device_id (0x7C not accessible on this host)')

i2c.deinit()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
