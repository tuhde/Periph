from periph.connection.neopixel_auto import NeoPixelConnection
from periph.chips.led.ws2812b import WS2812BFull
import time

connection = NeoPixelConnection(mosi=19, sck=18, miso=20)                               # Create NeoPixel connection, (spi)
strip = WS2812BFull(connection, 8)                                # Create WS2812B full driver, (connection, n=8 pixels)

# fill — set all pixels and send immediately
strip.fill(255, 0, 0)                                            # Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255) → None
                                                                 # stores GRB in buffer and calls connection.write()
time.sleep(0.5)

# set individual pixels then show
strip.set_pixel(0, 255, 0, 0)                                    # Set pixel 0 to red (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255) → None
                                                                 # writes G,R,B bytes into internal buffer at position index*3
strip.set_pixel(1, 0, 255, 0)                                    # Set pixel 1 to green (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255) → None
                                                                 # writes G,R,B bytes into internal buffer at position index*3
strip.set_pixel(2, 0, 0, 255)                                    # Set pixel 2 to blue (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255) → None
                                                                 # writes G,R,B bytes into internal buffer at position index*3
strip.show()                                                     # Transmit buffer to strip, () → None
                                                                 # applies brightness scaling then calls connection.write()
time.sleep(0.5)

# set_pixels — write multiple pixels at once
colors = [(255, 128, 0), (128, 0, 255), (0, 255, 128),          # Set pixels from list of (r,g,b) tuples, (colors=list[tuple]) → None
          (255, 255, 0), (0, 255, 255), (255, 0, 255),
          (128, 128, 128), (255, 255, 255)]
strip.set_pixels(colors)                                         # Set pixels from list of (r,g,b) tuples, (colors=list[tuple]) → None
                                                                 # writes entries sequentially from pixel 0; ignores extras beyond strip length
strip.show()                                                     # Transmit buffer to strip, () → None
                                                                 # applies brightness scaling then calls connection.write()
time.sleep(0.5)

# brightness — global scale applied at show() time
strip.brightness = 64                                            # Set global brightness, (value=0–255) → None
                                                                 # stored value is scaled: sent = stored * brightness // 255
strip.show()                                                     # Transmit buffer to strip, () → None
                                                                 # applies brightness scaling then calls connection.write()
time.sleep(0.5)
strip.brightness = 255                                           # Set global brightness, (value=0–255) → None
                                                                 # stored value is scaled: sent = stored * brightness // 255

# fill_hsv — fill all pixels from HSV colour
strip.fill_hsv(0.0, 1.0, 1.0)                                   # Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → None
                                                                 # converts HSV to RGB then calls fill(); hue 0.0 = red
time.sleep(0.5)
strip.fill_hsv(0.333, 1.0, 1.0)                                  # Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → None
                                                                 # converts HSV to RGB then calls fill(); hue 0.333 = green
time.sleep(0.5)
strip.fill_hsv(0.667, 1.0, 1.0)                                  # Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → None
                                                                 # converts HSV to RGB then calls fill(); hue 0.667 = blue
time.sleep(0.5)

# rotate — shift pixel buffer by N positions
strip.set_pixels([(255, 0, 0)] + [(0, 0, 0)] * 7)               # Set pixels from list of (r,g,b) tuples, (colors=list[tuple]) → None
                                                                 # writes entries sequentially from pixel 0; ignores extras beyond strip length
strip.show()                                                     # Transmit buffer to strip, () → None
                                                                 # applies brightness scaling then calls connection.write()
time.sleep(0.5)
for _ in range(7):
    strip.rotate(1)                                              # Rotate pixel buffer left, (steps=1) → None
                                                                 # shifts buffer by steps pixel positions; wraps around; does not send
    strip.show()                                                 # Transmit buffer to strip, () → None
                                                                 # applies brightness scaling then calls connection.write()
    time.sleep(0.2)

strip.off()                                                      # Turn off all pixels, () → None
                                                                 # equivalent to fill(0, 0, 0)
