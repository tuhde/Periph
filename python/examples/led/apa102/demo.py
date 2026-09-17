from periph.connection.spi_auto import SPIConnection
from periph.chips.led.apa102 import APA102Full
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
FPS = 60
FRAME_DELAY = 1.0 / FPS

connection = SPIConnection(bus=0, device=0, polarity=0, phase=0, baudrate=1_000_000)  # Create SPI connection, (bus=0, device=0, mode=0, baudrate=1MHz)
strip = APA102Full(connection, N_PIXELS)                                              # Create APA102 full driver, (connection, n=N_PIXELS pixels)

# --- 13-bit effective color depth demonstration ---
# First pass: full hardware brightness (31) for maximum drive current
# Second pass: hardware brightness 1 (1/31 current) to show hardware vs software dimming

# --- Pass 1: Full hardware brightness (31) ---
# Rainbow sweep at hardware brightness 31 uses full 8-bit PWM channels + 5-bit
# hardware current control = 13-bit effective depth per channel.
strip.brightness = 255                                                                # Set global software brightness, (value=0–255) → None
hue_offset = 0.0
start = time.time()
last_print = start
while time.time() - start < RAINBOW_DURATION_S:
    for i in range(N_PIXELS):
        h = (hue_offset + i / N_PIXELS) % 1.0
        r, g, b = _hsv_to_rgb(h, 1.0, 1.0)
        strip.set_pixel(i, r, g, b, 31)                                               # Set pixel i to rainbow hue at hardware brightness 31, (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → None
    strip.show()                                                                      # Transmit buffer to strip, () → None
                                                                                        # applies software brightness scaling then calls connection.write()
    hue_offset = (hue_offset + 1.0 / (N_PIXELS * 2)) % 1.0
    now = time.time()
    if now - last_print >= 1.0:
        print('rainbow hw_brightness=31 hue_offset={:.3f}'.format(hue_offset))
        last_print = now
    elapsed = time.time() - now
    remaining = FRAME_DELAY - elapsed
    if remaining > 0:
        time.sleep(remaining)

# --- Pass 2: Low hardware brightness (1) ---
# Same 8-bit RGB values but hardware brightness=1 (1/31 drive current).
# Demonstrates hardware current control vs software brightness scaling.
strip.brightness = 255                                                                # Set global software brightness, (value=0–255) → None
hue_offset = 0.0
start = time.time()
last_print = start
while time.time() - start < RAINBOW_DURATION_S:
    for i in range(N_PIXELS):
        h = (hue_offset + i / N_PIXELS) % 1.0
        r, g, b = _hsv_to_rgb(h, 1.0, 1.0)
        strip.set_pixel(i, r, g, b, 1)                                                # Set pixel i to rainbow hue at hardware brightness 1, (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → None
    strip.show()                                                                      # Transmit buffer to strip, () → None
                                                                                        # applies software brightness scaling then calls connection.write()
    hue_offset = (hue_offset + 1.0 / (N_PIXELS * 2)) % 1.0
    now = time.time()
    if now - last_print >= 1.0:
        print('rainbow hw_brightness=1 hue_offset={:.3f}'.format(hue_offset))
        last_print = now
    elapsed = time.time() - now
    remaining = FRAME_DELAY - elapsed
    if remaining > 0:
        time.sleep(remaining)

strip.off()                                                                           # Turn off all pixels, () → None