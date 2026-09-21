"""Linux hardware test for the ADXL362 — runs against a Raspberry Pi.

Verifies the chip's identity, performs a single 6-byte burst read,
and exercises the Full driver's range configuration.
"""

import os
from periph.connection.spi_linux import SPIConnection
from periph.chips.accelerometer.adxl362 import ADXL362Minimal, ADXL362Full

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


SPI_BUS = int(os.environ.get('SPI_BUS', '0'))
SPI_DEVICE = int(os.environ.get('SPI_DEVICE', '0'))

connection = SPIConnection(SPI_BUS, SPI_DEVICE, mode=0, max_speed_hz=8_000_000)

accel = ADXL362Minimal(connection)                                                # Construct minimal driver, (connection) → ADXL362Minimal
check_true('construct_minimal', isinstance(accel, ADXL362Minimal))

x, y, z = accel.read()                                                            # Read 3-axis acceleration, () → (float, float, float) g
check_true('read_returns_three_floats',
           isinstance(x, float) and isinstance(y, float) and isinstance(z, float))
check_true('read_magnitude_near_1g',
           abs((x * x + y * y + z * z) ** 0.5 - 1.0) < 0.5)

accel_full = ADXL362Full(connection)                                              # Construct full driver, (connection) → ADXL362Full
check_true('construct_full', isinstance(accel_full, ADXL362Full))

devad, devmst, partid, revid = accel_full.device_id()                             # Read device IDs, () → (int, int, int, int)
check_true('devid_ad_0xAD', devad == 0xAD)                                        # verify DEVID_AD = 0xAD
check_true('devid_mst_0x1D', devmst == 0x1D)                                      # verify DEVID_MST = 0x1D
check_true('partid_0xF2',    partid == 0xF2)                                      # verify PARTID = 0xF2

accel_full.set_range(4)                                                           # Set measurement range, (range_g=4) → None
x, y, z = accel_full.read()                                                       # Read 3-axis acceleration, () → (float, float, float) g
check_true('read_after_set_range_4g',
           isinstance(x, float) and isinstance(y, float) and isinstance(z, float))

connection.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))