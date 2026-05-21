import busio
import board
import _testconfig as cfg
from periph.transport.i2c_circuitpython import I2CTransport
from periph.chips.pressure.bmp280 import BMP280Minimal, BMP280Full

passed = 0
failed = 0

i2c = busio.I2C(board.SCL, board.SDA)
transport = I2CTransport(i2c, 0x76)

bmp = BMP280Minimal(transport)

# Validate compensation using datasheet example values
bmp._dig = (27504, 26435, -1000,
            36477, -10685, 3024, 2855, 140, -7,
            15500, -14600, 6000)
bmp._t_fine = 0

adc_t = 519888
adc_p = 415148

t = bmp._compensate_temp(adc_t)
p = bmp._compensate_pressure(adc_p)

if abs(t - 25.08) < 0.01:
    print('PASS temperature_compensation')
    passed += 1
else:
    print('FAIL temperature_compensation: expected 25.08, got {}'.format(t))
    failed += 1

if abs(p - 1006.53) < 0.01:
    print('PASS pressure_compensation')
    passed += 1
else:
    print('FAIL pressure_compensation: expected 1006.53, got {}'.format(p))
    failed += 1

bmp_full = BMP280Full(transport, osrs_t=1, osrs_p=1)
if bmp_full.chip_id() == 0x58:
    print('PASS chip_id')
    passed += 1
else:
    print('FAIL chip_id: expected 0x58, got 0x{:02X}'.format(bmp_full.chip_id()))
    failed += 1

status = bmp_full.status()
if 0 <= status <= 0xFF:
    print('PASS status')
    passed += 1
else:
    print('FAIL status: out of range')
    failed += 1

bmp_full.set_oversampling(osrs_t=3, osrs_p=3)
bmp_full.set_filter(BMP280Full.FILTER_8)
bmp_full.set_standby(BMP280Full.T_SB_250_MS)

alt = bmp_full.altitude(1013.25)
if alt >= -500 and alt <= 10000:
    print('PASS altitude')
    passed += 1
else:
    print('FAIL altitude: out of reasonable range {}'.format(alt))
    failed += 1

slp = bmp_full.sea_level_pressure(0)
if 800 <= slp <= 1100:
    print('PASS sea_level_pressure')
    passed += 1
else:
    print('FAIL sea_level_pressure: out of range {}'.format(slp))
    failed += 1

bmp_full.configure(osrs_t=5, osrs_p=5, mode=BMP280Full.MODE_NORMAL,
                   filter=BMP280Full.FILTER_16, t_sb=BMP280Full.T_SB_500_MS)
print('PASS configure')
passed += 1

bmp_full.reset()
print('PASS reset')
passed += 1

print('===DONE: {} passed, {} failed==='.format(passed, failed))