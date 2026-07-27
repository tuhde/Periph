from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.bmp180 import BMP180Minimal

connection = I2CConnection(0x77)
bmp = BMP180Minimal(connection)                           # Create BMP180 driver, (connection)

for _ in range(5):
    t = bmp.temperature()                                # Read temperature, () → float C
    p = bmp.pressure()                                  # Read pressure, () → float hPa
    print('{} C, {} hPa'.format(t, p))
    machine.sleep(1000)
print('===DONE: 0 passed, 0 failed===')
