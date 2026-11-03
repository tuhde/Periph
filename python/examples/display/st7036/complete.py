# ST7036 complete example: every Full method on an EA DOGM162W-A (2 x 16, 3.3 V) (MicroPython, Raspberry Pi Pico).
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

lcd.clear()                                                          # Clear display, ()
                                                                     # writes spaces to all of DDRAM, cursor to home (2 ms)
lcd.print_at(0, 0, "Complete example")                               # Write text, (col, row, text)
lcd.set_cursor(0, 1)                                                 # Move cursor, (col, row)
lcd.write("Cursor + blink")                                          # Write text at the cursor, (text)
lcd.cursor(True, True)                                               # Cursor, (on, blink=False)
time.sleep(2)
lcd.cursor(False, False)                                             # Cursor, (on, blink=False)

heart = [0x00, 0x0A, 0x1F, 0x1F, 0x0E, 0x04, 0x00, 0x00]
arrow = [0x04, 0x02, 0x1F, 0x02, 0x04, 0x00, 0x00, 0x00]
lcd.create_char(0, heart)                                            # Define user character, (index 0..7, rows[8])
                                                                     # 8 rows of 5 bits into CGRAM, DDRAM address restored
lcd.create_char(1, arrow)                                            # Define user character, (index 0..7, rows[8])
lcd.clear()                                                          # Clear display, ()
lcd.set_cursor(0, 0)                                                 # Move cursor, (col, row)
lcd.write("I ")                                                      # Write text at the cursor, (text)
lcd.write_char(0)                                                    # Write raw character code, (code)
lcd.write(" custom ")                                                # Write text at the cursor, (text)
lcd.write_char(1)                                                    # Write raw character code, (code)
time.sleep(2)

lcd.set_contrast(0x08)                                               # Set contrast, (value 0..63)
lcd.print_at(0, 1, "low contrast")                                   # Write text, (col, row, text)
time.sleep(1.5)
lcd.set_contrast(0x30)                                               # Set contrast, (value 0..63)
lcd.print_at(0, 1, "high contrast")                                  # Write text, (col, row, text)
time.sleep(1.5)
lcd.set_contrast(0x18)                                               # Set contrast, (value 0..63)
                                                                     # 0x18 is the DOGM162 3.3 V preset
lcd.set_bias(False)                                                  # LCD bias, (quarter=False) 1/5 bias
lcd.set_follower(True, 5)                                            # Voltage follower, (on, ratio 0..7) 1 + Rb/Ra = 2.5
lcd.set_booster(True)                                                # Voltage booster, (on)

lcd.clear()                                                          # Clear display, ()
lcd.set_entry_mode(True, False)                                      # Entry mode, (increment, shift=False)
lcd.print_at(0, 0, "Shift display ->")                               # Write text, (col, row, text)
lcd.print_at(0, 1, "0123456789ABCDEF")                               # Write text, (col, row, text)
time.sleep(1)
for _ in range(6):
    lcd.shift_display(True)                                          # Shift display, (right)
    time.sleep(0.3)
lcd.return_home()                                                    # Return home, () — undoes the display shift
lcd.shift_cursor(True)                                               # Shift cursor, (right)
lcd.set_ddram_address(0x40)                                          # Set DDRAM address, (address)
time.sleep(1)

lcd.display(False)                                                   # Display on/off, (on)
time.sleep(0.8)
lcd.display(True)                                                    # Display on/off, (on)
