# UC8159 demo: status card on a Waveshare 5.65inch e-Paper (F), Raspberry Pi Pico (MicroPython).
# Wiring: DIN=GP19, CLK=GP18, CS=GP17, DC=GP20, RST=GP21, BUSY=GP22.
# The refresh blocks the interpreter for about 30 s; the printed time is the update time.
import time
from machine import SPI, Pin
from periph.connection.display_micropython import DisplayConnection
from periph.connection.input_pin import MicroPythonPin
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.uc8159 import UC8159Full

spi = SPI(0, baudrate=4_000_000, polarity=0, phase=0)               # Create SPI bus, (id=0, baud=4 MHz, mode=0)
cs = MicroPythonOutputPin(17)                                        # Create CS# pin, (pin=17) → OutputPin
dc = MicroPythonOutputPin(20)                                        # Create D/C# pin, (pin=20) → OutputPin
res = MicroPythonOutputPin(21)                                       # Create RES# pin, (pin=21) → OutputPin
busy = MicroPythonPin(Pin(22, Pin.IN))                               # Create BUSY pin, (pin=22) → InputPin
connection = DisplayConnection(spi, cs, dc, res, busy)               # Create display connection, (spi, cs, dc, res, busy)
epd = UC8159Full(connection)                                         # Create UC8159 driver, (connection, busy_timeout_ms=60000)

# --- Compose the colour chart in the framebuffer ---
# White page, seven vertical bars (black, white, green, blue, red, yellow, orange) and a
# one-pixel black frame inset by 4 px (a frame on the very edge is hidden by the bezel).
epd.clear(UC8159Full.WHITE)                                          # Fill framebuffer, (color=WHITE)
for c in range(7):
    epd.draw_rect(20 + c * 80, 40, 80, 368, c, True)                 # Draw rectangle, (x, y, w, h, color=BLACK, fill=False)
epd.draw_rect(4, 4, 592, 440, UC8159Full.BLACK)                      # Draw rectangle, (x, y, w, h, color=BLACK, fill=False)

# --- Refresh and time it ---
# show() blocks for the whole DTM1/PON/DRF/POF sequence: about 30 s.
start = time.ticks_ms()
epd.show()                                                           # Refresh panel, () → None
print('refresh took', time.ticks_diff(time.ticks_ms(), start) / 1000, 's')

# --- Save the image and power down ---
# The panel keeps the picture without power; deep sleep stops the controller drawing current.
epd.sleep()                                                          # Enter deep sleep, () → None
