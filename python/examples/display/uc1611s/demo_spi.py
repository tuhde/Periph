# UC1611s demo example (4-wire SPI): EA DOGXL240-7 (240 x 128, 3.3 V only) on a Raspberry Pi Pico (MicroPython).
# Wiring: SI=GP19 (SPI0 MOSI), SCK=GP18 (SPI0 SCK), CS0=GP17, CD=GP20, RST=GP21; VDD=3V3; module pins BM0 = D13 = VSS (4-wire SPI).
# Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD. Demo: a bouncing ball with partial page updates, a contrast sweep, inverse flashes, hardware scroll, a second RAM screen and sleep.
from machine import SPI
from periph.connection.display_micropython import DisplayConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.uc1611s import UC1611sSpiFull

spi = SPI(0, baudrate=4_000_000, polarity=0, phase=0)               # Create SPI bus, (id=0, baud=4 MHz, mode=0)
cs = MicroPythonOutputPin(17)                                        # Create CS0 pin, (pin=17) → OutputPin
dc = MicroPythonOutputPin(20)                                        # Create CD (D/C) pin, (pin=20) → OutputPin
res = MicroPythonOutputPin(21)                                       # Create RST pin, (pin=21) → OutputPin
connection = DisplayConnection(spi, cs, dc, res, None)               # Create display connection, (spi, cs, dc, res, busy=None)

lcd = UC1611sSpiFull(connection)                                # Create UC1611s driver, (connection, width=240, height=128, top_view=False, contrast=0x8F)

# --- Draw the frame once ---
# A one-pixel border around the whole 240 x 128 panel; the ball bounces inside it.
lcd.clear()                                                     # Clear framebuffer, ()
lcd.draw_rect(0, 0, 240, 128)                                   # Draw rectangle, (x, y, w, h, on=True, fill=False)
lcd.show()                                                      # Send framebuffer to the display, ()

# --- Bounce an 8 x 8 ball with partial page updates ---
# Only the page rows the ball touches are re-sent, so the animation runs at about
# 20 frames per second, instead of the 8 of a full-frame show().
bx, by, dx, dy = 20, 12, 3, 2
for frame in range(200):
    oldy = by
    lcd.draw_rect(bx, by, 8, 8, False, True)                    # Draw rectangle, (x, y, w, h, on=True, fill=False)
    bx += dx
    by += dy
    if bx < 2 or bx > 229:
        dx = -dx
        bx += 2 * dx
    if by < 2 or by > 118:
        dy = -dy
        by += 2 * dy
    lcd.draw_rect(bx, by, 8, 8, True, True)                     # Draw rectangle, (x, y, w, h, on=True, fill=False)
    for page in range(min(oldy, by) // 8, (max(oldy, by) + 7) // 8 + 1):
        lcd.show_page(page)                                     # Send one framebuffer page, (page 0..15)
    time.sleep(0.02)

# --- Contrast sweep around the working point ---
# The contrast is set once in normal use; sweeping PM shows the usable range of the panel.
for pm in list(range(0x8F, 0x6F, -2)) + list(range(0x70, 0x90, 2)):
    lcd.set_contrast(pm)                                        # Set contrast, (value 0..255)
    time.sleep(0.04)

# --- Flash the inverse display twice ---
for i in range(2):
    lcd.set_invert(True)                                        # Invert display, (on)
    time.sleep(0.4)
    lcd.set_invert(False)                                       # Invert display, (on)
    time.sleep(0.4)

# --- Hardware scroll ---
# The image moves up in the controller, no data is re-sent; a blank band enters from the bottom
# because RAM rows 128..159 are empty.
for line in list(range(33)) + list(range(32, -1, -1)):
    lcd.set_scroll_line(line)                                   # Scroll the image up, (line 0..159 - 2 * fixed)
    time.sleep(0.04)

# --- Second screen in RAM ---
# The chip holds four black/white screens; draw a cross into screen 1 and switch to it.
lcd.set_write_pattern(1)                                        # Select RAM screen to write, (pattern 0..3)
lcd.fill(False)                                                 # Fill framebuffer, (on=True)
lcd.draw_line(0, 0, 239, 127)                                   # Draw line, (x0, y0, x1, y1, on=True)
lcd.draw_line(0, 127, 239, 0)                                   # Draw line, (x0, y0, x1, y1, on=True)
lcd.show()                                                      # Send framebuffer to the display, ()
lcd.set_display_pattern(1)                                      # Select displayed RAM screen, (pattern 0..3)
time.sleep(2)
lcd.set_display_pattern(0)                                      # Select displayed RAM screen, (pattern 0..3)
lcd.set_write_pattern(0)                                        # Select RAM screen to write, (pattern 0..3)
time.sleep(1.5)

# --- Power down ---
# Sleep stops the charge pump and the drivers (below 1 uA); RAM and registers are kept.
lcd.sleep()                                                     # Enter sleep, ()
print("done")
