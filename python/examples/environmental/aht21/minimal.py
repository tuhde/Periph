from periph.transport.i2c_auto import I2CTransport
from periph.chips.environmental.aht21 import AHT21Minimal
import time

transport = I2CTransport(0x38)                                         # Create I²C transport, (addr=0x38) → Transport
aht = AHT21Minimal(transport)                                          # Create AHT21 driver, (transport) → None

while True:
    r = aht.read()                                                     # Trigger measurement, () → dict {temperature_c, humidity_pct}
    print(r['temperature_c'], r['humidity_pct'])
    time.sleep(1)
