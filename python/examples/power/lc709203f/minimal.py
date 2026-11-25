from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.lc709203f import LC709203FMinimal
import time

connection = I2CConnection(0x0B)                                       # Create I²C connection, (addr=0x0B) → Connection
gauge = LC709203FMinimal(connection, capacity_mah=1000, profile=1)     # Create LC709203F driver, (connection, capacity_mah, profile) → None

while True:
    print(gauge.voltage(), gauge.percent(), gauge.temperature())       # Read V / % / °C, () → float each
    time.sleep(5)
