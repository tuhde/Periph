#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "ENS160.h"

#ifndef ENS160_I2C_NODE
#define ENS160_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef ENS160_ADDR
#define ENS160_ADDR 0x52
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(ENS160_I2C_NODE);
    I2CTransportZephyr transport(dev, ENS160_ADDR);
    ENS160Full sensor(transport);                        // Create ENS160 driver, (transport)

    uint8_t major, minor, release;
    sensor.get_firmware_version(major, minor, release);  // Get firmware version, (major&, minor&, release&) → void
                                                          // switches to IDLE, issues GET_APPVER, returns to STANDARD
    printk("Firmware: %d.%d.%d\n", major, minor, release);

    sensor.set_compensation(25.0f, 50.0f);               // Set compensation, (temp_celsius, rh_percent) → void
                                                          // improves accuracy with external T/RH readings

    sensor.configure_interrupt(true, false, false, true, false);  // Configure interrupt, (enabled, active_high, push_pull, on_data, on_gpr) → void
                                                          // sets INTn pin behavior for new data notification

    printk("Waiting for warm-up...\n");
    while (sensor.status() != 0) {                       // Poll validity, () → uint8_t 0–3
        k_sleep(K_SECONDS(1));
    }

    float tvoc = sensor.read_tvoc();                     // Read TVOC, () → float ppb
    float eco2 = sensor.read_eco2();                     // Read eCO2, () → float ppm
    uint8_t aqi = sensor.read_aqi();                     // Read AQI, () → uint8_t 1–5
    float ethanol = sensor.read_ethanol();               // Read ethanol, () → float ppb
                                                          // alias of DATA_TVOC at 0x22
    float r1 = sensor.read_raw_resistance(1);            // Read raw resistance, (sensor=1 or 4) → float Ohms
    float r4 = sensor.read_raw_resistance(4);            // Read raw resistance, (sensor=1 or 4) → float Ohms
    float temp_actual, rh_actual;
    sensor.read_compensation_actuals(temp_actual, rh_actual);  // Read compensation actuals, (temp_celsius&, rh_percent&) → void
                                                          // returns T/RH values used by sensor

    printk("TVOC=%.0f ppb, eCO2=%.0f ppm, AQI=%d\n", tvoc, eco2, aqi);
    printk("Ethanol=%.0f ppb, R1=%.0f Ohm, R4=%.0f Ohm\n", ethanol, r1, r4);
    printk("Actual T=%.1f C, RH=%.1f %%\n", temp_actual, rh_actual);

    sensor.sleep();                                      // Enter deep sleep, () → void
                                                          // reduces current to ~10 uA
    k_sleep(K_SECONDS(1));
    sensor.wake();                                       // Wake and resume sensing, () → void
                                                          // transitions IDLE then STANDARD

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
