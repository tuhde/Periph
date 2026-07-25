#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CTransportZephyr.h"
#include "AHT21.h"

#define I2C_NODE   DT_NODELABEL(i2c0)
#define AHT21_ADDR 0x38

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, AHT21_ADDR);
    AHT21Full aht(transport);                                          // Create AHT21 driver, (transport, addr=0x38) → void

    // --- Verify calibration before starting the logging session ---
    // Most AHT21 modules ship pre-calibrated; if the CAL bit is not set
    // the driver already sent the calibration init sequence during construction.
    printk("Calibrated: %d\n", aht.is_calibrated());                   // Check calibration status, () → bool

    printk("Time     T (C)      RH (%%)     Dew (C)\n");
    for (int n = 0; n < 60; n++) {
        // --- Each reading requires an 80 ms measurement cycle ---
        // The sensor cannot output data faster than this; the driver
        // handles the trigger + wait internally.
        float t, h;
        bool crc_ok = aht.read_with_crc(t, h);                         // Read with CRC verification, (temperature_c, humidity_pct) → bool
        if (!crc_ok) {
            printk("CRC error at sample %d\n", n);
            k_sleep(K_SECONDS(5));
            continue;
        }

        // --- Magnus formula dew-point approximation ---
        // gamma = ln(RH/100) + (17.625 * T) / (243.04 + T)
        // dew_point = (243.04 * gamma) / (17.625 - gamma)
        // Accurate to ±0.5 °C for 0 < T < 60 °C and 1 < RH < 100 %RH.
        float gamma = logf(h / 100.0f) + (17.625f * t) / (243.04f + t);
        float dew   = (243.04f * gamma) / (17.625f - gamma);

        printk("%-8d %-10.2f %-10.2f %-10.2f\n", n, (double)t, (double)h, (double)dew);
        k_sleep(K_SECONDS(5));
    }
    return 0;
}
