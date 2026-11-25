from periph.connection.i2c_auto import I2CConnection
from periph.chips.display.pcf8579 import PCF8579Minimal

connection = I2CConnection(0x3C)
lcd = PCF8579Minimal(connection, 0, 16)                   # Create PCF8579 driver, (connection, subaddress=0 0–15, rows=16 8/16/24/32, mixed=False)

lcd.clear()                                               # Clear display RAM, () → None
lcd.set_pixel(0, 0)                                       # Set one pixel, (x 0–39, y 0–15, on=True) → None
lcd.set_pixel(39, 15)                                     # Set opposite corner, (x 0–39, y 0–15, on=True) → None
lcd.write_columns(4, 0, bytes([0xFF, 0x81, 0x81, 0xFF]))  # Write column bytes, (x 0–39, bank 0–1, data bytes) → None
print('===DONE: 0 passed, 0 failed===')
