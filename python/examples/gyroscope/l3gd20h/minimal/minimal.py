from periph.connection.i2c_linux import I2CConnection
from periph.chips.gyroscope.l3gd20h import L3GD20HMinimal

conn = I2CConnection(bus=1, addr=0x6A)
gyro = L3GD20HMinimal(conn)                                          # Create L3GD20H driver, (connection, bus_type='i2c')

while True:
    x, y, z = gyro.gyro()                                           # Read angular rate, () -> (float, float, float) rad/s
    print("x={:.3f} y={:.3f} z={:.3f} rad/s".format(x, y, z))