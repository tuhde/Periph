# UC8276C demo: status card on a Waveshare 4.2inch e-Paper (B), Raspberry Pi Pico (MicroPython).
# Wiring: DIN=GP19, CLK=GP18, CS=GP17, DC=GP20, RST=GP21, BUSY=GP22.
# The refresh blocks the interpreter for about 17 s; the printed time is the number the
# spec's Timing Constraints are missing.
import time
from machine import SPI, Pin
from periph.connection.display_micropython import DisplayConnection
from periph.connection.input_pin import MicroPythonPin
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.uc8276c import UC8276CFull

spi = SPI(0, baudrate=4_000_000, polarity=0, phase=0)               # Create SPI bus, (id=0, baud=4 MHz, mode=0)
cs = MicroPythonOutputPin(17)                                        # Create CS# pin, (pin=17) → OutputPin
dc = MicroPythonOutputPin(20)                                        # Create D/C# pin, (pin=20) → OutputPin
res = MicroPythonOutputPin(21)                                       # Create RES# pin, (pin=21) → OutputPin
busy = MicroPythonPin(Pin(22, Pin.IN))                               # Create BUSY_N pin, (pin=22) → InputPin
connection = DisplayConnection(spi, cs, dc, res, busy)               # Create display connection, (spi, cs, dc, res, busy)
epd = UC8276CFull(connection)                                        # Create UC8276C driver, (connection, width=400, height=300, busy_timeout_ms=30000)

# --- Compose the status card in the framebuffer ---
# White page, a one-pixel black border inset by 4 px (a frame on the very edge is hidden
# by the panel bezel) and a red header bar across the top 40 rows.
epd.clear(UC8276CFull.WHITE)                                         # Fill framebuffer, (color=WHITE)
epd.draw_rect(4, 4, 392, 292, UC8276CFull.BLACK)                     # Draw rectangle, (x, y, w, h, color=BLACK, fill=False)
epd.draw_rect(5, 5, 390, 40, UC8276CFull.RED, True)                  # Draw rectangle, (x, y, w, h, color=BLACK, fill=False)

# --- Bar chart of eight readings ---
# Axes first, then one filled bar per value; the tallest bar is red to flag the peak.
values = [30, 55, 80, 45, 120, 90, 60, 100]
epd.draw_line(30, 270, 370, 270, UC8276CFull.BLACK)                  # Draw line, (x0, y0, x1, y1, color=BLACK)
epd.draw_line(30, 70, 30, 270, UC8276CFull.BLACK)                    # Draw line, (x0, y0, x1, y1, color=BLACK)
peak = max(values)
for i, v in enumerate(values):
    colour = UC8276CFull.RED if v == peak else UC8276CFull.BLACK
    epd.draw_rect(45 + i * 40, 270 - v, 28, v, colour, True)         # Draw rectangle, (x, y, w, h, color=BLACK, fill=False)

# --- Refresh and time it ---
# show() blocks for the whole DTM1/DTM2/PON/DRF/POF sequence.
start = time.ticks_ms()
epd.show()                                                           # Refresh panel, () → None
print('refresh took', time.ticks_diff(time.ticks_ms(), start) / 1000, 's')

# --- Save the image and power down ---
# The panel keeps the picture without power; deep sleep stops the controller drawing current.
epd.sleep()                                                          # Enter deep sleep, () → None
