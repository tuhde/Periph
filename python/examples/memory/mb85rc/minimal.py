from periph.connection.i2c_auto import I2CConnection
from periph.chips.memory.mb85rc import MB85RCMinimal
import time

connection = I2CConnection(0x50, reg_bytes=2)                                         # Create I2C connection, (addr=0x50, reg_bytes=2) → Connection
fram = MB85RCMinimal(connection)                                                      # Create MB85RC driver, (connection) → None

counter = 0
while True:
    fram.write_byte(0x0000, counter)                                                  # Write a single byte, (address 0–32767, value 0–255) → None
    value = fram.read_byte(0x0000)                                                    # Read a single byte, (address 0–32767) → int
    print('Stored:', value)
    counter = (counter + 1) & 0xFF
    time.sleep(1)
