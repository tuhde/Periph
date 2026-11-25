from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.max17048 import MAX17048Minimal
import time

connection = I2CConnection(0x36)                                       # Create I²C connection, (addr=0x36) → Connection
gauge = MAX17048Minimal(connection, cells=1)                           # Create MAX17048 driver, (connection, cells) → None

while True:
    print(gauge.voltage(), gauge.percent())                            # Read V / %, () → float each
    time.sleep(1)
