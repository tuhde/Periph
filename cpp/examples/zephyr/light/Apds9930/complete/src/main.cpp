// Complete Zephyr example for the APDS-9930 — exercises every Full-class method.

#include <stdio.h>
#include <zephyr/kernel.h>
#include "Apds9930.h"
#include "I2CConnectionZephyr.h"

int main() {
    I2CConnectionZephyr connection(0x39);                                       // Create I2C connection, (addr=0x39) → I2CConnectionZephyr
    APDS9930Full apds(connection);                                              // Create APDS-9930 Full, (connection) → APDS9930Full

    k_msleep(110);
    apds.configure_als(0xDB, 0, false);                                        // Configure ALS, (atime=0xDB, again=0, agl=false) → void
    apds.configure_proximity(8, 0, 0, false, 0xFF);                           // Configure proximity, (ppulse=8, pgain=0, pdrive=0, pdl=false, ptime=0xFF) → void
    apds.disable_wait();                                                       // Disable wait timer, () → void
    apds.set_als_thresholds(100, 60000, 1);                                    // Set ALS thresholds, (low=100, high=60000, persistence=1) → void
    apds.set_proximity_thresholds(10, 200, 1);                                 // Set proximity thresholds, (low=10, high=200, persistence=1) → void
    apds.set_proximity_offset(0);                                             // Set proximity offset, (offset=0) → void
    apds.sleep_after_interrupt(false);                                        // Configure SAI, (enable=false) → void

    for (int i = 0; i < 10; i++) {
        k_msleep(110);
        float lx = apds.lux();                                                  // Read ambient illuminance, () → float lx
        uint16_t p = apds.proximity();                                          // Read proximity count, () → uint16_t count
        uint16_t c0 = apds.ch0();                                               // Read Ch0 raw, () → uint16_t count
        uint16_t c1 = apds.ch1();                                               // Read Ch1 raw, () → uint16_t count
        bool avalid, pvalid, psat, aint, pint;
        apds.status(avalid, pvalid, psat, aint, pint);                          // Read STATUS decoded, (avalid, pvalid, psat, aint, pint) → void
        printf("lux=%.1f lx  prox=%u  ch0=%u  ch1=%u  AVALID=%d  PVALID=%d\n",
               lx, p, c0, c1, avalid, pvalid);
    }
    apds.clear_interrupt(0);                                                    // Clear interrupts, (channel=0) → void

    return 0;
}