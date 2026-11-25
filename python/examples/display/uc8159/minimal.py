# UC8159 minimal example: Waveshare 5.65inch e-Paper (F) on a Raspberry Pi Pico (MicroPython).
# Wiring: DIN=GP19 (SPI0 MOSI), CLK=GP18 (SPI0 SCK), CS=GP17, DC=GP20, RST=GP21, BUSY=GP22.
from machine import SPI, Pin
from periph.connection.display_micropython import DisplayConnection
from periph.connection.input_pin import MicroPythonPin
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.uc8159 import UC8159Minimal

spi = SPI(0, baudrate=4_000_000, polarity=0, phase=0)               # Create SPI bus, (id=0, baud=4 MHz, mode=0)
cs = MicroPythonOutputPin(17)                                        # Create CS# pin, (pin=17) → OutputPin
dc = MicroPythonOutputPin(20)                                        # Create D/C# pin, (pin=20) → OutputPin
res = MicroPythonOutputPin(21)                                       # Create RES# pin, (pin=21) → OutputPin
busy = MicroPythonPin(Pin(22, Pin.IN))                               # Create BUSY pin, (pin=22) → InputPin
connection = DisplayConnection(spi, cs, dc, res, busy)               # Create display connection, (spi, cs, dc, res, busy)

epd = UC8159Minimal(connection)                                      # Create UC8159 driver, (connection, busy_timeout_ms=60000)
epd.clear(UC8159Minimal.WHITE)                                       # Fill framebuffer, (color=WHITE)
for x in range(600):
    colour = x * 7 // 600                                            # seven vertical bars: black .. orange
    for y in range(448):
        epd.set_pixel(x, y, colour)                                  # Set one pixel, (x, y, color=BLACK)
epd.show()                                                           # Refresh panel (blocks ~30 s), () → None
epd.sleep()                                                          # Enter deep sleep, () → None
