from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.ltc4151 import LTC4151Minimal
import time

connection = I2CConnection(0x6A)                                       # Create I²C connection, (addr=0x6A) → Connection
ltc = LTC4151Minimal(connection, r_sense=0.02)                         # Create LTC4151 driver, (connection, r_sense=0.02 Ω) → None

while True:
    print(ltc.voltage(), ltc.current(), ltc.power())                   # Read V / A / W, () → float each
    time.sleep(1)
