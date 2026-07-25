from periph.transport.i2c_auto import I2CTransport
from periph.chips.light.apds9960 import APDS9960Minimal
import time

transport = I2CTransport(0x39)
apds = APDS9960Minimal(transport)                          # Create APDS9960 driver, (transport) → APDS9960Minimal

while True:
    c, r, g, b = apds.color()                              # Read all RGBC channels, () → tuple(int, int, int, int)
    print('C=%d R=%d G=%d B=%d' % (c, r, g, b))
    time.sleep(1)
