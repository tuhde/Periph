import time

from periph.connection.i2c_auto import I2CConnection
from periph.chips.display.pcf8578 import PCF8578Full

connection = I2CConnection(0x3C)

# --- Initialise a 16 x 24 dot-matrix panel ---
# 1:16 multiplex gives 16 rows and 24 columns; the constructor clears the RAM
# before switching the display on so no random start-up pattern is shown.
lcd = PCF8578Full(connection, 16)                         # Create PCF8578 driver, (connection, rows=16 8/16/24/32)

# --- Draw a border and both diagonals ---
# Lines and rectangles are rendered in the shadow buffer and sent as one
# batch of changed bytes per run of columns.
lcd.draw_rect(0, 0, 24, 16)                               # Draw rectangle outline, (x, y, w, h, on=True, fill=False)
lcd.draw_line(0, 0, 23, 15)                               # Draw line, (x0, y0, x1, y1, on=True)
lcd.draw_line(0, 15, 23, 0)                               # Draw line, (x0, y0, x1, y1, on=True)

# --- Flash the display twice with inverse video ---
# Inverse video only changes how the RAM is shown, so the picture is kept.
for _ in range(2):
    lcd.set_display(PCF8578Full.DISPLAY_INVERSE)          # Set display status, (status 0–3)
    time.sleep(0.4)
    lcd.set_display(PCF8578Full.DISPLAY_NORMAL)           # Set display status, (status 0–3)
    time.sleep(0.4)

# --- Fill all four RAM banks with distinct patterns ---
# At 1:16 only banks 0 and 1 are visible; banks 2 and 3 act as off-screen
# buffers that the start bank can scroll into view.
patterns = (0x55, 0x33, 0x0F, 0xFF)
for bank in range(4):
    lcd.write_ram(16, bank, bytes([patterns[bank]] * 24))  # Write raw RAM, (x 0–39, bank 0–3, data bytes, mode=0)

# --- Scroll through the banks with the hardware start bank ---
# Stepping the start bank scrolls the display by 8 rows without rewriting RAM.
for bank in range(4):
    lcd.set_start_bank(bank)                              # Set start bank, (bank 0–3)
    first = lcd.read_ram(16, bank, 1)                     # Read raw RAM, (x 0–39, bank 0–3, n, mode=0) → bytes
    print('start bank', bank, 'first column byte', hex(first[0]))
    time.sleep(1)

# --- Read back the first 8 RAM columns of bank 0 ---
# RAM columns 0-7 are never displayed at 1:16, so they serve as scratch
# storage; the chip supports reads in mixed mode, so the bytes can be verified.
lcd.set_start_bank(0)                                     # Set start bank, (bank 0–3)
lcd.write_ram(0, 0, bytes(range(1, 9)))                   # Write raw RAM, (x 0–39, bank 0–3, data bytes, mode=0)
print([hex(b) for b in lcd.read_ram(0, 0, 8)])            # Read raw RAM, (x 0–39, bank 0–3, n, mode=0) → bytes
print('===DONE: 0 passed, 0 failed===')
