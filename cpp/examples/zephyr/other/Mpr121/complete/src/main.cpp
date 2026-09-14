// Complete Zephyr example for the MPR121 — exercises every Full-class method.

#include <stdio.h>
#include <zephyr/kernel.h>
#include "Mpr121.h"
#include "I2CConnectionZephyr.h"

int main() {
    I2CConnectionZephyr connection(0x5A);                                       // Create I2C connection, (addr=0x5A) → I2CConnectionZephyr
    MPR121Full mpr(connection);                                                 // Create MPR121 Full, (connection) → MPR121Full

    mpr.stop();                                                                 // Enter Stop Mode, () → void
    mpr.configure_thresholds(0, 15, 8);                                         // Set thresholds, (electrode=0, touch=15, release=8) → void
    mpr.configure_all_thresholds(12, 6);                                        // Apply thresholds to all, (touch=12, release=6) → void
    mpr.configure_proximity_thresholds(8, 4);                                   // Set ELEPROX thresholds, (touch=8, release=4) → void
    mpr.configure_baseline_filter(1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0);             // Set baseline filter, (mhdr, nhdr, nclr, fdlr, mhdf, nhdf, nclf, fdlf, nhdt, nclt, fdlt) → void
    mpr.configure_sampling(16, 1, 0, 0, 4);                                     // Set AFE config, (cdc=16, cdt=1, ffi=0, sfi=0, esi=4) → void
    mpr.configure_debounce(1, 1);                                               // Set debounce, (touch=1, release=1) → void
    mpr.configure_autoconfig(3300, 0, false, true, true);                       // Configure autoconfig, (vdd_mv=3300, retry=0, scts=false, are=true, ace=true) → void
    mpr.start(12, 2, 0);                                                        // Enter Run Mode, (n_electrodes=12, cl=2, eleprox_en=0) → void

    for (int i = 0; i < 10; i++) {
        k_msleep(200);
        uint16_t t = mpr.touched();                                             // Read 12-bit touch bitmask, () → uint16_t bitmask
        uint16_t f0 = mpr.filtered(0);                                          // Read ELE0 filtered, (electrode=0) → uint16_t 0..1023
        uint16_t b0 = mpr.baseline(0);                                          // Read ELE0 baseline, (electrode=0) → uint16_t 0..1023
        uint16_t oor = mpr.oor_status();                                        // Read OOR bitmask, () → uint16_t bitmask
        bool pt = mpr.proximity_touched();                                      // Read proximity touched, () → bool
        printf("t=0x%03X f0=%u b0=%u oor=0x%03X pt=%d\n", t, f0, b0, oor, pt);
    }
    mpr.enable_interrupt(MPR121Full::SOURCE_OOR);                               // Enable interrupt source, (source=SOURCE_OOR) → void
    mpr.disable_interrupt(MPR121Full::SOURCE_OOR);                              // Disable interrupt source, (source=SOURCE_OOR) → void
    mpr.clear_overcurrent();                                                    // Clear OVCF, () → void
    mpr.reset();                                                                // Soft reset, () → void
    return 0;
}
