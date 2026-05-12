import busio
import _testconfig as cfg
from periph.transport.smbus_circuitpython import SMBusTransport

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


# --- offline: address validation ---

try:
    SMBusTransport(None, 0x07)
    check_true('addr 0x07 rejected', False)
except ValueError:
    check_true('addr 0x07 rejected', True)

try:
    SMBusTransport(None, 0x78)
    check_true('addr 0x78 rejected', False)
except ValueError:
    check_true('addr 0x78 rejected', True)

# --- online: basic I/O without PEC ---

i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
transport = SMBusTransport(i2c, cfg.ADDR)

data = transport.read(1)
check_true('read returns 1 byte', len(data) == 1)

transport.write(bytes([0x00]))
check_true('write accepted', True)

data = transport.write_read(bytes([0x00]), 1)
check_true('write_read returns 1 byte', len(data) == 1)

# --- online: write with PEC enabled ---

transport_pec = SMBusTransport(i2c, cfg.ADDR, pec=True)
transport_pec.write(bytes([0x00]))
check_true('write with PEC accepted', True)

i2c.deinit()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
