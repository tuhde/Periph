import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.pressure.mpl115a2 import MPL115A2Full

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
connection = I2CConnection(I2C_BUS, 0x60)

passed = 0
failed = 0


def check(label, ok, detail=''):
    global passed, failed
    if ok:
        print('PASS ' + label)
        passed += 1
    else:
        print('FAIL ' + label + (': ' + detail if detail else ''))
        failed += 1


sensor = MPL115A2Full(connection)
p = sensor.read_pressure()
check('pressure_range', 50000 <= p <= 115000, str(p))
t = sensor.read_temperature()
check('temperature_range', -40 <= t <= 105, str(t))
padc, tadc = sensor.read_raw()
check('raw_range', 0 <= padc <= 1023 and 0 <= tadc <= 1023, str((padc, tadc)))
check('compensate_matches', 50000 <= sensor.compensate(padc, tadc) <= 115000)
r = sensor.read()
check('read_keys', 'pressure_pa' in r and 'temperature_c' in r)
check('pressure_hpa', 500 <= sensor.pressure_hpa() <= 1150)
check('altitude', -1000 <= sensor.altitude() <= 6000)
sensor.reload_coefficients()
check('coefficients', len(sensor.coefficients()) == 4)

print('===DONE: {} passed, {} failed==='.format(passed, failed))

connection.close()
