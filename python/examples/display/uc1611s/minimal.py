# UC1611s minimal example: EA DOGXL240-7 (240 x 128, 3.3 V only) over I2C (MicroPython, Raspberry Pi Pico; also CircuitPython / Linux via i2c_auto).
# Wiring: SDA=GP4, SCL=GP5 (I2C0), VDD = 3V3; module pins BM0 = D13 = VDD, CD = A2 = A3 = VSS -> command 0x38 / data 0x39 (A2 = VDD: 0x3A / 0x3B ...).
# Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD; pull-ups 2-4.7 k to 3.3 V. Minimal use: a border and a diagonal on the 240 x 128 panel.
from periph.connection.i2c_auto import I2CConnection
from periph.chips.display.uc1611s import UC1611sMinimal

command = I2CConnection(0x38)                                        # Create command connection, (addr=0x38)
data = I2CConnection(0x39)                                           # Create data connection, (addr=0x39)
lcd = UC1611sMinimal(command, data)                                  # Create UC1611s driver, (command, data, width=240, height=128)

lcd.clear()                                                     # Clear framebuffer, ()
for x in range(240):
    lcd.set_pixel(x, 0)                                         # Set pixel, (x 0..239, y 0..127, on=True)
    lcd.set_pixel(x, 127)                                       # Set pixel, (x 0..239, y 0..127, on=True)
    lcd.set_pixel(x, x * 127 // 239)                            # Set pixel, (x 0..239, y 0..127, on=True)
for y in range(128):
    lcd.set_pixel(0, y)                                         # Set pixel, (x 0..239, y 0..127, on=True)
    lcd.set_pixel(239, y)                                       # Set pixel, (x 0..239, y 0..127, on=True)
lcd.show()                                                      # Send framebuffer to the display, ()
