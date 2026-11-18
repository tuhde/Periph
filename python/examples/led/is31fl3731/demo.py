import time
from periph.connection.i2c_auto import I2CConnection
from periph.chips.led.is31fl3731 import IS31FL3731Full

connection = I2CConnection(0x74)

# --- Animated matrix: auto-play movie, blinking checkerboard, breath, gamma fade ---
# A diagonal moves across frames 1-8 and is played by the chip itself, then a
# blinking checkerboard is shown in Picture mode, a filled frame breathes, the
# matrix fades through the 64-step gamma table, a finite 2-loop movie ends with
# an interrupt, and the display is shut down.
matrix = IS31FL3731Full(connection)                       # Create IS31FL3731 driver, (connection)
matrix.clear_all_frames()                                 # Initialise all eight frames, () → None

for f in range(8):
    matrix.select_frame(f)                                # Draw on frame f, (frame 0–7) → None
    for r in range(9):
        matrix.set_pixel(r, (f + r) % 16, 255)            # One diagonal LED per row, (row, col, brightness) → None
matrix.select_frame(0)

matrix.start_auto_play(0, 0, 100, 0)                      # Endless 8-frame movie, (frames, loops, delay_ms, start_frame) → None
t_end = time.time() + 3
while time.time() < t_end:
    print('frame', matrix.current_frame())                # Frame being displayed, () → int
    time.sleep(0.5)
matrix.stop_auto_play()                                   # Picture mode, current frame kept, () → None

matrix.select_frame(0)
matrix.clear()                                            # Blank the drawing frame, () → None
pixels = bytearray(144)
for r in range(9):
    for c in range(16):
        pixels[r * 16 + c] = 255 if (r + c) % 2 == 0 else 0
matrix.set_pixels(bytes(pixels))                          # Checkerboard, (data 144 bytes) → None
matrix.set_blink_all(True)                                # Every LED blinks, (on) → None
matrix.enable_blink(True, 2)                              # Blink period 2 × 0.27 s, (enabled, period) → None
matrix.show_frame(0)                                      # Show frame 1 in Picture mode, (frame) → None
time.sleep(3)
matrix.enable_blink(False)
matrix.set_blink_all(False)

matrix.fill(255)                                          # Full brightness, (brightness) → None
matrix.set_breath(True, 4, 4, 2)                          # Breathing, (enabled, fade_in, fade_out, extinguish) → None
time.sleep(5)
matrix.set_breath(False)

for level in IS31FL3731Full.GAMMA_64:
    matrix.fill(level)                                    # Gamma-corrected fade, (brightness) → None
    time.sleep(0.03)

def on_end(status):
    print('movie finished at frame', status & 0x07)

matrix.on_interrupt(on_end)                               # Callback when a movie ends, (callback, int_pin=None) → None
matrix.set_picture_mode()                                 # Picture mode, () → None
matrix.start_auto_play(0, 2, 100, 0)                      # Two loops, (frames, loops, delay_ms, start_frame) → None
time.sleep(3)
matrix.off_interrupt()                                    # Unsubscribe, () → None
print(matrix.movie_finished())                            # Flag already read by the poller, () → bool

matrix.clear_all_frames()
matrix.shutdown()                                         # Display off, (on=True) → None
print('===DONE: 0 passed, 0 failed===')
