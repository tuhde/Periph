#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "TMP102.h"

#define I2C_NODE DT_NODELABEL(i2c0)

static volatile float alertTemperature = 0.0f;
static volatile bool pending = false;

static void onAlert(float temperatureC) {
    alertTemperature = temperatureC;
    pending = true;
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, TMP102Minimal::I2C_ADDRESS);
    TMP102Full sensor(connection);                          // Create TMP102 Full driver, (connection)

    printk("config 0x%04X\n", sensor.readConfig());         // Read CONFIGURATION, () → uint16_t
    printk("limits low %.4f high %.4f\n",
           (double)sensor.getTLow(), (double)sensor.getTHigh());  // Read T_LOW, () → °C; Read T_HIGH, () → °C

    // --- Continuous reading at 4 Hz for 5 s ---
    for (int i = 0; i < 20; i++) {
        printk("%.4f C (raw %d)\n", (double)sensor.readTemperature(), (int)sensor.readRaw());  // Read temperature, () → °C; Read raw count, () → int16_t
        k_msleep(250);
    }

    // --- 8 Hz and the 13-bit extended mode ---
    sensor.setConversionRate(8.0f);                         // Set conversion rate, (hz) → bool
    sensor.setExtendedMode(true);                           // Set extended mode, (on) → void
    k_msleep(200);
    printk("extended %.4f C\n", (double)sensor.readTemperature());  // Read temperature, () → °C
    sensor.setExtendedMode(false);                          // Set extended mode, (on) → void
    sensor.setConversionRate(4.0f);                         // Set conversion rate, (hz) → bool

    // --- Shutdown and one-shot readings (about 10 ms each) ---
    sensor.shutdown();                                      // Shut down, () → void
    for (int i = 0; i < 5; i++) {
        float oneShot = 0.0f;
        sensor.oneShot(oneShot);                            // One-shot reading, (celsius&) → bool
        printk("one-shot %.4f C\n", (double)oneShot);
    }
    sensor.wake();                                          // Wake, () → void

    // --- Thermostat around the current temperature ---
    float t = sensor.readTemperature();                     // Read temperature, () → °C
    sensor.setTHigh(t + 1.0f);                              // Set T_HIGH, (celsius °C) → void
    sensor.setTLow(t - 1.0f);                               // Set T_LOW, (celsius °C) → void
    sensor.setFaultQueue(2);                                // Set fault queue, (count) → bool
    sensor.setPolarity(false);                              // Set ALERT polarity, (activeHigh) → void
    sensor.setThermostatMode(true);                         // Set thermostat mode, (interrupt) → void
    printk("warm the sensor with a finger ...\n");
    for (int i = 0; i < 40; i++) {
        if (sensor.pollInterrupt()) {                       // Poll the alert state, () → bool
            printk("ALERT: %.2f C, alert_state=%d\n", (double)sensor.readTemperature(), sensor.alertState());  // Read alert state, () → bool
        }
        k_msleep(250);
    }
    sensor.onInterrupt(onAlert);                            // Subscribe to ALERT, (callback, intPin=nullptr) → void
    sensor.offInterrupt();                                  // Unsubscribe, () → void

    // --- Restore the power-on defaults ---
    sensor.setThermostatMode(false);                        // Set thermostat mode, (interrupt) → void
    sensor.setFaultQueue(1);                                // Set fault queue, (count) → bool
    sensor.setTHigh(80.0f);                                 // Set T_HIGH, (celsius °C) → void
    sensor.setTLow(75.0f);                                  // Set T_LOW, (celsius °C) → void
    sensor.writeConfig(0x60A0);                             // Write CONFIGURATION, (value) → void
    sensor.reset();                                         // General-call reset, () → void
    return 0;
}
