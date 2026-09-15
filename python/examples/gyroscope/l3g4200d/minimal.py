from periph.connection.i2c_auto import I2CConnection
from periph.chips.gyroscope.l3g4200d import L3G4200DMinimal

connection = I2CConnection(0x68)
gyro = L3G4200DMinimal(connection)                         # Create L3G4200D driver, (connection, bus_type='i2c')

for _ in range(10):
    x, y, z = gyro.angular_rate()                         # Read X/Y/Z angular rate, () → (float, float, float) rad/s
    print('X={:.2f} Y={:.2f} Z={:.2f} rad/s'.format(x, y, z))
    time.sleep_ms(100)
print('===DONE: 0 passed, 0 failed===')
