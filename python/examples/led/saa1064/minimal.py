import time
from periph.connection.i2c_auto import I2CConnection
from periph.chips.led.saa1064 import SAA1064Minimal

connection = I2CConnection(0x38)
display = SAA1064Minimal(connection)                      # Create SAA1064 driver, (connection)

n = 0
while True:
    display.show_number(n)                                # Show a right-aligned number, (value 0–9999) → None
    n = (n + 1) % 10000
    time.sleep(0.1)
