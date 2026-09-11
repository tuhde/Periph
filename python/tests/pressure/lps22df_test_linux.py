import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.pressure.lps22df import LPS22DFFull

passed = 0
failed = 0

I2C_BUS  = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x5C'), 16)

connection = I2CConnection(I2C_BUS, I2C_ADDR)
lps = LPS22DFFull(connection)

t = lps.temperature()
if -40.0 <= t <= 85.0:
    print('PASS temperature_range'); passed += 1
else:
    print('FAIL temperature_range: got {}'.format(t)); failed += 1

p = lps.pressure()
if 26000.0 <= p <= 126000.0:
    print('PASS pressure_range'); passed += 1
else:
    print('FAIL pressure_range: got {}'.format(p)); failed += 1

lps.configure(odr=4, avg=1, en_lpfp=True, lfpf_cfg=1, bdu=True)
p2 = lps.pressure()
if 26000.0 <= p2 <= 126000.0:
    print('PASS configure_then_read'); passed += 1
else:
    print('FAIL configure_then_read: got {}'.format(p2)); failed += 1

alt = lps.altitude(101325.0)
if -500.0 <= alt <= 10000.0:
    print('PASS altitude'); passed += 1
else:
    print('FAIL altitude: got {}'.format(alt)); failed += 1

lps.set_pressure_threshold(102000.0)
lps.configure_interrupt(int_en=True)
src = lps.interrupt_source()
if 'ia' in src and 'ph' in src:
    print('PASS interrupt_source_dict'); passed += 1
else:
    print('FAIL interrupt_source_dict: got {}'.format(src)); failed += 1

connection.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))