# SH1106 demo example: 1.3" 128 x 64 OLED module (SH1106, RAM columns 2..129) over I2C (MicroPython, Raspberry Pi Pico; also CircuitPython / Linux via i2c_auto).
# Wiring: SDA=GP4, SCL=GP5 (I2C0), VCC = 3V3 (module with on-board charge pump); address 0x3C (SA0 = VSS) or 0x3D (SA0 = VDD1). Demo: a bouncing ball inside a border with partial page updates, a contrast fade, inverse flashes, hardware scroll and sleep.
import time
from periph.connection.i2c_auto import I2CConnection
from periph.chips.display.sh1106 import SH1106Full

connection = I2CConnection(0x3C)                         # Create I2C connection, (addr=0x3C)
lcd = SH1106Full(connection)                             # Create SH1106 driver, (connection, width=128, height=64, column_offset=2, internal_dcdc=True)

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

# --- Hardware scroll ---
# Stepping the display start line moves the image up inside the controller, no data is re-sent;
# the picture wraps around after 64 lines.
for line in list(range(64)) + list(range(63, -1, -1)):
    lcd.set_start_line(line)                             # Scroll the image up, (line 0..63)
    time.sleep(0.03)

# --- Power down ---
# Sleep turns the display and the charge pump off; RAM and registers are kept.
lcd.sleep()                                              # Enter sleep, ()
