from periph.connection.sipo_micropython import SiPoConnection
from periph.chips.io_expander.tpic6b595 import Tpic6b595Minimal, Tpic6b595Full
from machine import SPI, Pin
import time

spi = SPI(0, baudrate=1_000_000, polarity=0, phase=0)                     # Create SPI bus, (id=0, baud=1 MHz, mode=0)
rck    = Pin(17, Pin.OUT)                                                   # Configure RCK GPIO, (pin=17, mode=OUT)
srclr  = Pin(16, Pin.OUT)                                                   # Configure SRCLR GPIO, (pin=16, mode=OUT)
g      = Pin(15, Pin.OUT)                                                   # Configure G GPIO, (pin=15, mode=OUT)
connection = SiPoConnection(spi, rck, srclr=srclr, g=g)                  # Create SiPo connection, (spi, rck, srclr, g)

chip = Tpic6b595Full(connection, num_devices=2)                             # Create TPIC6B595 full driver, (connection, num_devices=2)
                                                                            # two cascaded devices — 16 outputs total (DRAIN0..DRAIN15)

# --- Pin-level control ---
p0  = chip.pin(0)                                                          # Get pin proxy for DRAIN0 of device 0, (n=0) → Pin
                                                                            # returns proxy bound to pin 0; writes are deferred until flush
p0.on()                                                                     # Set DRAIN0 ON, () → None
                                                                            # sets shadow[0] bit 0, reverses the cascade, shifts out and pulses RCK
p0.off()                                                                    # Set DRAIN0 OFF, () → None
                                                                            # clears shadow[0] bit 0, retransmits and latches
p0.toggle()                                                                 # Invert shadow bit, () → None
                                                                            # flips the bit in the shadow register and retransmits the cascade

state = p0.value()                                                          # Read pin state, () → int
                                                                            # returns the shadow bit (no bus read — SiPo is write-only)
p0.value(1)                                                                 # Write pin high, (x=1) → None
                                                                            # equivalent to on(); updates shadow, retransmits, latches
p0.set(True)                                                                # OutputPin set, (high=True) → None
                                                                            # same path as on()/value(1), but matches the OutputPin contract

# --- Port-level bulk write ---
chip.write_port(0, 0xAA)                                                   # Write device 0 outputs, (port=0, mask=0xAA) → None
                                                                            # sets DRAIN{1,3,5,7} ON, DRAIN{0,2,4,6} OFF; preserves device 1
chip.write_port(1, 0x55)                                                   # Write device 1 outputs, (port=1, mask=0x55) → None

# --- Bulk fill / off ---
chip.fill(True)                                                             # Set every output ON, (value=True) → None
                                                                            # fills every shadow byte with 0xFF and retransmits — fast "all on" path
chip.fill(False)                                                            # Set every output OFF, (value=False) → None
                                                                            # fills every shadow byte with 0x00 and retransmits — fast "all off" path
chip.off()                                                                  # Turn every output off, () → None
                                                                            # shorthand for fill(False); the safe initial state

# --- Multi-device bulk write ---
chip.write_all([0x01, 0x80])                                                # Write all device bytes, (values=[device0, device1]) → None
                                                                            # updates both shadow bytes and performs one transmit + latch

# --- Hardware features (Full only) ---
chip.clear()                                                                # Pulse SRCLR, () → None
                                                                            # clears the shift register only; outputs unaffected until next RCK pulse
chip.set_output_enable(False)                                              # Force every output off via G, (enabled=False) → None
                                                                            # drives G HIGH, blanking outputs without disturbing the shadow register
time.sleep(0.1)
chip.set_output_enable(True)                                               # Re-enable outputs, (enabled=True) → None
                                                                            # drives G LOW; outputs resume from the previously-latched state
