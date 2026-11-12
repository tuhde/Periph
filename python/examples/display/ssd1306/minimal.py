# SSD1306 minimal example: 0.96" 128 x 64 OLED module (SSD1306) over I2C (MicroPython, Raspberry Pi Pico; also CircuitPython / Linux via i2c_auto).
# Wiring: SDA=GP4, SCL=GP5 (I2C0), VCC = 3V3 (the module generates the OLED supply with the SSD1306 charge pump); address 0x3C (SA0 = low) or 0x3D (SA0 = high). Minimal use: a border and a diagonal on the 128 x 64 panel.
from periph.connection.i2c_auto import I2CConnection
from periph.chips.display.ssd1306 import SSD1306Minimal

connection = I2CConnection(0x3C)                         # Create I2C connection, (addr=0x3C)
lcd = SSD1306Minimal(connection)                          # Create SSD1306 driver, (connection, width=128, height=64, external_vcc=False)

lcd.clear()                                              # Clear framebuffer, ()
for x in range(128):
    lcd.set_pixel(x, 0)                                  # Set pixel, (x 0..127, y 0..63, on=True)
    lcd.set_pixel(x, 63)                                 # Set pixel, (x 0..127, y 0..63, on=True)
    lcd.set_pixel(x, x // 2)                             # Set pixel, (x 0..127, y 0..63, on=True)
for y in range(64):
    lcd.set_pixel(0, y)                                  # Set pixel, (x 0..127, y 0..63, on=True)
    lcd.set_pixel(127, y)                                # Set pixel, (x 0..127, y 0..63, on=True)
lcd.show()                                               # Send framebuffer to the display, ()
