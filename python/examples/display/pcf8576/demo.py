import machine
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.display.pcf8576 import PCF8576Full
from machine import Pin

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)

# --- 4-digit countdown from 9999 to 0000 on a 1:4 multiplex 7-segment LCD ---
# The PCF8576 drives four 7-segment digits from a single I2C bus; the host
# encodes each digit using the chip's 1:4 multiplex bit layout (a/c/b/DP/f/e/g/d)
# and writes all four with one write_raw() call. The countdown runs once per
# second and the terminal mirrors the value sent to the display.
lcd = PCF8576Full(transport)                             # Create PCF8576 driver, (transport)

for n in range(9999, -1, -1):
    d0 = (n // 1000) % 10
    d1 = (n // 100) % 10
    d2 = (n // 10) % 10
    d3 = n % 10
    out = bytearray()
    for d in (d0, d1, d2, d3):
        out.append(PCF8576Full._SEVEN_SEG[d])           # Encode 7-segment digit, (digit 0–9) → int byte
    lcd.write_raw(0, bytes(out))                         # Write all four digits, (address 0, 4 bytes) → None
    print('countdown: {:04d}'.format(n))
    machine.sleep(1000)

# --- Stop indicator: light only the middle segments (g) on every digit ---
# When the counter reaches zero we replace the "0000" pattern with "----" to
# signal that the demo has finished. Each digit's g segment is bit 1, so a
# 0x02 byte lights just the bar across the middle.
dash = bytearray([0x02] * 4)
lcd.write_raw(0, bytes(dash))                            # Write dash pattern, (address 0, 4 bytes) → None
print('countdown complete')
print('===DONE: 0 passed, 0 failed===')
