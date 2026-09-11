from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.lps28dfw import LPS28DFWMinimal

connection = I2CConnection(0x5C)
lps = LPS28DFWMinimal(connection)                             # Create LPS28DFW driver, (connection)

for _ in range(5):
    t = lps.read_temperature()                                # Read temperature, () → float °C
    p = lps.read_pressure()                                   # Read pressure, () → float hPa
    print('{} C, {} hPa'.format(t, p))
    machine.sleep(1000)
print('===DONE: 0 passed, 0 failed===')