import time
from periph.connection.i2c_auto import I2CConnection
from periph.chips.led.saa1064 import SAA1064Full

connection = I2CConnection(0x38)

# --- Four-digit counter ---
# A fast counter from 0 to 9999, the decimal point sweeping across the four
# positions, the segment current stepped through 3/9/15/21 mA, a switch to
# static mode (only digits 1 and 2 stay lit), a 2 s segment test and the
# status byte (the PR flag is set after power-up and clears on the first read).
display = SAA1064Full(connection)                         # Create SAA1064 driver, (connection)

for n in range(0, 10000, 37):
    display.show_number(n)                                # Show the counter, (value 0–9999) → None
    time.sleep(0.01)

display.show_digits([1, 2, 3, 4])                         # Show four digits, (digits) → None
for pos in (1, 2, 3, 4):
    display.set_decimal_point(pos, True)                  # Light a decimal point, (position, on) → None
    time.sleep(0.4)
    display.set_decimal_point(pos, False)

for ma in (3, 9, 15, 21):
    display.set_current(ma)                               # Step the segment current, (ma) → None
    display.show_number(8888)
    time.sleep(1)
display.set_current(12)

display.set_mode(False)                                   # Static mode, (dynamic) → None
time.sleep(2)
display.set_mode(True)

display.segment_test(True)                                # Segment test, (on) → None
time.sleep(2)
display.segment_test(False)

print(hex(display.read_status()))                         # Read the status byte, () → int
print(display.power_reset_flag())                         # Read the PR flag, () → bool
display.clear()                                           # Blank all digits, () → None
print('===DONE: 0 passed, 0 failed===')
