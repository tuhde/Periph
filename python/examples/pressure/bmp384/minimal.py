from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.bmp384 import BMP384Minimal

connection = I2CConnection(0x76)
bmp = BMP384Minimal(connection)                          # Create BMP384 driver, (connection, bus_type='i2c')

for _ in range(5):
    t = bmp.temperature()                               # Read temperature, () → float °C
    p = bmp.pressure()                                  # Read pressure, () → float hPa
    print('{} C, {} hPa'.format(t, p))
    import machine; machine.sleep(1000)
print('===DONE: 0 passed, 0 failed===')
