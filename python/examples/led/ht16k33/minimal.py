import time
from periph.connection.i2c_auto import I2CConnection
from periph.chips.led.ht16k33 import HT16K33Minimal

connection = I2CConnection(0x70)
matrix = HT16K33Minimal(connection)                       # Create HT16K33 driver, (connection)

for row in range(HT16K33Minimal.ROWS):
    matrix.set_pixel(0, row)                              # Set one LED in the shadow RAM, (com 0–7, row 0–15, on) → None
matrix.show()                                             # Write the shadow RAM to the chip, () → None
time.sleep(1)
print('===DONE: 0 passed, 0 failed===')
