from periph.connection.neopixel_auto import NeoPixelConnection
from periph.chips.led.ws2814 import WS2814Full
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
    if i == 0: return vv, t, p
    if i == 1: return q, vv, p
    if i == 2: return p, vv, t
    if i == 3: return p, q, vv
    if i == 4: return t, p, vv
    return vv, p, q


N_PIXELS         = 30
RAINBOW_DURATION = 5
FLASH_DURATION   = 2
DIM_DURATION     = 2
FPS              = 30
FRAME_DELAY      = 1.0 / FPS

connection = NeoPixelConnection(mosi=19, sck=18, miso=20)                               # Create NeoPixel connection, (spi)
strip = WS2814Full(connection, N_PIXELS)                          # Create WS2814 full driver, (connection, n=N_PIXELS pixels)

# --- Rainbow rotation using RGB channels (white=0).
#     Each pixel is assigned a hue offset by its position; the offset advances
#     each frame so the rainbow rotates continuously around the strip.
#     Demonstrates that WS2814's RGBW wire order is identity (R, G, B, W with
#     no reorder), unlike the SK6812RGBW's GRBW order. Runs at ~30 fps for
#     5 seconds. ---
hue_offset = 0.0
start = time.time()
last_print = start
while time.time() - start < RAINBOW_DURATION:
    for i in range(N_PIXELS):
        h = (hue_offset + i / N_PIXELS) % 1.0
        r, g, b = _hsv_to_rgb(h, 1.0, 1.0)
        strip.set_pixel(i, r, g, b, 0)                          # Set pixel i to rainbow hue (w=0), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → None
    strip.show()                                                 # Transmit buffer to strip, () → None
    hue_offset = (hue_offset + 1.0 / (N_PIXELS * 2)) % 1.0
    now = time.time()
    if now - last_print >= 1.0:
        print('mode=rainbow brightness={}'.format(strip.brightness))
        last_print = now
    elapsed = time.time() - now
    remaining = FRAME_DELAY - elapsed
    if remaining > 0:
        time.sleep(remaining)

# --- Warm white at full brightness for 2 seconds.
#     r=255, g=200, b=150, w=255 blends the dedicated white element with
#     amber-tinted RGB, exercising the white channel and the 32-bit RGBW
#     pixel word at full brightness. ---
strip.fill(255, 200, 150, 255)                                   # Fill all pixels warm white, (r=0–255, g=0–255, b=0–255, w=0–255) → None
start = time.time()
last_print = start
while time.time() - start < FLASH_DURATION:
    now = time.time()
    if now - last_print >= 1.0:
        print('mode=warm-white brightness={}'.format(strip.brightness))
        last_print = now
    time.sleep(0.1)

# --- Dim warm white to 50% using the brightness property and hold for 2 seconds.
#     Demonstrates that brightness scaling is non-destructive: the stored
#     RGBW values are unchanged, only the scale factor applied at show()
#     time changes. ---
strip.brightness = 128                                           # Set global brightness, (value=0–255) → None
strip.show()                                                     # Transmit buffer to strip, () → None
                                                                 # applies brightness scaling then calls connection.write()
start = time.time()
last_print = start
while time.time() - start < DIM_DURATION:
    now = time.time()
    if now - last_print >= 1.0:
        print('mode=warm-white-dimmed brightness={}'.format(strip.brightness))
        last_print = now
    time.sleep(0.1)

# --- Cycle to cool white at full brightness.
#     r=200, g=210, b=255, w=255 shifts the blend toward blue, showcasing the
#     dedicated white element paired with a cool-tinted RGB base. ---
strip.brightness = 255                                           # Set global brightness, (value=0–255) → None
strip.fill(200, 210, 255, 255)                                   # Fill all pixels cool white, (r=0–255, g=0–255, b=0–255, w=0–255) → None
print('mode=cool-white brightness={}'.format(strip.brightness))
