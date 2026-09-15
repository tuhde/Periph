"""Complete example for the MPR121 — exercises every Full-class method."""

from periph.chips.other.mpr121 import Mpr121Full
from periph.connection.i2c_auto import I2CConnection

connection = I2CConnection(0x5A)                                         # Create I2C connection, (addr=0x5A, bus=None) → I2CConnection
mpr = Mpr121Full(connection)                                             # Construct MPR121 Full, (connection) → Mpr121Full
                                                                     # runs Minimal init then exposes Full configuration methods

# --- Stop, configure, re-enter Run Mode (recommended pattern) ---
mpr.stop()                                                               # Enter Stop Mode, () → None
                                                                     # required before writing most config registers
mpr.configure_thresholds(0, touch=15, release=8)                         # Set thresholds, (electrode=0, touch=15, release=8) → None
                                                                     # ELE0: touch at 15 LSBs below baseline, release at 8 LSBs
mpr.configure_all_thresholds(touch=12, release=6)                        # Apply thresholds to all, (touch=12, release=6) → None
                                                                     # ELE1..ELE11: same touch/release values
mpr.configure_proximity_thresholds(touch=8, release=4)                   # Set ELEPROX thresholds, (touch=8, release=4) → None
                                                                     # ELEPROX touch at 8 LSBs, release at 4
mpr.configure_baseline_filter(mhdr=1, nhdr=1, nclr=0, fdlr=0,             # Set baseline filter, (mhdr, nhdr, nclr, fdlr, mhdf, nhdf, nclf, fdlf, nhdt, nclt, fdlt) → None
                              mhdf=1, nhdf=1, nclf=0, fdlf=0,
                              nhdt=1, nclt=0, fdlt=0)
                                                                     # baseline filter defaults; tracks capacitance drift only
mpr.configure_sampling(cdc=16, cdt=1, ffi=0, sfi=0, esi=4)               # Set AFE config, (cdc, cdt, ffi, sfi, esi) → None
                                                                     # 16 µA global CDC, 0.5 µs charge time, 16 ms sample interval
mpr.configure_debounce(touch=1, release=1)                               # Set debounce, (touch=1, release=1) → None
                                                                     # one consecutive measurement to detect touch/release
mpr.configure_autoconfig(vdd_mv=3300, retry=0, scts=False,               # Configure autoconfig, (vdd_mv=3300, retry=0, scts=False, are=True, ace=True) → None
                         are=True, ace=True)
                                                                     # recompute USL/TL/LSL for 3.3 V supply
mpr.start(n_electrodes=12, cl=2, eleprox_en=0)                           # Enter Run Mode, (n_electrodes=12, cl=2, eleprox_en=0) → None
                                                                     # all 12 electrodes, baseline init from first measurement, proximity off

# --- Subscribe to interrupt (delivery via connection.int_pin if wired) ---
def on_touch(mask):                                                      # Define callback, (mask) → None
    print('INT: 0x{:03X}'.format(mask))
mpr.on_interrupt(on_touch)                                               # Subscribe to INT, (callback) → None
                                                                     # callback fires on any touch/release via edge-triggered INT line

# --- Read raw filtered capacitance, baseline, OOR, proximity touched ---
f0 = mpr.filtered(0)                                                     # Read ELE0 filtered, (electrode=0) → int 0..1023
                                                                     # raw 10-bit value, inversely proportional to capacitance
b0 = mpr.baseline(0)                                                     # Read ELE0 baseline, (electrode=0) → int 0..1023
                                                                     # 8 MSBs from baseline register, shifted left 2
oor = mpr.oor_status()                                                   # Read OOR bitmask, () → int bitmask
                                                                     # bits 0..11 = ELE0..11 out-of-range
pt = mpr.proximity_touched()                                             # Read proximity touched, () → bool
                                                                     # False: ELEPROX not enabled in this example
print('ELE0 filtered={} baseline={} OOR=0x{:03X} prox_touched={}'.format(
    f0, b0, oor, pt))

# --- Enable OOR interrupt, then disable it ---
mpr.enable_interrupt(Mpr121Full.SOURCE_OOR)                               # Enable interrupt source, (source=SOURCE_OOR) → None
                                                                     # OOR will now assert INT as well as touch/release
mpr.disable_interrupt(Mpr121Full.SOURCE_OOR)                             # Disable interrupt source, (source=SOURCE_OOR) → None
                                                                     # OOR no longer asserts INT

# --- Clear overcurrent if it has fired (read-modify-write OVCF) ---
mpr.clear_overcurrent()                                                  # Clear OVCF, () → None
                                                                     # safe no-op when overcurrent has not occurred

# --- Unwire interrupt subscription and reset the chip ---
mpr.off_interrupt()                                                      # Unsubscribe, () → None
                                                                     # removes the on_edge handler from the int pin
mpr.reset()                                                              # Soft reset, () → None
                                                                     # reapplies Minimal defaults; device ready for fresh use

connection.close()
