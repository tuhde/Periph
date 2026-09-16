from periph.connection.neopixel_auto import NeoPixelConnection
from periph.chips.led.ws2814 import WS2814Minimal
import time

connection = NeoPixelConnection(mosi=19, sck=18, miso=20)                               # Create NeoPixel connection, (spi)
strip = WS2814Minimal(connection, 30)                             # Create WS2814 driver, (connection, n=30 pixels)

strip.fill(255, 0, 0)                                            # Fill all pixels red, (r=0–255, g=0–255, b=0–255, w=0–255) → None
time.sleep(1)
strip.fill(0, 255, 0)                                            # Fill all pixels green, (r=0–255, g=0–255, b=0–255, w=0–255) → None
time.sleep(1)
strip.fill(0, 0, 255)                                            # Fill all pixels blue, (r=0–255, g=0–255, b=0–255, w=0–255) → None
time.sleep(1)
strip.fill(0, 0, 0, 255)                                         # Fill all pixels white (W channel), (r=0–255, g=0–255, b=0–255, w=0–255) → None
time.sleep(1)
strip.off()                                                      # Turn off all pixels, () → None
