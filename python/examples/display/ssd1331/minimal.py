# SSD1331 minimal example: Waveshare 0.95inch RGB OLED on a Raspberry Pi Pico (MicroPython).
# Wiring: DIN=GP19 (SPI0 MOSI), CLK=GP18 (SPI0 SCK), CS=GP17, DC=GP20, RST=GP21 (no BUSY line).
from machine import SPI
from periph.connection.display_micropython import DisplayConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.ssd1331 import SSD1331Minimal

spi = SPI(0, baudrate=4_000_000, polarity=0, phase=0)               # Create SPI bus, (id=0, baud=4 MHz, mode=0)
cs = MicroPythonOutputPin(17)                                        # Create CS# pin, (pin=17) → OutputPin
dc = MicroPythonOutputPin(20)                                        # Create D/C# pin, (pin=20) → OutputPin
res = MicroPythonOutputPin(21)                                       # Create RES# pin, (pin=21) → OutputPin
connection = DisplayConnection(spi, cs, dc, res, None)               # Create display connection, (spi, cs, dc, res, busy=None)

oled = SSD1331Minimal(connection)                                    # Create SSD1331 driver, (connection)

bars = (oled.WHITE, oled.YELLOW, oled.CYAN, oled.GREEN, oled.MAGENTA, oled.RED, oled.BLUE, oled.BLACK)
for y in range(oled.height):
    for x in range(oled.width):
        oled.set_pixel(x, y, bars[x // 12])                          # Set one pixel, (x, y, color=WHITE)
for x in range(oled.width):
    oled.set_pixel(x, 0, oled.WHITE)                                 # Set one pixel, (x, y, color=WHITE)
    oled.set_pixel(x, oled.height - 1, oled.WHITE)                   # Set one pixel, (x, y, color=WHITE)

oled.show()                                                          # Send framebuffer, () → None
