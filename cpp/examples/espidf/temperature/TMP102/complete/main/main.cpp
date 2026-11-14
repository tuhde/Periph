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

    float t = sensor.readTemperature();                     // Read temperature, () → °C
                                                            // decodes TEMPERATURE, 0.0625 °C two's complement
    printf("temperature %.4f C\n", (double)t);
    printf("raw %d\n", (int)sensor.readRaw());              // Read raw count, () → int16_t
                                                            // signed 12/13-bit count, 0.0625 °C per LSB

    printf("config 0x%04X\n", sensor.readConfig());         // Read CONFIGURATION, () → uint16_t
                                                            // reset value 0x60A0
    sensor.writeConfig(sensor.readConfig());                // Write CONFIGURATION, (value) → void
                                                            // read-only bits are masked out

    sensor.setConversionRate(8.0f);                         // Set conversion rate, (hz) → bool
                                                            // 0.25, 1, 4 or 8 Hz
    sensor.setExtendedMode(true);                           // Set extended mode, (on) → void
                                                            // EM bit, 13-bit readings up to +255.9375 °C
    vTaskDelay(pdMS_TO_TICKS(200));
    printf("extended %.4f C\n", (double)sensor.readTemperature());  // Read temperature, () → °C
    sensor.setExtendedMode(false);                          // Set extended mode, (on) → void
                                                            // back to 12 bit

    sensor.setTHigh(30.0f);                                 // Set T_HIGH, (celsius °C) → void
                                                            // rounded to 0.0625 °C
    sensor.setTLow(10.0f);                                  // Set T_LOW, (celsius °C) → void
                                                            // rounded to 0.0625 °C
    printf("high %.4f C\n", (double)sensor.getTHigh());     // Read T_HIGH, () → °C
                                                            // same format as TEMPERATURE
    printf("low %.4f C\n", (double)sensor.getTLow());       // Read T_LOW, () → °C
                                                            // same format as TEMPERATURE

    sensor.setFaultQueue(2);                                // Set fault queue, (count) → bool
                                                            // 1, 2, 4 or 6 consecutive faults
    sensor.setPolarity(false);                              // Set ALERT polarity, (activeHigh) → void
                                                            // POL bit, false = active low
    sensor.setThermostatMode(false);                        // Set thermostat mode, (interrupt) → void
                                                            // TM bit, false = comparator
    printf("alert %d\n", sensor.alertState());              // Read alert state, () → bool
                                                            // AL bit corrected for POL

    sensor.shutdown();                                      // Shut down, () → void
                                                            // SD bit, stops after the current conversion
    float oneShot = 0.0f;
    sensor.oneShot(oneShot);                                // One-shot reading, (celsius&) → bool
                                                            // OS=1, polls until done, restores the mode
    printf("one-shot %.4f C\n", (double)oneShot);
    sensor.wake();                                          // Wake, () → void
                                                            // resume continuous conversion

    printf("polling %d\n", sensor.pollInterrupt());         // Poll the alert state, () → bool
                                                            // reads CONFIGURATION and TEMPERATURE
    sensor.onInterrupt(onAlert);                            // Subscribe to ALERT, (callback, intPin=nullptr) → void
                                                            // callback receives the temperature in °C
    vTaskDelay(pdMS_TO_TICKS(3000));
    if (pending) printf("alert at %.2f C\n", (double)alertTemperature);
    sensor.offInterrupt();                                  // Unsubscribe, () → void
                                                            // detaches the pin handler

    sensor.setConversionRate(4.0f);                         // Set conversion rate, (hz) → bool
                                                            // back to the power-on default
    sensor.setTHigh(80.0f);                                 // Set T_HIGH, (celsius °C) → void
    sensor.setTLow(75.0f);                                  // Set T_LOW, (celsius °C) → void
    sensor.reset();                                         // General-call reset, () → void
                                                            // resets every general-call device on the bus
}
