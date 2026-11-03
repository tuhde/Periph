# ST7036 minimal example: EA DOGM162W-A (2 x 16, 3.3 V) on a Raspberry Pi Pico (MicroPython).
# Wiring: SI=GP19 (SPI0 MOSI), SCL=GP18 (SPI0 SCK), CSB=GP17, RS=GP20 (C/D), RESET=GP21; PSB=GND, E=3V3 (no BUSY line).
# Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
from machine import SPI
from periph.connection.display_micropython import DisplayConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.st7036 import ST7036Minimal

spi = SPI(0, baudrate=1_000_000, polarity=0, phase=0)               # Create SPI bus, (id=0, baud=1 MHz, mode=0)
cs = MicroPythonOutputPin(17)                                        # Create CSB pin, (pin=17) → OutputPin
dc = MicroPythonOutputPin(20)                                        # Create RS (C/D) pin, (pin=20) → OutputPin
res = MicroPythonOutputPin(21)                                       # Create RESET pin, (pin=21) → OutputPin
connection = DisplayConnection(spi, cs, dc, res, None)               # Create display connection, (spi, cs, dc, res, busy=None)

lcd = ST7036Minimal(connection)                                      # Create ST7036 driver, (connection)

lcd.print_at(0, 0, "Hello, Periph!")                                 # Write text, (col, row, text)
lcd.print_at(0, 1, "ST7036 DOGM162")                                 # Write text, (col, row, text)
