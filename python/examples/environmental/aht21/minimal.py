from periph.connection.i2c_auto import I2CConnection
from periph.chips.environmental.aht21 import AHT21Minimal
import time

connection = I2CConnection(0x38)                                         # Create I²C connection, (addr=0x38) → Connection
aht = AHT21Minimal(connection)                                          # Create AHT21 driver, (connection) → None

while True:
    r = aht.read()                                                     # Trigger measurement, () → dict {temperature_c, humidity_pct}
    print(r['temperature_c'], r['humidity_pct'])
    time.sleep(1)
