import os
import sys

from periph.connection.spi_linux import SPIConnection

SPI_BUS    = int(os.environ.get('LINUX_SPI_BUS',    '0'))
SPI_DEVICE = int(os.environ.get('LINUX_SPI_DEVICE', '0'))
SPI_MODE   = int(os.environ.get('LINUX_SPI_MODE',   '0'))
SPI_SPEED  = int(os.environ.get('LINUX_SPI_SPEED',  '1000000'))

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


connection = SPIConnection(SPI_BUS, SPI_DEVICE, mode=SPI_MODE, max_speed_hz=SPI_SPEED)

connection.write(bytes([0x00]))
check_true('write accepted', True)

data = connection.read(1)
check_true('read returns 1 byte', len(data) == 1)

data = connection.write_read(bytes([0x00]), 1)
check_true('write_read returns 1 byte', len(data) == 1)

connection.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
