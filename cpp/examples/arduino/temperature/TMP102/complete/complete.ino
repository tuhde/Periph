#include <stdarg.h>
#include <Wire.h>
#include <Periph.h>

static void logPrintf(const char* fmt, ...) {
    char buf[128];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    Serial.print(buf);
}

static volatile float alertTemperature = 0.0f;
static volatile bool pending = false;

static void onAlert(float temperatureC) {
    alertTemperature = temperatureC;
    pending = true;
}

void setup() {
    Serial.begin(115200);
    Wire.begin();
    I2CConnection connection(Wire, TMP102Minimal::I2C_ADDRESS);
    TMP102Full sensor(connection);                          // Create TMP102 Full driver, (connection)

    float t = sensor.readTemperature();                     // Read temperature, () → °C
                                                            // decodes TEMPERATURE, 0.0625 °C two's complement
    logPrintf("temperature %.4f C\n", (double)t);
    logPrintf("raw %d\n", (int)sensor.readRaw());              // Read raw count, () → int16_t
                                                            // signed 12/13-bit count, 0.0625 °C per LSB

    logPrintf("config 0x%04X\n", sensor.readConfig());         // Read CONFIGURATION, () → uint16_t
                                                            // reset value 0x60A0
    sensor.writeConfig(sensor.readConfig());                // Write CONFIGURATION, (value) → void
                                                            // read-only bits are masked out

    sensor.setConversionRate(8.0f);                         // Set conversion rate, (hz) → bool
                                                            // 0.25, 1, 4 or 8 Hz
    sensor.setExtendedMode(true);                           // Set extended mode, (on) → void
                                                            // EM bit, 13-bit readings up to +255.9375 °C
    delay(200);
    logPrintf("extended %.4f C\n", (double)sensor.readTemperature());  // Read temperature, () → °C
    sensor.setExtendedMode(false);                          // Set extended mode, (on) → void
                                                            // back to 12 bit

    sensor.setTHigh(30.0f);                                 // Set T_HIGH, (celsius °C) → void
                                                            // rounded to 0.0625 °C
    sensor.setTLow(10.0f);                                  // Set T_LOW, (celsius °C) → void
                                                            // rounded to 0.0625 °C
    logPrintf("high %.4f C\n", (double)sensor.getTHigh());     // Read T_HIGH, () → °C
                                                            // same format as TEMPERATURE
    logPrintf("low %.4f C\n", (double)sensor.getTLow());       // Read T_LOW, () → °C
                                                            // same format as TEMPERATURE

    sensor.setFaultQueue(2);                                // Set fault queue, (count) → bool
                                                            // 1, 2, 4 or 6 consecutive faults
    sensor.setPolarity(false);                              // Set ALERT polarity, (activeHigh) → void
                                                            // POL bit, false = active low
    sensor.setThermostatMode(false);                        // Set thermostat mode, (interrupt) → void
                                                            // TM bit, false = comparator
    logPrintf("alert %d\n", sensor.alertState());              // Read alert state, () → bool
                                                            // AL bit corrected for POL

    sensor.shutdown();                                      // Shut down, () → void
                                                            // SD bit, stops after the current conversion
    float oneShot = 0.0f;
    sensor.oneShot(oneShot);                                // One-shot reading, (celsius&) → bool
                                                            // OS=1, polls until done, restores the mode
    logPrintf("one-shot %.4f C\n", (double)oneShot);
    sensor.wake();                                          // Wake, () → void
                                                            // resume continuous conversion

    logPrintf("polling %d\n", sensor.pollInterrupt());         // Poll the alert state, () → bool
                                                            // reads CONFIGURATION and TEMPERATURE
    sensor.onInterrupt(onAlert);                            // Subscribe to ALERT, (callback, intPin=nullptr) → void
                                                            // callback receives the temperature in °C
    delay(3000);
    if (pending) logPrintf("alert at %.2f C\n", (double)alertTemperature);
    sensor.offInterrupt();                                  // Unsubscribe, () → void
                                                            // detaches the pin handler

    sensor.setConversionRate(4.0f);                         // Set conversion rate, (hz) → bool
                                                            // back to the power-on default
    sensor.setTHigh(80.0f);                                 // Set T_HIGH, (celsius °C) → void
    sensor.setTLow(75.0f);                                  // Set T_LOW, (celsius °C) → void
    sensor.reset();                                         // General-call reset, () → void
                                                            // resets every general-call device on the bus
}

void loop() {}
