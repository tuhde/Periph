from periph.transport.neopixel_micropython import NeoPixelTransport
from periph.chips.led.sk6812rgbw import SK6812RGBWFull
import machine
import time

spi = machine.SoftSPI(baudrate=2_400_000, polarity=0, phase=0,  # Create SoftSPI for NeoPixel, (baudrate=2400000, polarity=0, phase=0, sck=Pin, mosi=Pin, miso=Pin)
                      sck=machine.Pin(18), mosi=machine.Pin(19), miso=machine.Pin(20))
transport = NeoPixelTransport(spi)                               # Create NeoPixel transport, (spi)
strip = SK6812RGBWFull(transport, 8)                             # Create SK6812RGBW full driver, (transport, n=8 pixels)

# fill — set all pixels and send immediately
strip.fill(255, 0, 0)                                            # Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255, w=0–255) → None
                                                                 # stores GRBW in buffer and calls transport.write()
time.sleep(0.5)

# fill with white channel active
strip.fill(0, 0, 0, 255)                                         # Fill all pixels with W channel, (r=0–255, g=0–255, b=0–255, w=0–255) → None
                                                                 # W=255 activates dedicated white LED element; RGB channels stay dark
time.sleep(0.5)

# set individual pixels then show
strip.set_pixel(0, 255, 0, 0)                                    # Set pixel 0 to red (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → None
                                                                 # writes G,R,B,W bytes into internal buffer at position index*4
strip.set_pixel(1, 0, 255, 0)                                    # Set pixel 1 to green (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → None
                                                                 # writes G,R,B,W bytes into internal buffer at position index*4
strip.set_pixel(2, 0, 0, 255)                                    # Set pixel 2 to blue (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → None
                                                                 # writes G,R,B,W bytes into internal buffer at position index*4
strip.set_pixel(3, 0, 0, 0, 255)                                 # Set pixel 3 to white (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → None
                                                                 # w=255 lights the dedicated white element; RGB remain off
strip.show()                                                     # Transmit buffer to strip, () → None
                                                                 # applies brightness scaling then calls transport.write()
time.sleep(0.5)

# set_pixels — write multiple pixels at once
colors = [(255, 128, 0), (128, 0, 255), (0, 255, 128), (0, 0, 0, 200),
          (255, 0, 0, 100), (0, 255, 0), (0, 0, 255), (128, 128, 128, 128)]
strip.set_pixels(colors)                                         # Set pixels from list of (r,g,b) or (r,g,b,w) tuples, (colors=list[tuple]) → None
                                                                 # 3-element tuples use w=0; extra entries beyond strip length ignored
strip.show()                                                     # Transmit buffer to strip, () → None
                                                                 # applies brightness scaling then calls transport.write()
time.sleep(0.5)

# brightness — global scale applied at show() time
strip.brightness = 64                                            # Set global brightness, (value=0–255) → None
                                                                 # stored value is scaled: sent = stored * brightness // 255
strip.show()                                                     # Transmit buffer to strip, () → None
                                                                 # applies brightness scaling then calls transport.write()
time.sleep(0.5)
strip.brightness = 255                                           # Set global brightness, (value=0–255) → None
                                                                 # stored value is scaled: sent = stored * brightness // 255

# fill_hsv — fill all pixels from HSV colour (white=0)
strip.fill_hsv(0.0, 1.0, 1.0)                                   # Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → None
                                                                 # converts HSV to RGB (w=0) then calls fill(); hue 0.0 = red
time.sleep(0.5)
strip.fill_hsv(0.333, 1.0, 1.0)                                  # Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → None
                                                                 # converts HSV to RGB (w=0) then calls fill(); hue 0.333 = green
time.sleep(0.5)
strip.fill_hsv(0.667, 1.0, 1.0)                                  # Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → None
                                                                 # converts HSV to RGB (w=0) then calls fill(); hue 0.667 = blue
time.sleep(0.5)

# rotate — shift pixel buffer by N positions
strip.set_pixels([(255, 0, 0, 0)] + [(0, 0, 0, 0)] * 7)         # Set pixels from list of (r,g,b,w) tuples, (colors=list[tuple]) → None
                                                                 # writes entries sequentially from pixel 0; ignores extras beyond strip length
strip.show()                                                     # Transmit buffer to strip, () → None
                                                                 # applies brightness scaling then calls transport.write()
time.sleep(0.5)
for _ in range(7):
    strip.rotate(1)                                              # Rotate pixel buffer left, (steps=1) → None
                                                                 # shifts buffer by steps whole-pixel (4-byte) positions; wraps around; does not send
    strip.show()                                                 # Transmit buffer to strip, () → None
                                                                 # applies brightness scaling then calls transport.write()
    time.sleep(0.2)

strip.off()                                                      # Turn off all pixels, () → None
                                                                 # equivalent to fill(0, 0, 0, 0)
