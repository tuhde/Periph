#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "TMP102.h"

static volatile float alertTemperature = 0.0f;
static volatile bool pending = false;

static void onAlert(float temperatureC) {
    alertTemperature = temperatureC;
    pending = true;
}

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = TMP102Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    TMP102Full sensor(connection);                          // Create TMP102 Full driver, (connection)

    printf("config 0x%04X\n", sensor.readConfig());         // Read CONFIGURATION, () → uint16_t
    printf("limits low %.4f high %.4f\n",
           (double)sensor.getTLow(), (double)sensor.getTHigh());  // Read T_LOW, () → °C; Read T_HIGH, () → °C

    // --- Continuous reading at 4 Hz for 5 s ---
    for (int i = 0; i < 20; i++) {
        printf("%.4f C (raw %d)\n", (double)sensor.readTemperature(), (int)sensor.readRaw());  // Read temperature, () → °C; Read raw count, () → int16_t
        vTaskDelay(pdMS_TO_TICKS(250));
    }

    // --- 8 Hz and the 13-bit extended mode ---
    sensor.setConversionRate(8.0f);                         // Set conversion rate, (hz) → bool
    sensor.setExtendedMode(true);                           // Set extended mode, (on) → void
    vTaskDelay(pdMS_TO_TICKS(200));
    printf("extended %.4f C\n", (double)sensor.readTemperature());  // Read temperature, () → °C
    sensor.setExtendedMode(false);                          // Set extended mode, (on) → void
    sensor.setConversionRate(4.0f);                         // Set conversion rate, (hz) → bool

    // --- Shutdown and one-shot readings (about 10 ms each) ---
    sensor.shutdown();                                      // Shut down, () → void
    for (int i = 0; i < 5; i++) {
        float oneShot = 0.0f;
        sensor.oneShot(oneShot);                            // One-shot reading, (celsius&) → bool
        printf("one-shot %.4f C\n", (double)oneShot);
    }
    sensor.wake();                                          // Wake, () → void

    // --- Thermostat around the current temperature ---
    float t = sensor.readTemperature();                     // Read temperature, () → °C
    sensor.setTHigh(t + 1.0f);                              // Set T_HIGH, (celsius °C) → void
    sensor.setTLow(t - 1.0f);                               // Set T_LOW, (celsius °C) → void
    sensor.setFaultQueue(2);                                // Set fault queue, (count) → bool
    sensor.setPolarity(false);                              // Set ALERT polarity, (activeHigh) → void
    sensor.setThermostatMode(true);                         // Set thermostat mode, (interrupt) → void
    printf("warm the sensor with a finger ...\n");
    for (int i = 0; i < 40; i++) {
        if (sensor.pollInterrupt()) {                       // Poll the alert state, () → bool
            printf("ALERT: %.2f C, alert_state=%d\n", (double)sensor.readTemperature(), sensor.alertState());  // Read alert state, () → bool
        }
        vTaskDelay(pdMS_TO_TICKS(250));
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
}
