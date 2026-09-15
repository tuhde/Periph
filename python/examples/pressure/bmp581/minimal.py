from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.bmp581 import BMP581Minimal

connection = I2CConnection(0x46)
bmp = BMP581Minimal(connection)                          # Create BMP581 driver, (connection, bus_type='i2c')

for _ in range(5):
    p = bmp.pressure()                                   # Read pressure, () → float Pa
    t = bmp.temperature()                                # Read temperature, () → float °C
    print('{} C, {} Pa'.format(t, p))
    machine.sleep(1000)
print('===DONE: 0 passed, 0 failed===')