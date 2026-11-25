# SSD1305 demo example: 1.3" 128 x 64 OLED module (SSD1305, RAM columns 4..131) over I2C (MicroPython, Raspberry Pi Pico; also CircuitPython / Linux via i2c_auto).
# Wiring: SDA=GP4, SCL=GP5 (I2C0), VCC = 3V3 (OLED supply VCC 7..15 V from the module; the chip has no charge pump); address 0x3C (SA0 = low) or 0x3D (SA0 = high). Demo: a bouncing ball inside a border with partial page updates, a contrast fade, inverse flashes, hardware scroll and sleep.
import time
from periph.connection.i2c_auto import I2CConnection
from periph.chips.display.ssd1305 import SSD1305Full

connection = I2CConnection(0x3C)                         # Create I2C connection, (addr=0x3C)
lcd = SSD1305Full(connection)                             # Create SSD1305 driver, (connection, width=128, height=64, column_offset=4)

# --- Draw the border once ---
# A one-pixel border around the 128 x 64 panel; the ball bounces inside it.
lcd.clear()                                              # Clear framebuffer, ()
lcd.draw_rect(0, 0, 128, 64)                             # Draw rectangle, (x, y, w, h, on=True, fill=False)
lcd.show()                                               # Send framebuffer to the display, ()

# --- Bounce a 6 x 6 ball at about 20 frames per second ---
# The ball is erased and redrawn in the framebuffer, then the whole frame is sent with show().
bx, by, dx, dy = 20, 12, 3, 2
for frame in range(200):
    lcd.draw_rect(bx, by, 6, 6, False, True)             # Draw rectangle, (x, y, w, h, on=True, fill=False)
    bx += dx
    by += dy
    if bx < 2 or bx > 120:
        dx = -dx
        bx += 2 * dx
    if by < 2 or by > 56:
        dy = -dy
        by += 2 * dy
    lcd.draw_rect(bx, by, 6, 6, True, True)              # Draw rectangle, (x, y, w, h, on=True, fill=False)
    lcd.show()                                           # Send framebuffer to the display, ()
    time.sleep(0.05)

# --- Fade the contrast down and back up ---
# 255 -> 0 -> 255 in steps of 5 shows the full range of the segment drive current.
for v in list(range(255, -1, -5)) + list(range(0, 256, 5)):
    lcd.set_contrast(v)                                  # Set contrast, (value 0..255)
    time.sleep(0.02)

# --- Flash the inverse display twice ---
for i in range(2):
    lcd.set_invert(True)                                 # Invert display, (on)
    time.sleep(0.4)
    lcd.set_invert(False)                                # Invert display, (on)
    time.sleep(0.4)

# --- Dim mode ---
# Dim mode lowers contrast and colour-bank brightness to the levels stored with set_dim_mode.
lcd.set_dim_mode(0x10, 0x10)                             # Configure dim levels, (contrast 0..255, brightness 0..255)
lcd.set_dim(True)                                        # Dim mode, (on)
time.sleep(2)
lcd.set_dim(False)                                       # Dim mode, (on)

# --- Hardware scroll ---
# The scroll engine moves all eight pages right in the controller without re-sending any data;
# stopping rewrites the RAM from the framebuffer as the datasheet requires.
lcd.set_scroll_horizontal(True, 0, 7, 0)                 # Set horizontal scroll, (right, start_page, end_page, interval 0..6, columns=1)
lcd.scroll_start()                                       # Start scrolling, ()
time.sleep(5)
lcd.scroll_stop()                                        # Stop scrolling, ()

# --- Power down ---
# Sleep turns the display off (switch VCC off afterwards); RAM and registers are kept.
lcd.sleep()                                              # Enter sleep, ()
