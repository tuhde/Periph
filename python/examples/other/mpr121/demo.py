"""Demo for the MPR121 — 12-button musical keyboard with IRQ-driven detection.

Maps ELE0-ELE11 to 12 musical notes spanning one octave (C4 through B4).
On each scan, compares the current touched bitmask with the previous
one to fire NOTE ON/NOTE OFF events. Interrupt-driven detection via
the chip's active-low open-drain INT pin — no polling — so the
sample loop wakes only when a touch or release actually happens.
"""

from periph.chips.other.mpr121 import Mpr121Full
from periph.connection.i2c_auto import I2CConnection

# --- Setup: default thresholds + autoconfig for 3.3 V + IRQ delivery ---
# Defaults (T=12, R=6 for all 12 electrodes, 16 µA global CDC, 16 ms
# sample interval) are a good fit for a finger-sized touch surface.
# The 12-bit touched bitmask is mapped to one musical note per
# electrode; the scan loop compares it to the previous scan to
# detect onset and release edges.
NOTES = ['C4', 'C#4', 'D4', 'D#4', 'E4', 'F4', 'F#4', 'G4', 'G#4', 'A4', 'A#4', 'B4']

connection = I2CConnection(0x5A)                                         # Create I2C connection, (addr=0x5A, bus=None) → I2CConnection
mpr = Mpr121Full(connection)                                             # Construct MPR121 Full, (connection) → Mpr121Full
                                                                     # defaults, all 12 electrodes enabled

# --- IRQ handling: edge-triggered on the active-low open-drain INT pin ---
# Polling would burn CPU and miss sub-cycle touch/release edges. The
# chip drives INT low on any change; we read the status registers
# inside the callback (which clears the line).
def on_change(mask):                                                     # Define callback, (mask) → None
                                                                     # diff previous vs current bitmask; print NOTE ON / NOTE OFF
    global _previous
    newly_pressed = mask & ~_previous
    newly_released = (~mask) & _previous
    for n in range(12):
        if newly_pressed & (1 << n):
            print('NOTE ON:  {}'.format(NOTES[n]))
        if newly_released & (1 << n):
            print('NOTE OFF: {}'.format(NOTES[n]))
    _previous = mask

_previous = 0
mpr.on_interrupt(on_change)                                              # Subscribe to INT, (callback) → None
                                                                     # delivers bitmask via edge-triggered INT line

# --- Note mapping: ELE index -> note name ---
# Bit 0 = ELE0 -> C4, bit 1 = ELE1 -> C#4, ... bit 11 = ELE11 -> B4.
# Twelve pads span exactly one octave.

# --- Block on user input; the scan loop is driven entirely by IRQs ---
try:
    input()
except KeyboardInterrupt:
    pass
finally:
    mpr.off_interrupt()                                                  # Unsubscribe, () → None
                                                                     # removes the on_edge handler from the int pin
    connection.close()
