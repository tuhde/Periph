#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "ENS160.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x52;
    I2CConnectionLinux connection(bus, addr);

    ENS160Full ens(connection);                                             // Create ENS160 driver, (connection)

    uint8_t major, minor, release;
    ens.get_firmware_version(major, minor, release);                       // Read firmware version, (major, minor, release) → void
                                                                           // temporarily switches to IDLE to issue GET_APPVER
    printf("fw=%u.%u.%u\n", major, minor, release);

    ens.set_compensation(25.0f, 50.0f);                                    // Set T/H compensation, (temp_celsius °C, rh_percent %RH) → void
                                                                           // feed live values from a T/H sensor for best accuracy
    float comp_t, comp_rh;
    ens.read_compensation_actuals(comp_t, comp_rh);                        // Read back compensation in use, (temp_celsius °C, rh_percent %RH) → void
    printf("compensation %.2f C %.2f %%RH\n", (double)comp_t, (double)comp_rh);

    uint8_t st = ens.status();                                             // Read DEVICE_STATUS, () → uint8_t
                                                                           // validity flag in bits 3:2 (VALIDITY_*)
    uint8_t aqi;
    float tvoc, eco2;
    bool valid = ens.read_air_quality(aqi, tvoc, eco2);                    // Read AQI/TVOC/eCO2, (aqi 1–5, tvoc_ppb ppb, eco2_ppm ppm) → bool
                                                                           // true when the reading is valid
    printf("status=0x%02X valid=%d AQI=%u TVOC=%.0f ppb eCO2=%.0f ppm\n",
           st, valid, aqi, (double)tvoc, (double)eco2);

    float t2 = ens.read_tvoc();                                            // Read TVOC, () → float ppb
    float e2 = ens.read_eco2();                                            // Read eCO2, () → float ppm
    uint8_t a2 = ens.read_aqi();                                           // Read AQI, () → uint8_t 1–5 (UBA scale)
    float eth = ens.read_ethanol();                                        // Read ethanol equivalent, () → float ppb
    float r1 = ens.read_raw_resistance(1);                                 // Read raw hot-plate resistance, (sensor 1 or 4) → float Ω
                                                                           // diagnostic value, not calibrated
    printf("TVOC=%.0f eCO2=%.0f AQI=%u ethanol=%.0f R1=%.0f Ohm\n",
           (double)t2, (double)e2, a2, (double)eth, (double)r1);

    ens.configure_interrupt(true, false, false, true, false);              // Configure INTn, (enabled, active_high, push_pull, on_data, on_gpr) → void
                                                                           // active-low open-drain pulse on each new sample
    ens.sleep();                                                           // Enter DEEP_SLEEP, () → void
    usleep(10000);
    ens.wake();                                                            // Return to STANDARD mode, () → void
    return 0;
}
