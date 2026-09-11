import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.pressure.bmp581 import BMP581Minimal, BMP581Full

passed = 0
failed = 0

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x46'), 16)

connection = I2CConnection(I2C_BUS, I2C_ADDR)

bmp = BMP581Minimal(connection)

raw_t = 0x007E00
raw_p = 0x003D65
if raw_t & 0x800000:
    raw_t -= 0x1000000
if raw_p & 0x800000:
    raw_p -= 0x1000000
t = raw_t / 65536.0
p = raw_p / 64.0
if abs(t - 0.0625) < 1e-6:
    print('PASS temperature_decode')
    passed += 1
else:
    print('FAIL temperature_decode: got {}'.format(t))
    failed += 1
if abs(p - 0.0625) < 1e-6:
    print('PASS pressure_decode')
    passed += 1
else:
    print('FAIL pressure_decode: got {}'.format(p))
    failed += 1

bmp_full = BMP581Full(connection)
bmp_full.set_mode(BMP581Full.MODE_NORMAL)
if bmp_full._pwr_mode == 1:
    print('PASS set_mode')
    passed += 1
else:
    print('FAIL set_mode: got {}'.format(bmp_full._pwr_mode))
    failed += 1

bmp_full.configure(odr=0x17, osr_p=4, osr_t=2, press_en=True)
if bmp_full._odr == 0x17 and bmp_full._osr_p == 4 and bmp_full._osr_t == 2:
    print('PASS configure')
    passed += 1
else:
    print('FAIL configure: got odr={} osr_p={} osr_t={}'.format(bmp_full._odr, bmp_full._osr_p, bmp_full._osr_t))
    failed += 1

bmp_full.set_iir_filter(BMP581Full.IIR_COEFF_3, BMP581Full.IIR_BYPASS)
print('PASS set_iir_filter (no exception)')
passed += 1

bmp_full.enable_drdy_interrupt(True)
print('PASS enable_drdy_interrupt (no exception)')
passed += 1

bmp_full.configure_fifo(BMP581Full.FIFO_BOTH, mode=BMP581Full.FIFO_STREAM, threshold=8)
print('PASS configure_fifo (no exception)')
passed += 1

bmp_full.set_oor_threshold(110000, 200, 1)
print('PASS set_oor_threshold (no exception)')
passed += 1

alt = bmp_full.altitude()
if alt >= -500 and alt <= 9000:
    print('PASS altitude')
    passed += 1
else:
    print('FAIL altitude: got {}'.format(alt))
    failed += 1

connection.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))