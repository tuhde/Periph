from periph.transport.neopixel_micropython import NeoPixelTransport
from periph.chips.led.ws2812b import WS2812BMinimal
import machine
import time

spi = machine.SoftSPI(baudrate=2_400_000, polarity=0, phase=0,  # Create SoftSPI for NeoPixel, (baudrate=2400000, polarity=0, phase=0, sck=Pin, mosi=Pin, miso=Pin)
                      sck=machine.Pin(18), mosi=machine.Pin(19), miso=machine.Pin(20))
transport = NeoPixelTransport(spi)                               # Create NeoPixel transport, (spi)
strip = WS2812BMinimal(transport, 30)                            # Create WS2812B driver, (transport, n=30 pixels)

strip.fill(255, 0, 0)                                            # Fill all pixels red, (r=0–255, g=0–255, b=0–255) → None
time.sleep(1)
strip.fill(0, 255, 0)                                            # Fill all pixels green, (r=0–255, g=0–255, b=0–255) → None
time.sleep(1)
strip.fill(0, 0, 255)                                            # Fill all pixels blue, (r=0–255, g=0–255, b=0–255) → None
time.sleep(1)
strip.off()                                                      # Turn off all pixels, () → None
