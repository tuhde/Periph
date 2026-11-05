from periph.connection.i2c_auto import I2CConnection
from periph.chips.display.pcf8578 import PCF8578Full

connection = I2CConnection(0x3C)
lcd = PCF8578Full(connection, 16)                         # Create PCF8578 driver, (connection, rows=16 8/16/24/32)
                                                          # clears RAM and switches the display on in mixed mode
lcd.clear()                                               # Clear display RAM, () → None
                                                          # zeroes all 4 banks x 40 columns and the shadow
lcd.set_pixel(1, 1)                                       # Set one pixel, (x 0–23, y 0–15, on=True) → None
                                                          # writes a single RAM byte from the shadow
lcd.write_columns(4, 0, bytes([0xFF, 0x81, 0xFF]))        # Write column bytes, (x 0–23, bank 0–1, data bytes) → None
                                                          # character access mode, bit 0 = top row of the bank
on = lcd.get_pixel(1, 1)                                  # Read pixel from shadow, (x, y) → bool
                                                          # no bus traffic
lcd.fill(True)                                            # Fill display, (on=True) → None
                                                          # every displayed pixel on
lcd.fill(False)                                           # Fill display, (on=True) → None
                                                          # every displayed pixel off
lcd.draw_line(0, 0, 23, 15)                               # Draw line, (x0, y0, x1, y1, on=True) → None
                                                          # Bresenham, written as one batch of changed bytes
lcd.draw_rect(0, 0, 24, 16)                               # Draw rectangle outline, (x, y, w, h, on=True, fill=False) → None
                                                          # border around the whole panel
lcd.draw_rect(8, 4, 8, 8, True, True)                     # Draw filled rectangle, (x, y, w, h, on=True, fill=False) → None
                                                          # solid 8x8 block in the middle
lcd.show_bitmap(2, 8, [bytes([0x18, 0x24, 0x42, 0x81])])  # Blit bitmap, (x, y multiple of 8, bands list of column bytes) → None
                                                          # one 8-pixel-high band at the lower bank
lcd.set_display(PCF8578Full.DISPLAY_INVERSE)              # Set display status, (status 0 blank/1 normal/2 all on/3 inverse) → None
                                                          # inverse video; RAM is preserved
lcd.set_display(PCF8578Full.DISPLAY_NORMAL)               # Set display status, (status 0–3) → None
                                                          # back to normal video
lcd.set_mode(24, True)                                    # Set multiplex rate, (rows 8/16/24/32, mixed=True) → None
                                                          # 1:24 drive; mixed mode keeps RAM accessible
lcd.set_mode(16, True)                                    # Set multiplex rate, (rows 8/16/24/32, mixed=True) → None
                                                          # back to 1:16
lcd.set_start_bank(1)                                     # Set start bank, (bank 0–3) → None
                                                          # shows RAM bank 1 at the top (hardware scroll)
lcd.scroll(-1)                                            # Scroll by banks, (banks int) → None
                                                          # adds -1 to the start bank modulo banks in use
lcd.write_ram(16, 0, bytes([0xAA, 0x55]))                 # Write raw RAM, (x 0–39, bank 0–3, data bytes, mode=0) → None
                                                          # character order at RAM column 16
data = lcd.read_ram(16, 0, 2)                             # Read raw RAM, (x 0–39, bank 0–3, n, mode=0) → bytes
                                                          # address-set commands, repeated start, then a read
lcd.device_select(0)                                      # Select device subaddress, (subaddress 0–15) → None
                                                          # 0 addresses this PCF8578; others target cascaded PCF8579s
lcd.reset()                                               # Re-initialise, () → None
                                                          # clears RAM and restores normal video
print('===DONE: 0 passed, 0 failed===')
