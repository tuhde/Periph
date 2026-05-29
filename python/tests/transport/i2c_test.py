import sys

from periph.transport.i2c_auto import I2CTransport

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


# --- online: bus scan ---
# Probe every non-reserved I²C address; a responding device proves the
# transport can open the bus and perform I/O without depending on any
# specific chip.

found = []
for probe_addr in range(0x08, 0x78):
    t = I2CTransport(probe_addr)
    try:
        t.read(1)
        found.append(probe_addr)
    except OSError:
        pass
    finally:
        t.close()

check_true('bus scan: at least one device found', len(found) > 0)
print('devices found:', [hex(a) for a in found])

print('===DONE: {} passed, {} failed==='.format(passed, failed))
try:
    sys.exit(0 if failed == 0 else 1)
except AttributeError:
    pass  # MicroPython has no sys.exit
