# SSD1331 demo example: a colour test card on a Waveshare 0.95inch RGB OLED (MicroPython, Raspberry Pi Pico).
# Wiring: DIN=GP19 (SPI0 MOSI), CLK=GP18 (SPI0 SCK), CS=GP17, DC=GP20, RST=GP21 (no BUSY line).
import time
from machine import SPI
from periph.connection.display_micropython import DisplayConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.ssd1331 import SSD1331Full

spi = SPI(0, baudrate=4_000_000, polarity=0, phase=0)               # Create SPI bus, (id=0, baud=4 MHz, mode=0)
cs = MicroPythonOutputPin(17)                                        # Create CS# pin, (pin=17) → OutputPin
dc = MicroPythonOutputPin(20)                                        # Create D/C# pin, (pin=20) → OutputPin
res = MicroPythonOutputPin(21)                                       # Create RES# pin, (pin=21) → OutputPin
connection = DisplayConnection(spi, cs, dc, res, None)               # Create display connection, (spi, cs, dc, res, busy=None)
oled = SSD1331Full(connection)                                       # Create SSD1331 driver, (connection)

# --- Draw the test card into the framebuffer ---
# Eight 12-pixel colour bars over a 26-row gray ramp show that all channels and the
# 5-6-5 depth work; the one-pixel frame shows that the whole 96 x 64 area is addressed.
bars = (oled.WHITE, oled.YELLOW, oled.CYAN, oled.GREEN, oled.MAGENTA, oled.RED, oled.BLUE, oled.BLACK)
oled.clear(oled.BLACK)                                               # Fill framebuffer, (color=BLACK)
for x in range(96):
    oled.draw_rect(x, 2, 1, 30, bars[x // 12], True)                 # Filled rectangle, (x, y, w, h, color=WHITE, fill=False)
    g = x * 255 // 95
    oled.draw_rect(x, 34, 1, 26, oled.rgb(g, g, g), True)            # Filled rectangle, (x, y, w, h, color=WHITE, fill=False)
oled.draw_rect(0, 0, 96, 64, oled.WHITE)                             # Rectangle outline, (x, y, w, h, color=WHITE, fill=False)

# --- Send it once ---
oled.show()                                                          # Send framebuffer, () → None
time.sleep(3)

# --- Animate with hardware drawing ---
# The chip draws the marker straight into its RAM: no framebuffer transfer per step.
for step in range(60):
    x = 4 + (step * 3) % 80
    oled.show()                                                      # Send framebuffer, () → None
    oled.hw_rect(x, 36, 10, 10, oled.WHITE, oled.RED, True)          # Hardware rectangle, (x, y, w, h, line_color, fill_color=0, fill=False)
    time.sleep_ms(50)

# --- Scroll the test card ---
# The chip scrolls its own RAM, one column every 6 frames.
oled.show()                                                          # Send framebuffer, () → None
oled.scroll(1, 0, 64, 0, 0)                                          # Scroll, (horizontal, start_row, rows, vertical, interval 0..3)
