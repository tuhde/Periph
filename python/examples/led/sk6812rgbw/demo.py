from periph.transport.neopixel_micropython import NeoPixelTransport
from periph.chips.led.sk6812rgbw import SK6812RGBWFull
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
    if i == 0: return vv, t, p
    if i == 1: return q, vv, p
    if i == 2: return p, vv, t
    if i == 3: return p, q, vv
    if i == 4: return t, p, vv
    return vv, p, q


N_PIXELS         = 30
RAINBOW_DURATION = 10
WARM_WHITE_DURATION = 2
FPS              = 30
FRAME_DELAY      = 1.0 / FPS

spi = machine.SoftSPI(baudrate=2_400_000, polarity=0, phase=0,  # Create SoftSPI for NeoPixel, (baudrate=2400000, polarity=0, phase=0, sck=Pin, mosi=Pin, miso=Pin)
                      sck=machine.Pin(18), mosi=machine.Pin(19), miso=machine.Pin(20))
transport = NeoPixelTransport(spi)                               # Create NeoPixel transport, (spi)
strip = SK6812RGBWFull(transport, N_PIXELS)                      # Create SK6812RGBW full driver, (transport, n=N_PIXELS pixels)
strip.brightness = 180                                           # Set global brightness, (value=0–255) → None

# --- Rainbow rotation using RGB channels (white=0).
#     Each pixel is assigned a hue offset by its position; the offset advances
#     each frame so the rainbow rotates continuously around the strip.
#     Runs at ~30 fps for 10 seconds to demonstrate smooth HSV-based animation. ---
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
        print('rainbow hue_offset={:.3f}'.format(hue_offset))
        last_print = now
    elapsed = time.time() - now
    remaining = FRAME_DELAY - elapsed
    if remaining > 0:
        time.sleep(remaining)

# --- Warm-white flash at 5 Hz for 2 seconds.
#     r=255, g=200, b=150, w=255 produces a warm white by blending the dedicated
#     white element with amber-tinted RGB — showcasing the unique 4-channel RGBW
#     capability that distinguishes SK6812RGBW from RGB-only strips. ---
strip.brightness = 255                                           # Set global brightness, (value=0–255) → None
FLASH_HALF = 0.1
start = time.time()
state = True
while time.time() - start < WARM_WHITE_DURATION:
    if state:
        strip.fill(255, 200, 150, 255)                           # Fill all pixels warm white, (r=0–255, g=0–255, b=0–255, w=0–255) → None
    else:
        strip.off()                                              # Turn off all pixels, () → None
    state = not state
    time.sleep(FLASH_HALF)

# --- Return to rainbow ---
strip.brightness = 180                                           # Set global brightness, (value=0–255) → None
hue_offset = 0.0
last_print = time.time()
while True:
    for i in range(N_PIXELS):
        h = (hue_offset + i / N_PIXELS) % 1.0
        r, g, b = _hsv_to_rgb(h, 1.0, 1.0)
        strip.set_pixel(i, r, g, b, 0)                          # Set pixel i to rainbow hue (w=0), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → None
    strip.show()                                                 # Transmit buffer to strip, () → None
    hue_offset = (hue_offset + 1.0 / (N_PIXELS * 2)) % 1.0
    now = time.time()
    if now - last_print >= 1.0:
        print('rainbow hue_offset={:.3f}'.format(hue_offset))
        last_print = now
    time.sleep(FRAME_DELAY)
