from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.light.apds9960 import APDS9960Minimal
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x39)
apds = APDS9960Minimal(transport)                          # Create APDS9960 driver, (transport) → APDS9960Minimal

while True:
    c, r, g, b = apds.color()                              # Read all RGBC channels, () → tuple(int, int, int, int)
    print('C=%d R=%d G=%d B=%d' % (c, r, g, b))
    time.sleep(1)
