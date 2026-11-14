from periph.connection.i2c_auto import I2CConnection
from periph.chips.tof.vl6180x import VL6180XMinimal
import time

connection = I2CConnection(0x29, reg_bytes=2)                            # Create I²C connection, (addr=0x29, reg_bytes=2) → Connection
sensor = VL6180XMinimal(connection)                                      # Create VL6180X driver, (connection) → None

while True:
    print('%d mm' % sensor.read_range())                                 # Single-shot range, () → int mm
    time.sleep(0.2)
