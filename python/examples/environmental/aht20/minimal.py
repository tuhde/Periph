from periph.connection.i2c_auto import I2CConnection
from periph.chips.environmental.aht20 import AHT20Minimal
import time

connection = I2CConnection(0x38)                                         # Create I²C connection, (addr=0x38) → Connection
aht = AHT20Minimal(connection)                                          # Create AHT20 driver, (connection) → None

while True:
    r = aht.read()                                                     # Trigger measurement, () → dict {temperature_c, humidity_pct}
    print(r['temperature_c'], r['humidity_pct'])
    time.sleep(1)
