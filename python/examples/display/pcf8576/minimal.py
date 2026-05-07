from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.display.pcf8576 import PCF8576Minimal, PCF8576Full
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x38)
lcd = PCF8576Minimal(transport)

lcd.clear()
while True:
    for digit in [9, 9, 9, 9]:
        lcd.set_digit_7seg(0, PCF8576Full.SEG_7SEG[digit])
        lcd.set_digit_7seg(1, PCF8576Full.SEG_7SEG[digit])
        lcd.set_digit_7seg(2, PCF8576Full.SEG_7SEG[digit])
        lcd.set_digit_7seg(3, PCF8576Full.SEG_7SEG[digit])
        time.sleep(1)