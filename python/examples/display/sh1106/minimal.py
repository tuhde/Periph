# SH1106 minimal example: 1.3" 128 x 64 OLED module (SH1106, RAM columns 2..129) over I2C (MicroPython, Raspberry Pi Pico; also CircuitPython / Linux via i2c_auto).
# Wiring: SDA=GP4, SCL=GP5 (I2C0), VCC = 3V3 (module with on-board charge pump); address 0x3C (SA0 = VSS) or 0x3D (SA0 = VDD1). Minimal use: a border and a diagonal on the 128 x 64 panel.
from periph.connection.i2c_auto import I2CConnection
from periph.chips.display.sh1106 import SH1106Minimal

connection = I2CConnection(0x3C)                         # Create I2C connection, (addr=0x3C)
lcd = SH1106Minimal(connection)                          # Create SH1106 driver, (connection, width=128, height=64, column_offset=2, internal_dcdc=True)

lcd.clear()                                              # Clear framebuffer, ()
for x in range(128):
    lcd.set_pixel(x, 0)                                  # Set pixel, (x 0..127, y 0..63, on=True)
    lcd.set_pixel(x, 63)                                 # Set pixel, (x 0..127, y 0..63, on=True)
    lcd.set_pixel(x, x // 2)                             # Set pixel, (x 0..127, y 0..63, on=True)
for y in range(64):
    lcd.set_pixel(0, y)                                  # Set pixel, (x 0..127, y 0..63, on=True)
    lcd.set_pixel(127, y)                                # Set pixel, (x 0..127, y 0..63, on=True)
lcd.show()                                               # Send framebuffer to the display, ()
