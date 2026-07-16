import os
import sys

from periph.transport.spi_linux import SPITransport
from periph.chips.gnss.neo6 import NEO6Minimal

SPI_BUS    = int(os.environ.get('LINUX_SPI_BUS', '0'))
SPI_DEVICE = int(os.environ.get('LINUX_SPI_DEVICE', '0'))

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


# Requires a NEO-6 module wired to SPI (mode 0, <=200 kHz) with a clear sky
# view. Achieving an actual fix needs an outdoor antenna and can take up to
# ~26 s (cold start); this test only requires that well-typed values come
# back. SPI reads use write_read() with an empty command so every response
# byte is captured (see NEO6Minimal._read_byte).
transport = SPITransport(SPI_BUS, SPI_DEVICE, mode=0, max_speed_hz=200_000)
gps = NEO6Minimal(transport, bus_type='spi')

check_true('fix() starts at 0', gps.fix() == 0)
check_true('latitude() starts at None', gps.latitude() is None)

for _ in range(3000):
    gps.update()

check_true('fix() is a valid quality code', gps.fix() in (0, 1, 2))
check_true('satellites() is a non-negative int', gps.satellites() >= 0)
if gps.fix() > 0:
    check_true('latitude() in range once fixed', -90.0 <= gps.latitude() <= 90.0)
    check_true('longitude() in range once fixed', -180.0 <= gps.longitude() <= 180.0)
    check_true('altitude() is populated once fixed', gps.altitude() is not None)
else:
    print('note: no fix acquired during the test window (needs sky view)')

transport.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
