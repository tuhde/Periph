from periph.connection.spi_auto import SPIConnection
from periph.chips.led.apa102 import APA102Full
import time

connection = SPIConnection(bus=0, device=0, polarity=0, phase=0, baudrate=1_000_000)  # Create SPI connection, (bus=0, device=0, mode=0, baudrate=1MHz)
strip = APA102Full(connection, 8)                                                     # Create APA102 full driver, (connection, n=8 pixels)

# fill — set all pixels and send immediately
strip.fill(255, 0, 0)                                                                 # Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255) → None
                                                                                        # stores brightness/B/G/R in buffer and calls connection.write()
time.sleep(0.5)

# set individual pixels then show
strip.set_pixel(0, 255, 0, 0)                                                         # Set pixel 0 to red (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → None
                                                                                        # writes brightness, B, G, R bytes into internal buffer at position index*4
strip.set_pixel(1, 0, 255, 0)                                                         # Set pixel 1 to green (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → None
                                                                                        # writes brightness, B, G, R bytes into internal buffer at position index*4
strip.set_pixel(2, 0, 0, 255)                                                         # Set pixel 2 to blue (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → None
                                                                                        # writes brightness, B, G, R bytes into internal buffer at position index*4
strip.show()                                                                          # Transmit buffer to strip, () → None
                                                                                        # applies software brightness scaling then calls connection.write()
time.sleep(0.5)

# set_pixels — write multiple pixels at once
colors = [(255, 128, 0), (128, 0, 255), (0, 255, 128),                               # Set pixels from list of (r,g,b) or (r,g,b,brightness) tuples, (colors=list[tuple]) → None
          (255, 255, 0), (0, 255, 255), (255, 0, 255),
          (128, 128, 128), (255, 255, 255)]
strip.set_pixels(colors)                                                              # Set pixels from list of (r,g,b) or (r,g,b,brightness) tuples, (colors=list[tuple]) → None
                                                                                        # writes entries sequentially from pixel 0; ignores extras beyond strip length
strip.show()                                                                          # Transmit buffer to strip, () → None
                                                                                        # applies software brightness scaling then calls connection.write()
time.sleep(0.5)

# set_pixels with per-pixel hardware brightness
colors_bright = [(255, 0, 0, 31), (255, 0, 0, 16), (255, 0, 0, 8), (255, 0, 0, 4),  # Set pixels with varying hardware brightness, (colors=list[tuple]) → None
                 (0, 255, 0, 31), (0, 255, 0, 16), (0, 255, 0, 8), (0, 255, 0, 4)]
strip.set_pixels(colors_bright)                                                       # Set pixels from list of (r,g,b,brightness) tuples, (colors=list[tuple]) → None
                                                                                        # writes entries sequentially from pixel 0; ignores extras beyond strip length
strip.show()                                                                          # Transmit buffer to strip, () → None
                                                                                        # applies software brightness scaling then calls connection.write()
time.sleep(0.5)

# brightness — global software scale applied at show() time
strip.brightness = 64                                                                 # Set global software brightness, (value=0–255) → None
                                                                                        # stored RGB value is scaled: sent = stored * brightness // 255; hardware brightness byte unchanged
strip.show()                                                                          # Transmit buffer to strip, () → None
                                                                                        # applies software brightness scaling then calls connection.write()
time.sleep(0.5)
strip.brightness = 255                                                                # Set global software brightness, (value=0–255) → None
                                                                                        # stored RGB value is scaled: sent = stored * brightness // 255; hardware brightness byte unchanged

# fill_hsv — fill all pixels from HSV colour
strip.fill_hsv(0.0, 1.0, 1.0)                                                         # Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → None
                                                                                        # converts HSV to RGB then calls fill(); hue 0.0 = red
time.sleep(0.5)
strip.fill_hsv(0.333, 1.0, 1.0)                                                       # Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → None
                                                                                        # converts HSV to RGB then calls fill(); hue 0.333 = green
time.sleep(0.5)
strip.fill_hsv(0.667, 1.0, 1.0)                                                       # Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → None
                                                                                        # converts HSV to RGB then calls fill(); hue 0.667 = blue
time.sleep(0.5)

# rotate — shift pixel buffer by N positions
strip.set_pixels([(255, 0, 0)] + [(0, 0, 0)] * 7)                                     # Set pixels from list of (r,g,b) tuples, (colors=list[tuple]) → None
                                                                                        # writes entries sequentially from pixel 0; ignores extras beyond strip length
strip.show()                                                                          # Transmit buffer to strip, () → None
                                                                                        # applies software brightness scaling then calls connection.write()
time.sleep(0.5)
for _ in range(7):
    strip.rotate(1)                                                                   # Rotate pixel buffer left, (steps=1) → None
                                                                                        # shifts buffer by steps pixel positions; wraps around; does not send
    strip.show()                                                                      # Transmit buffer to strip, () → None
                                                                                        # applies software brightness scaling then calls connection.write()
    time.sleep(0.2)

strip.off()                                                                           # Turn off all pixels, () → None
                                                                                        # equivalent to fill(0, 0, 0)