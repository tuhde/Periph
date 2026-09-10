#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Apds9930.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x39;
    I2CConnectionLinux connection(bus, addr);                                 // Create I2C connection, (bus, addr) → I2CConnectionLinux

    APDS9930Full apds(connection);                                              // Create APDS-9930 Full, (connection) → APDS9930Full
                                                                               // exposes ALS and proximity configuration methods

    usleep(110 * 1000);

    apds.configure_als(0xDB, 0, false);                                        // Configure ALS, (atime=0xDB, again=0, agl=false) → void
                                                                               // sets ALS integration time to 101 ms with 1x gain
    apds.configure_proximity(8, 0, 0, false, 0xFF);                           // Configure proximity, (ppulse=8, pgain=0, pdrive=0, pdl=false, ptime=0xFF) → void
                                                                               // 8 LED pulses at 100 mA, 1x gain, no reduced drive
    apds.disable_wait();                                                       // Disable wait timer, () → void
                                                                               // clears WEN in ENABLE
    apds.set_als_thresholds(100, 60000, 1);                                    // Set ALS thresholds, (low=100, high=60000, persistence=1) → void
                                                                               // fires an interrupt after 1 consecutive out-of-range Ch0 count
    apds.set_proximity_thresholds(10, 200, 1);                                 // Set proximity thresholds, (low=10, high=200, persistence=1) → void
                                                                               // fires on a single proximity reading outside [10, 200]
    apds.set_proximity_offset(0);                                             // Set proximity offset, (offset=0) → void
                                                                               // clears any prior offset
    apds.sleep_after_interrupt(false);                                        // Configure SAI, (enable=false) → void
                                                                               // chip stays in normal operation after an interrupt

    for (int i = 0; i < 10; i++) {
        usleep(110 * 1000);
        float lx = apds.lux();                                                  // Read ambient illuminance, () → float lx
                                                                               // combines Ch0 and Ch1 with IR-compensation coefficients
        uint16_t p = apds.proximity();                                          // Read proximity count, () → uint16_t count
                                                                               // 16-bit ADC value
        uint16_t c0 = apds.ch0();                                               // Read Ch0 raw, () → uint16_t count
                                                                               // 16-bit ADC value of visible + IR channel
        uint16_t c1 = apds.ch1();                                               // Read Ch1 raw, () → uint16_t count
                                                                               // 16-bit ADC value of IR-only channel
        bool avalid, pvalid, psat, aint, pint;
        apds.status(avalid, pvalid, psat, aint, pint);                          // Read STATUS decoded, (avalid, pvalid, psat, aint, pint) → void
                                                                               // populates five booleans from STATUS register
        printf("lux=%.1f lx  prox=%u  ch0=%u  ch1=%u  AVALID=%d  PVALID=%d\n",
               lx, p, c0, c1, avalid, pvalid);
    }
    apds.clear_interrupt(0);                                                    // Clear interrupts, (channel=0) → void
                                                                               // 0=both, 1=ALS, 2=proximity — issues special-function command

    return 0;
}