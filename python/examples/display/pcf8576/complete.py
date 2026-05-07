from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.display.pcf8576 import PCF8576Full
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x38)
lcd = PCF8576Full(transport)

lcd.clear()
lcd.set_blink(lcd.BLINK_OFF)
lcd.enable()
lcd.set_mode(4, 0)
lcd.device_select(0)
lcd.set_bank(0, 0)
lcd.set_digit_7seg(0, PCF8576Full.SEG_7SEG[1])
lcd.set_digit_7seg(1, PCF8576Full.SEG_7SEG[2])
lcd.set_digit_7seg(2, PCF8576Full.SEG_7SEG[3])
lcd.set_digit_7seg(3, PCF8576Full.SEG_7SEG[4])
time.sleep(1)
lcd.disable()
time.sleep(1)
lcd.enable()
lcd.set_blink(lcd.BLINK_1HZ)