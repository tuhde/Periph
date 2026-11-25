import time
from periph.connection.i2c_auto import I2CConnection
from periph.chips.led.saa1064 import SAA1064Full

connection = I2CConnection(0x38)
display = SAA1064Full(connection)                         # Create SAA1064 driver, (connection)
display.show_digits([1, 2, 3, 4])                         # Show four digits, (digits 4 × 0–9 or None) → None
                                                          # default mapping a=bit0 … g=bit6, dp=bit7
time.sleep(1)
display.show_number(42)                                   # Show a number, (value 0–9999) → None
                                                          # leading zeros are blanked
display.write_raw([0x7F, 0x7F], 3)                        # Write raw segment bytes, (patterns 1–4, first_digit 1–4) → None
                                                          # digits 3 and 4 show 8
time.sleep(1)
display.show_digit(2, 7, True)                            # Show one digit, (position 1–4, value 0–9 or None, dp) → None
                                                          # digit 2 = 7 with decimal point
display.set_decimal_point(2, False)                       # Switch a decimal point, (position 1–4, on) → None
                                                          # keeps the cached segments
display.set_segment_map({'a': 0, 'b': 1, 'c': 2, 'd': 3, 'e': 4, 'f': 5, 'g': 6, 'dp': 7})  # Remap segment bits, (mapping a–g, dp → bit 0–7) → None
display.set_current(21)                                   # Set segment current, (ma 0, 3, 6, 9, 12, 15, 18, 21) → None
                                                          # C4–C6
display.set_mode(False)                                   # Select static/dynamic mode, (dynamic) → None
                                                          # static shows only digits 1 and 2
time.sleep(1)
display.set_mode(True)
display.blank(True, False)                                # Blank digit pairs, (digits_13, digits_24) → None
                                                          # digits 1+3 off
time.sleep(1)
display.blank(False, False)
display.segment_test(True)                                # Segment test, (on) → None
                                                          # lights every segment
time.sleep(1)
display.segment_test(False)
print(display.read_status())                              # Read the status byte, () → int
print(display.power_reset_flag())                         # Read the PR flag, () → bool
                                                          # set after power-up, clears when read
display.write_control(0x47)                               # Write the raw control byte, (value 0–127) → None
                                                          # dynamic mode, unblanked, 12 mA
display.clear()                                           # Blank all digits, () → None
print('===DONE: 0 passed, 0 failed===')
