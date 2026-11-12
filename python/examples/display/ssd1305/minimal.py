# SSD1305 minimal example: 1.3" 128 x 64 OLED module (SSD1305, RAM columns 4..131) over I2C (MicroPython, Raspberry Pi Pico; also CircuitPython / Linux via i2c_auto).
# Wiring: SDA=GP4, SCL=GP5 (I2C0), VCC = 3V3 (OLED supply VCC 7..15 V from the module; the chip has no charge pump); address 0x3C (SA0 = low) or 0x3D (SA0 = high). Minimal use: a border and a diagonal on the 128 x 64 panel.
from periph.connection.i2c_auto import I2CConnection
from periph.chips.display.ssd1305 import SSD1305Minimal

connection = I2CConnection(0x3C)                         # Create I2C connection, (addr=0x3C)
lcd = SSD1305Minimal(connection)                          # Create SSD1305 driver, (connection, width=128, height=64, column_offset=4)

lcd.clear()                                              # Clear framebuffer, ()
for x in range(128):
    lcd.set_pixel(x, 0)                                  # Set pixel, (x 0..127, y 0..63, on=True)
    lcd.set_pixel(x, 63)                                 # Set pixel, (x 0..127, y 0..63, on=True)
    lcd.set_pixel(x, x // 2)                             # Set pixel, (x 0..127, y 0..63, on=True)
for y in range(64):
    lcd.set_pixel(0, y)                                  # Set pixel, (x 0..127, y 0..63, on=True)
    lcd.set_pixel(127, y)                                # Set pixel, (x 0..127, y 0..63, on=True)
lcd.show()                                               # Send framebuffer to the display, ()
