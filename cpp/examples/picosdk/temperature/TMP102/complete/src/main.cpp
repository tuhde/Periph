#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "TMP102.h"

static volatile float alertTemperature = 0.0f;
static volatile bool pending = false;

static void onAlert(float temperatureC) {
    alertTemperature = temperatureC;
    pending = true;
}

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, TMP102Minimal::I2C_ADDRESS);
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
    sleep_ms(200);
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
    sleep_ms(3000);
    if (pending) printf("alert at %.2f C\n", (double)alertTemperature);
    sensor.offInterrupt();                                  // Unsubscribe, () → void
                                                            // detaches the pin handler

    sensor.setConversionRate(4.0f);                         // Set conversion rate, (hz) → bool
                                                            // back to the power-on default
    sensor.setTHigh(80.0f);                                 // Set T_HIGH, (celsius °C) → void
    sensor.setTLow(75.0f);                                  // Set T_LOW, (celsius °C) → void
    sensor.reset();                                         // General-call reset, () → void
                                                            // resets every general-call device on the bus
    return 0;
}
