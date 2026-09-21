"""TPIC6B595 demo — "knight rider" chase pattern across two cascaded devices.

Hardware:
  Two cascaded TPIC6B595s driving 16 LEDs as an automotive-cluster-style
  indicator bank (DRAIN0..DRAIN7 on each device). Each LED's anode goes to
  the supply through a series resistor and its cathode to a DRAIN pin;
  writing 1 turns the LED on (active-low via the DMOS sink).

The demo walks a single lit LED back and forth across all 16 outputs and,
every few sweeps, blanks every output for half a second via set_output_enable(False)
to demonstrate glitch-free global blanking. The shadow register is untouched
across the blank, so the chase pattern resumes exactly where it left off.
"""
from periph.connection.sipo_micropython import SiPoConnection
from periph.chips.io_expander.tpic6b595 import Tpic6b595Full
from machine import SPI, Pin
import time

NUM_DEVICES = 2
NUM_OUTPUTS = NUM_DEVICES * 8

# --- Wire up the SiPo connection ---
# Two cascaded TPIC6B595s: SER OUT of the upstream device → SER IN of the next.
# RCK pulses latch both devices at once; SRCLR lets clear() reset deterministically;
# G lets set_output_enable() blank every output globally without touching shadow state.
spi = SPI(0, baudrate=1_000_000, polarity=0, phase=0)                       # Create SPI bus, (id=0, baud=1 MHz, mode=0)
rck    = Pin(17, Pin.OUT)                                                    # Configure RCK GPIO, (pin=17, mode=OUT)
srclr  = Pin(16, Pin.OUT)                                                    # Configure SRCLR GPIO, (pin=16, mode=OUT)
g      = Pin(15, Pin.OUT)                                                    # Configure G GPIO, (pin=15, mode=OUT)
connection = SiPoConnection(spi, rck, srclr=srclr, g=g)                    # Create SiPo connection, (spi, rck, srclr, g)

chip = Tpic6b595Full(connection, num_devices=NUM_DEVICES)                    # Create TPIC6B595 full driver, (connection, num_devices=2)
                                                                              # two cascaded devices — 16 outputs total; outputs start OFF

# --- Walk a single lit LED across all 16 outputs and back ---
# Use write_all() each step so both cascaded devices latch together — there
# is no way to update just one downstream device without re-sending the whole
# chain's data.
position = 0
direction = 1
sweep_count = 0
BLANK_EVERY = 3         # blank every Nth sweep so the global-disable feature is exercised
BLANK_MS    = 500

while True:
    # Build the per-device byte: only one bit set, the LED currently lit
    bytes_ = [0x00] * NUM_DEVICES
    port = position // 8
    bit  = position % 8
    bytes_[port] = 1 << bit
    chip.write_all(bytes_)                                                  # Write all device bytes, (values=[0x01, 0x80]) → None

    print("position={:2d}  device={}  bit={}  bytes=[0x{:02X}, 0x{:02X}]".format(
        position, port, bit, bytes_[0], bytes_[1]))

    # --- Periodically blank every output via G, then resume ---
    # set_output_enable(False) drives G HIGH, forcing every DMOS off without
    # touching the shadow register — the LEDs simply resume exactly where
    # they left off when G is re-enabled.
    sweep_count += 1
    if sweep_count % BLANK_EVERY == 0:
        chip.set_output_enable(False)                                       # Force every output off via G, (enabled=False) → None
                                                                              # the chase pattern's shadow state is preserved
        print("  blanked via G for {} ms".format(BLANK_MS))
        time.sleep_ms(BLANK_MS)
        chip.set_output_enable(True)                                        # Re-enable outputs, (enabled=True) → None
                                                                              # LEDs resume from the previously-latched state

    # Bounce the chase position at both ends of the strip
    position += direction
    if position >= NUM_OUTPUTS - 1 or position <= 0:
        direction = -direction
        time.sleep_ms(100)
    else:
        time.sleep_ms(80)
