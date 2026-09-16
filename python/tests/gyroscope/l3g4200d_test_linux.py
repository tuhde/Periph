import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.gyroscope.l3g4200d import L3G4200DMinimal, L3G4200DFull

passed = 0
failed = 0

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x68'), 16)

connection = I2CConnection(I2C_BUS, I2C_ADDR)

gyro = L3G4200DMinimal(connection)
if gyro is not None:
    print('PASS init')
    passed += 1
else:
    print('FAIL init')
    failed += 1

x, y, z = gyro.angular_rate()
print('PASS angular_rate x={:.3f} y={:.3f} z={:.3f} rad/s'.format(x, y, z))
passed += 1

gyro_full = L3G4200DFull(connection)
if gyro_full is not None:
    print('PASS full_init')
    passed += 1
else:
    print('FAIL full_init')
    failed += 1

if gyro_full.who_am_i() == 0xD3:
    print('PASS who_am_i')
    passed += 1
else:
    print('FAIL who_am_i')
    failed += 1

gyro_full.configure(odr=200, bandwidth=0, full_scale=500)
if gyro_full._full_scale == 500:
    print('PASS configure')
    passed += 1
else:
    print('FAIL configure')
    failed += 1

status = gyro_full.status()
print('PASS status 0x{:02X}'.format(status))
passed += 1

connection.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))
