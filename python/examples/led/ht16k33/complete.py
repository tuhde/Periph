import time
from periph.connection.i2c_auto import I2CConnection
from periph.chips.led.ht16k33 import HT16K33Full

connection = I2CConnection(0x70)
matrix = HT16K33Full(connection)                          # Create HT16K33 driver, (connection)
matrix.clear()                                            # Blank the display, () → None
                                                          # zeros the shadow RAM and writes it
matrix.set_pixel(2, 5)                                    # Set one LED in the shadow RAM, (com 0–7, row 0–15, on) → None
matrix.show()                                             # Write the shadow RAM to the chip, () → None
print(matrix.get_pixel(2, 5))                             # Read a pixel from the shadow RAM, (com, row) → bool
matrix.write_raw(bytes([0xFF, 0x0F]), 0)                  # Write raw RAM bytes, (data 1–16 bytes, address 0–15) → None
                                                          # com 0 rows 0–11 lit
matrix.set_brightness(8)                                  # Set dimming, (level 0–15) → None
                                                          # 9/16 duty
matrix.set_blink(1)                                       # Set blink frequency, (frequency 0, 0.5, 1, 2 Hz) → None
                                                          # blinks at 1 Hz
time.sleep(3)
matrix.set_blink(0)
matrix.display_off()                                      # Display output off, () → None
matrix.display_on()                                       # Display output on, () → None
matrix.set_int_output(True, False)                        # ROW15/INT pin as INT output, (enable, active_high) → None
                                                          # active low interrupt output
print(matrix.read_keys())                                 # Read and clear key data, () → int bitmask
print(matrix.key_pressed())                               # Read the INT flag, () → bool
print(matrix.poll_interrupt())                            # Read keys if INT flag set, () → int bitmask (0 if none)
matrix.standby()                                          # Oscillator off, () → None
matrix.wake()                                             # Oscillator on and restore state, () → None
print('===DONE: 0 passed, 0 failed===')
