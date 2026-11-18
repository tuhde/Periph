import time
from periph.connection.i2c_auto import I2CConnection
from periph.chips.led.is31fl3731 import IS31FL3731Full

connection = I2CConnection(0x74)
matrix = IS31FL3731Full(connection)                       # Create IS31FL3731 driver, (connection)
matrix.clear()                                            # Switch all LEDs of the drawing frame off, () → None
matrix.set_pixel(2, 5, 200)                               # Set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → None
print(matrix.get_pixel(2, 5))                             # Read a pixel from the shadow copy, (row, col) → int 0–255
matrix.set_pixels(bytes([i % 256 for i in range(144)]))   # Write a whole frame, (data 144 bytes, row-major) → None
matrix.fill(40)                                           # Set every LED's PWM brightness, (brightness 0–255) → None
matrix.set_led_enabled(0, 0, False)                       # LED-control (on/off) bit, (row, col, on) → None
matrix.set_led_enabled(0, 0, True)
matrix.set_blink(1, 1, True)                              # Blink-enable bit of one LED, (row, col, on) → None
matrix.set_blink_all(False)                               # Blink-enable bit of every LED, (on) → None
matrix.enable_blink(True, 2)                              # Blink on, (enabled, period 0–7 × 0.27 s) → None
                                                          # 0.54 s period, 50 % duty
time.sleep(2)
matrix.enable_blink(False)
matrix.select_frame(1)                                    # Frame subsequent drawing writes to, (frame 0–7) → None
matrix.fill(100)
matrix.copy_frame(1, 2)                                   # Copy PWM values between frames, (src, dst) → None
matrix.clear_frame(2)                                     # Re-initialise one frame, (frame 0–7) → None
matrix.clear_all_frames()                                 # Re-initialise all eight frames, () → None
matrix.select_frame(0)
matrix.show_frame(0)                                      # Display a frame in Picture mode, (frame 0–7) → None
matrix.set_picture_mode()                                 # Picture mode, () → None
matrix.start_auto_play(2, 1, 110, 0)                      # Auto Frame Play, (frames 0–7, loops 0–7, delay_ms, start_frame) → None
                                                          # frames/loops 0 = all/endless; plays 2 frames once
time.sleep(1)
print(matrix.frame_state())                               # Read FRAME_STATE, () → int
print(matrix.current_frame())                             # Frame being displayed, () → int 0–7
print(matrix.movie_finished())                            # Movie-finished flag (clears it), () → bool
matrix.stop_auto_play()                                   # Back to Picture mode on the current frame, () → None
matrix.set_breath(True, 3, 3, 2)                          # Breath function, (enabled, fade_in, fade_out, extinguish 0–7) → None
time.sleep(3)
matrix.set_breath(False)
matrix.set_intensity_control(False)                       # Share frame 1's PWM with all frames, (use_frame1) → None
matrix.set_audio_gain(6, True, False)                     # Audio gain, (gain_db 0–21 step 3, agc, fast) → None
matrix.set_audio_rate(10)                                 # Audio ADC interval, (a 0–255, × 46 µs) → None
matrix.set_audio_sync(False)                              # Audio intensity modulation, (enabled) → None
matrix.set_audio_mode(False)                              # Audio Frame Play on/off, (enabled) → None
print(matrix.read_function_register(0x03))                # Raw function-page read, (reg 0–0x0C) → int
matrix.write_function_register(0x03, 0x0B)                # Raw function-page write, (reg, value) → None
print(matrix.poll_interrupt())                            # FRAME_STATE, clears INT, () → int
matrix.shutdown()                                         # Software shutdown, (on=True) → None
matrix.shutdown(False)                                    # Back to normal operation
print('===DONE: 0 passed, 0 failed===')
