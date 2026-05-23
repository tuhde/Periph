from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.magnetometer.as5600 import AS5600Minimal
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x36)
as5600 = AS5600Minimal(transport)                          # Create AS5600 driver, (transport) → AS5600Minimal

while True:
    a = as5600.angle()                                     # Read absolute angle, () → float degrees
    r = as5600.angle_raw()                                 # Read scaled angle count, () → int 0-4095
    print('angle=%.2f°  raw=%d' % (a, r))
    time.sleep(1)
