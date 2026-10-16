# ST7036 demo example: a two-line status display on an EA DOGM162W-A (2 x 16, 3.3 V) (MicroPython, Raspberry Pi Pico).
# Wiring: SI=GP19 (SPI0 MOSI), SCL=GP18 (SPI0 SCK), CSB=GP17, RS=GP20 (C/D), RESET=GP21; PSB=GND, E=3V3 (no BUSY line).
# Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
import time
from machine import SPI
from periph.connection.display_micropython import DisplayConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.st7036 import ST7036Full

spi = SPI(0, baudrate=1_000_000, polarity=0, phase=0)               # Create SPI bus, (id=0, baud=1 MHz, mode=0)
cs = MicroPythonOutputPin(17)                                        # Create CSB pin, (pin=17) → OutputPin
dc = MicroPythonOutputPin(20)                                        # Create RS (C/D) pin, (pin=20) → OutputPin
res = MicroPythonOutputPin(21)                                       # Create RESET pin, (pin=21) → OutputPin
connection = DisplayConnection(spi, cs, dc, res, None)               # Create display connection, (spi, cs, dc, res, busy=None)
lcd = ST7036Full(connection)                                         # Create ST7036 driver, (connection, lines=2, columns=None, supply=SUPPLY_3V3, contrast=None)

# --- Define the bar-graph glyphs ---
# Custom characters 0..4 are columns of 1..5 filled pixel columns, so a 16-character bar
# can show 80 steps with the eight CGRAM slots.
for g in range(5):
    pattern = (0x1F << (4 - g)) & 0x1F
    rows = [0 if r in (0, 7) else pattern for r in range(8)]
    lcd.create_char(g, rows)                                         # Define user character, (index 0..7, rows[8])

# --- Static title line ---
lcd.clear()                                                          # Clear display, ()
lcd.print_at(0, 0, "Periph ST7036")                                  # Write text, (col, row, text)

# --- Animate a progress bar on line 2 ---
# Each step fills 1/5 of a character cell; a full bar is 16 x 5 = 80 steps.
for step in range(81):
    lcd.set_cursor(0, 1)                                             # Move cursor, (col, row)
    for col in range(16):
        fill = step - col * 5                                        # pixel columns of this cell that are filled
        if fill >= 5:
            lcd.write_char(4)                                        # Write raw character code, (code)
        elif fill > 0:
            lcd.write_char(fill - 1)                                 # Write raw character code, (code)
        else:
            lcd.write_char(ord(" "))                                 # Write raw character code, (code)
    time.sleep(0.04)
time.sleep(1)

# --- Contrast sweep ---
# The contrast is set once in normal use; sweeping it shows the usable range of the module.
lcd.print_at(0, 0, "Contrast sweep ")                                # Write text, (col, row, text)
for c in range(0x10, 0x31):
    lcd.set_contrast(c)                                              # Set contrast, (value 0..63)
    time.sleep(0.08)
lcd.set_contrast(0x18)                                               # Set contrast, (value 0..63)
lcd.print_at(0, 0, "Periph ST7036   ")                               # Write text, (col, row, text)
