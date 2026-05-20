from periph.transport.neopixel_micropython import NeoPixelTransport
from periph.chips.led.ws2812b import WS2812BFull
import machine
import time


def _hsv_to_rgb(h, s, v):
    if s == 0.0:
        c = int(v * 255)
        return c, c, c
    i = int(h * 6.0)
    f = h * 6.0 - i
    p = int(v * (1.0 - s) * 255)
    q = int(v * (1.0 - s * f) * 255)
    t = int(v * (1.0 - s * (1.0 - f)) * 255)
    vv = int(v * 255)
    i = i % 6
    if i == 0:
        return vv, t, p
    if i == 1:
        return q, vv, p
    if i == 2:
        return p, vv, t
    if i == 3:
        return p, q, vv
    if i == 4:
        return t, p, vv
    return vv, p, q


N_PIXELS = 30
RAINBOW_DURATION_S = 10
STROBE_DURATION_S  = 2
FPS                = 30
FRAME_DELAY        = 1.0 / FPS

spi = machine.SoftSPI(baudrate=2_400_000, polarity=0, phase=0,  # Create SoftSPI for NeoPixel, (baudrate=2400000, polarity=0, phase=0, sck=Pin, mosi=Pin, miso=Pin)
                      sck=machine.Pin(18), mosi=machine.Pin(19), miso=machine.Pin(20))
transport = NeoPixelTransport(spi)                               # Create NeoPixel transport, (spi)
strip = WS2812BFull(transport, N_PIXELS)                         # Create WS2812B full driver, (transport, n=N_PIXELS pixels)
strip.brightness = 180                                           # Set global brightness, (value=0–255) → None

# --- Rainbow rotation: each pixel is assigned a hue offset by its position;
#     the offset is advanced each frame so the rainbow rotates around the strip.
#     Running at 30 fps for 10 seconds gives a smooth, continuous animation. ---
hue_offset = 0.0
start = time.time()
last_print = start
while time.time() - start < RAINBOW_DURATION_S:
    for i in range(N_PIXELS):
        h = (hue_offset + i / N_PIXELS) % 1.0
        strip.set_pixel(i, *_hsv_to_rgb(h, 1.0, 1.0))          # Set pixel i to rainbow hue, (index=0–n-1, r=0–255, g=0–255, b=0–255) → None
    strip.show()                                                 # Transmit buffer to strip, () → None
    hue_offset = (hue_offset + 1.0 / (N_PIXELS * 2)) % 1.0
    now = time.time()
    if now - last_print >= 1.0:
        print('rainbow hue_offset={:.3f}'.format(hue_offset))
        last_print = now
    elapsed = time.time() - now
    remaining = FRAME_DELAY - elapsed
    if remaining > 0:
        time.sleep(remaining)

# --- Strobe effect: alternate full white and off at 10 Hz for 2 seconds.
#     Uses brightness=255 for maximum intensity, then brightness=0 for off,
#     demonstrating the non-destructive brightness scaling — pixel values in
#     the buffer are never zeroed. ---
strip.brightness = 255                                           # Set global brightness, (value=0–255) → None
strip.fill(255, 255, 255)                                        # Pre-load white into buffer, (r=0–255, g=0–255, b=0–255) → None
STROBE_HALF = 0.05
start = time.time()
state = True
while time.time() - start < STROBE_DURATION_S:
    strip.brightness = 255 if state else 0                       # Set global brightness, (value=0–255) → None
    strip.show()                                                 # Transmit buffer to strip, () → None
    state = not state
    time.sleep(STROBE_HALF)

# --- Return to rainbow ---
strip.brightness = 180                                           # Set global brightness, (value=0–255) → None
hue_offset = 0.0
start = time.time()
last_print = start
while True:
    for i in range(N_PIXELS):
        h = (hue_offset + i / N_PIXELS) % 1.0
        r, g, b = _hsv_to_rgb(h, 1.0, 1.0)
        strip.set_pixel(i, r, g, b)                              # Set pixel i to rainbow hue, (index=0–n-1, r=0–255, g=0–255, b=0–255) → None
    strip.show()                                                 # Transmit buffer to strip, () → None
    hue_offset = (hue_offset + 1.0 / (N_PIXELS * 2)) % 1.0
    now = time.time()
    if now - last_print >= 1.0:
        print('rainbow hue_offset={:.3f}'.format(hue_offset))
        last_print = now
    time.sleep(FRAME_DELAY)
