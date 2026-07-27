from periph.connection.i2c_auto import I2CConnection
from periph.chips.display.pcf8576 import PCF8576Minimal

connection = I2CConnection(0x38)
lcd = PCF8576Minimal(connection)                          # Create PCF8576 driver, (connection)

digits = [1, 2, 3, 4]
for pos, d in enumerate(digits):
    seg = PCF8576Minimal._SEVEN_SEG[d]                   # Encode 7-segment digit, (digit 0–9) → int byte
    lcd.set_digit_7seg(pos, seg)                         # Write one digit, (position 0–19, segments 0–255) → None
print('===DONE: 0 passed, 0 failed===')
