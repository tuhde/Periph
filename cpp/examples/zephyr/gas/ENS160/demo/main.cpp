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

const char* aqi_label(uint8_t aqi) {
    switch (aqi) {
        case 1: return "Excellent";
        case 2: return "Good";
        case 3: return "Moderate";
        case 4: return "Poor";
        case 5: return "Unhealthy";
        default: return "Unknown";
    }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(ENS160_I2C_NODE);
    I2CTransportZephyr transport(dev, ENS160_ADDR);
    ENS160Full sensor(transport);                        // Create ENS160 driver, (transport)

    // --- Wait for sensor warm-up ---
    // The ENS160 requires ~3 minutes after power-on or idle before VALIDITY_FLAG
    // reaches 0. During warm-up, readings are unreliable.
    printk("Waiting for sensor warm-up...\n");
    {
        uint8_t _aqi; float _tvoc, _eco2;
        while (!sensor.read_air_quality(_aqi, _tvoc, _eco2)) {  // Wait for valid data, () → blocks until warm
            uint8_t s = sensor.status();
            if (s == 1) printk("Warm-up in progress...\n");
            else if (s == 2) printk("Initial start-up (first power-on, up to 1 hour)...\n");
            else printk("No valid output\n");
            k_sleep(K_SECONDS(1));
        }
    }
    printk("Sensor ready!\n");

    // --- Set compensation from external sensor ---
    sensor.set_compensation(22.0f, 45.0f);               // Set compensation, (temp_celsius=22.0, rh_percent=45.0) → void

    // --- Indoor air quality monitoring loop ---
    for (int n = 0; n < 60; n++) {
        uint8_t aqi;
        float tvoc_ppb, eco2_ppm;
        bool ok = sensor.read_air_quality(aqi, tvoc_ppb, eco2_ppm);  // Read air quality, () → bool
        if (ok) {
            printk("%ds: AQI=%d (%s) TVOC=%.0f ppb eCO2=%.0f ppm\n",
                n, aqi, aqi_label(aqi), tvoc_ppb, eco2_ppm);
        }
        k_sleep(K_SECONDS(1));
    }

    return 0;
}
