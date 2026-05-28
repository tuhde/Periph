from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.environmental.aht21 import AHT21Minimal
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x38)
aht = AHT21Minimal(transport)                                          # Create AHT21 driver, (transport) → None

while True:
    r = aht.read()                                                     # Trigger measurement, () → dict {temperature_c, humidity_pct}
    print(r['temperature_c'], r['humidity_pct'])
    time.sleep(1)
