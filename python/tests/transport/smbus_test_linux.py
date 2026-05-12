import os
import sys

from periph.transport.smbus_linux import SMBusTransport

I2C_BUS  = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x40'), 16)

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


# --- offline: address validation (no bus needed) ---

class _MockBus:
    pass

try:
    SMBusTransport(_MockBus(), 0x07)
    check_true('addr 0x07 rejected', False)
except ValueError:
    check_true('addr 0x07 rejected', True)

try:
    SMBusTransport(_MockBus(), 0x78)
    check_true('addr 0x78 rejected', False)
except ValueError:
    check_true('addr 0x78 rejected', True)

# --- online: basic I/O without PEC ---

transport = SMBusTransport(I2C_BUS, I2C_ADDR)

data = transport.read(1)
check_true('read returns 1 byte', len(data) == 1)

transport.write(bytes([0x00]))
check_true('write accepted', True)

data = transport.write_read(bytes([0x00]), 1)
check_true('write_read returns 1 byte', len(data) == 1)

# --- online: write with PEC enabled ---

transport.close()
transport_pec = SMBusTransport(I2C_BUS, I2C_ADDR, pec=True)
transport_pec.write(bytes([0x00]))
check_true('write with PEC accepted', True)
transport_pec.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
