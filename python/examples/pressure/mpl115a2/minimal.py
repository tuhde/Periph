from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.mpl115a2 import MPL115A2Minimal

connection = I2CConnection(0x60)
baro = MPL115A2Minimal(connection)                      # Create MPL115A2 driver, (connection)

for _ in range(5):
    p = baro.read_pressure()                            # Read pressure, () → float Pa
    t = baro.read_temperature()                         # Read temperature, () → float C (approximate)
    print('{} Pa, {} C'.format(p, t))
    machine.sleep(1000)
print('===DONE: 0 passed, 0 failed===')
