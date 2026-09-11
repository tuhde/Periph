from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.lps22df import LPS22DFMinimal

connection = I2CConnection(0x5C)
lps = LPS22DFMinimal(connection)                           # Create LPS22DF driver, (connection, bus_type='i2c')

for _ in range(5):
    p = lps.pressure()                                   # Read pressure, () → float Pa
    t = lps.temperature()                                # Read temperature, () → float °C
    print('{} Pa, {} C'.format(int(p), t))
    machine.sleep(1000)
print('===DONE: 0 passed, 0 failed===')