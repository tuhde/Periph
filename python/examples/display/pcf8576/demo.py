from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.display.pcf8576 import PCF8576Full
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x38)
lcd = PCF8576Full(transport)

lcd.clear()

counter = 9999
while counter >= 0:
    digits = [
        counter // 1000 % 10,
        counter // 100 % 10,
        counter // 10 % 10,
        counter % 10
    ]
    print(counter)
    data = bytes([PCF8576Full.SEG_7SEG[d] for d in digits])
    lcd.write_raw(0, data)
    counter -= 1
    time.sleep(1)

# display ---- when done
lcd.write_raw(0, bytes([0x49, 0x49, 0x49, 0x49]))
print('done')