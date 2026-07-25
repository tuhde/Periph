from periph.transport.neopixel_auto import NeoPixelTransport
from periph.chips.led.ws2812b import WS2812BMinimal
import time

transport = NeoPixelTransport(mosi=19, sck=18, miso=20)                               # Create NeoPixel transport, (spi)
strip = WS2812BMinimal(transport, 30)                            # Create WS2812B driver, (transport, n=30 pixels)

strip.fill(255, 0, 0)                                            # Fill all pixels red, (r=0–255, g=0–255, b=0–255) → None
time.sleep(1)
strip.fill(0, 255, 0)                                            # Fill all pixels green, (r=0–255, g=0–255, b=0–255) → None
time.sleep(1)
strip.fill(0, 0, 255)                                            # Fill all pixels blue, (r=0–255, g=0–255, b=0–255) → None
time.sleep(1)
strip.off()                                                      # Turn off all pixels, () → None
