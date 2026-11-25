import time
from periph.connection.i2c_auto import I2CConnection
from periph.chips.led.is31fl3731 import IS31FL3731Minimal

connection = I2CConnection(0x74)
matrix = IS31FL3731Minimal(connection)                    # Create IS31FL3731 driver, (connection)

for col in range(IS31FL3731Minimal.COLS):
    matrix.set_pixel(0, col, 128)                         # Set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → None
time.sleep(1)
matrix.fill(32)                                           # Set every LED's PWM brightness, (brightness 0–255) → None
time.sleep(1)
matrix.clear()                                            # Switch all LEDs off, () → None
print('===DONE: 0 passed, 0 failed===')
