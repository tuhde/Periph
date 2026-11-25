# IL0373 minimal example: Waveshare 2.13inch e-Paper (D) on a Raspberry Pi Pico (MicroPython).
# Wiring: DIN=GP19 (SPI0 MOSI), CLK=GP18 (SPI0 SCK), CS=GP17, DC=GP20, RST=GP21, BUSY=GP22.
from machine import SPI, Pin
from periph.connection.display_micropython import DisplayConnection
from periph.connection.input_pin import MicroPythonPin
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.il0373 import IL0373Minimal

spi = SPI(0, baudrate=4_000_000, polarity=0, phase=0)               # Create SPI bus, (id=0, baud=4 MHz, mode=0)
cs = MicroPythonOutputPin(17)                                        # Create CS# pin, (pin=17) → OutputPin
dc = MicroPythonOutputPin(20)                                        # Create D/C# pin, (pin=20) → OutputPin
res = MicroPythonOutputPin(21)                                       # Create RES# pin, (pin=21) → OutputPin
busy = MicroPythonPin(Pin(22, Pin.IN))                               # Create BUSY pin, (pin=22) → InputPin
connection = DisplayConnection(spi, cs, dc, res, busy)               # Create display connection, (spi, cs, dc, res, busy)

epd = IL0373Minimal(connection)                                     # Create IL0373 driver, (connection, width=104, height=212, busy_timeout_ms=30000)
epd.clear(IL0373Minimal.WHITE)                                      # Fill framebuffer, (color=WHITE)
epd.set_pixel(10, 10, IL0373Minimal.BLACK)                          # Set one pixel, (x, y, color=BLACK)
for x in range(20, 100):
    epd.set_pixel(x, 40, IL0373Minimal.BLACK)                         # Set one pixel, (x, y, color=BLACK)
epd.show()                                                           # Refresh panel (blocks until BUSY is high), () → None
epd.sleep()                                                          # Enter deep sleep, () → None
