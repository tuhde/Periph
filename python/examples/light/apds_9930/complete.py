"""Complete example for the APDS-9930 — exercises every Full-class method."""

import time

from periph.chips.light.apds_9930 import APDS9930Full
from periph.connection.i2c_auto import I2CConnection

connection = I2CConnection(0x39)                                       # Create I2C connection, (addr=0x39, bus=None) → I2CConnection
apds = APDS9930Full(connection)                                        # Construct APDS-9930 Full, (connection) → APDS9930Full
                                                                     # runs Minimal init then exposes Full configuration methods

time.sleep(0.110)

apds.configure_als(atime=0xDB, again=0, agl=False)                    # Configure ALS, (atime=0xDB, again=0, agl=False) → None
                                                                     # sets ALS integration time to 101 ms with 1x gain
apds.configure_proximity(ppulse=8, pgain=0, pdrive=0, pdl=False, ptime=0xFF)  # Configure proximity, (ppulse=8, pgain=0, pdrive=0, pdl=False, ptime=0xFF) → None
                                                                     # 8 LED pulses at 100 mA, 1x gain, no reduced drive
apds.disable_wait()                                                   # Disable wait timer, () → None
                                                                     # clears WEN in ENABLE
apds.set_als_thresholds(100, 60000, persistence=1)                     # Set ALS thresholds, (low=100, high=60000, persistence=1) → None
                                                                     # fires an interrupt after 1 consecutive out-of-range Ch0 count
apds.set_proximity_thresholds(10, 200, persistence=1)                  # Set proximity thresholds, (low=10, high=200, persistence=1) → None
                                                                     # fires on a single proximity reading outside [10, 200]
apds.set_proximity_offset(0)                                          # Set proximity offset, (offset=0) → None
                                                                     # clears any prior offset
apds.sleep_after_interrupt(False)                                     # Configure SAI, (enable=False) → None
                                                                     # chip stays in normal operation after an interrupt

for _ in range(10):
    time.sleep(0.110)
    lx = apds.lux()                                                   # Read ambient illuminance, () → float lx
                                                                     # combines Ch0 and Ch1 with IR-compensation coefficients
    p = apds.proximity()                                              # Read proximity count, () → int count
                                                                     # 16-bit ADC value
    c0 = apds.ch0()                                                   # Read Ch0 raw, () → int count
                                                                     # 16-bit ADC value of visible + IR channel
    c1 = apds.ch1()                                                   # Read Ch1 raw, () → int count
                                                                     # 16-bit ADC value of IR-only channel
    st = apds.status()                                                # Read STATUS decoded, () → dict
                                                                     # {avalid, pvalid, psat, aint, pint} booleans
    print('lux={:.1f}  prox={}  ch0={}  ch1={}  status={}'.format(
        lx, p, c0, c1, st))

apds.clear_interrupt('both')                                          # Clear interrupts, (channel='both') → None
                                                                     # issues special-function command 0xE7 to clear both ALS and proximity INT flags

connection.close()