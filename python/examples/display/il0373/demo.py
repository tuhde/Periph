# IL0373 demo: status card on a Waveshare 2.13inch e-Paper (D), Raspberry Pi Pico (MicroPython).
# Wiring: DIN=GP19, CLK=GP18, CS=GP17, DC=GP20, RST=GP21, BUSY=GP22.
# The refresh blocks the interpreter until BUSY is high; the printed time is the number the
# spec's Timing Constraints are missing.
import time
from machine import SPI, Pin
from periph.connection.display_micropython import DisplayConnection
from periph.connection.input_pin import MicroPythonPin
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.il0373 import IL0373Full

spi = SPI(0, baudrate=4_000_000, polarity=0, phase=0)               # Create SPI bus, (id=0, baud=4 MHz, mode=0)
cs = MicroPythonOutputPin(17)                                        # Create CS# pin, (pin=17) → OutputPin
dc = MicroPythonOutputPin(20)                                        # Create D/C# pin, (pin=20) → OutputPin
res = MicroPythonOutputPin(21)                                       # Create RES# pin, (pin=21) → OutputPin
busy = MicroPythonPin(Pin(22, Pin.IN))                               # Create BUSY pin, (pin=22) → InputPin
connection = DisplayConnection(spi, cs, dc, res, busy)               # Create display connection, (spi, cs, dc, res, busy)
epd = IL0373Full(connection)                                        # Create IL0373 driver, (connection, width=104, height=212, busy_timeout_ms=30000)

# --- Compose the status card in the framebuffer ---
# White page, a one-pixel black border inset by 2 px (a frame on the very edge is hidden
# by the panel bezel) and a black header bar across the top 24 rows.
epd.clear(IL0373Full.WHITE)                                         # Fill framebuffer, (color=WHITE)
epd.draw_rect(2, 2, 100, 208, IL0373Full.BLACK)                     # Draw rectangle, (x, y, w, h, color=BLACK, fill=False)
epd.draw_rect(3, 3, 98, 24, IL0373Full.BLACK, True)                  # Draw rectangle, (x, y, w, h, color=BLACK, fill=False)

# --- Bar chart of eight readings ---
# Axes first, then one filled bar per value; the tallest bar is filled and the others are outlines.
values = [30, 55, 80, 45, 120, 90, 60, 100]
epd.draw_line(10, 190, 95, 190, IL0373Full.BLACK)                  # Draw line, (x0, y0, x1, y1, color=BLACK)
epd.draw_line(10, 60, 10, 190, IL0373Full.BLACK)                     # Draw line, (x0, y0, x1, y1, color=BLACK)
peak = max(values)
for i, v in enumerate(values):
    epd.draw_rect(14 + i * 10, 190 - v, 8, v, IL0373Full.BLACK, v == peak)# Draw rectangle, (x, y, w, h, color=BLACK, fill=False)

# --- Refresh and time it ---
# show() blocks for the whole DTM1/DTM2/DRF/POF sequence.
start = time.ticks_ms()
epd.show()                                                           # Refresh panel, () → None
print('refresh took', time.ticks_diff(time.ticks_ms(), start) / 1000, 's')

# --- Save the image and power down ---
# The panel keeps the picture without power; deep sleep stops the controller drawing current.
epd.sleep()                                                          # Enter deep sleep, () → None
