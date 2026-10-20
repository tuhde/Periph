# UC1611s minimal example (4-wire SPI): EA DOGXL240-7 (240 x 128, 3.3 V only) on a Raspberry Pi Pico (MicroPython).
# Wiring: SI=GP19 (SPI0 MOSI), SCK=GP18 (SPI0 SCK), CS0=GP17, CD=GP20, RST=GP21; VDD=3V3; module pins BM0 = D13 = VSS (4-wire SPI).
# Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD. Minimal use: a border and a diagonal on the 240 x 128 panel.
from machine import SPI
from periph.connection.display_micropython import DisplayConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.uc1611s import UC1611sSpiMinimal

spi = SPI(0, baudrate=4_000_000, polarity=0, phase=0)               # Create SPI bus, (id=0, baud=4 MHz, mode=0)
cs = MicroPythonOutputPin(17)                                        # Create CS0 pin, (pin=17) → OutputPin
dc = MicroPythonOutputPin(20)                                        # Create CD (D/C) pin, (pin=20) → OutputPin
res = MicroPythonOutputPin(21)                                       # Create RST pin, (pin=21) → OutputPin
connection = DisplayConnection(spi, cs, dc, res, None)               # Create display connection, (spi, cs, dc, res, busy=None)

lcd = UC1611sSpiMinimal(connection)                             # Create UC1611s driver, (connection, width=240, height=128)

lcd.clear()                                                     # Clear framebuffer, ()
for x in range(240):
    lcd.set_pixel(x, 0)                                         # Set pixel, (x 0..239, y 0..127, on=True)
    lcd.set_pixel(x, 127)                                       # Set pixel, (x 0..239, y 0..127, on=True)
    lcd.set_pixel(x, x * 127 // 239)                            # Set pixel, (x 0..239, y 0..127, on=True)
for y in range(128):
    lcd.set_pixel(0, y)                                         # Set pixel, (x 0..239, y 0..127, on=True)
    lcd.set_pixel(239, y)                                       # Set pixel, (x 0..239, y 0..127, on=True)
lcd.show()                                                      # Send framebuffer to the display, ()
