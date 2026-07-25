from periph.transport.i2c_auto import I2CTransport
from periph.chips.display.pcf8576 import PCF8576Minimal

transport = I2CTransport(0x38)
lcd = PCF8576Minimal(transport)                          # Create PCF8576 driver, (transport)

digits = [1, 2, 3, 4]
for pos, d in enumerate(digits):
    seg = PCF8576Minimal._SEVEN_SEG[d]                   # Encode 7-segment digit, (digit 0–9) → int byte
    lcd.set_digit_7seg(pos, seg)                         # Write one digit, (position 0–19, segments 0–255) → None
print('===DONE: 0 passed, 0 failed===')
