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

    logPrintf("config 0x%04X\n", sensor.readConfig());         // Read CONFIGURATION, () → uint16_t
    logPrintf("limits low %.4f high %.4f\n",
           (double)sensor.getTLow(), (double)sensor.getTHigh());  // Read T_LOW, () → °C; Read T_HIGH, () → °C

    // --- Continuous reading at 4 Hz for 5 s ---
    for (int i = 0; i < 20; i++) {
        logPrintf("%.4f C (raw %d)\n", (double)sensor.readTemperature(), (int)sensor.readRaw());  // Read temperature, () → °C; Read raw count, () → int16_t
        delay(250);
    }

    // --- 8 Hz and the 13-bit extended mode ---
    sensor.setConversionRate(8.0f);                         // Set conversion rate, (hz) → bool
    sensor.setExtendedMode(true);                           // Set extended mode, (on) → void
    delay(200);
    logPrintf("extended %.4f C\n", (double)sensor.readTemperature());  // Read temperature, () → °C
    sensor.setExtendedMode(false);                          // Set extended mode, (on) → void
    sensor.setConversionRate(4.0f);                         // Set conversion rate, (hz) → bool

    // --- Shutdown and one-shot readings (about 10 ms each) ---
    sensor.shutdown();                                      // Shut down, () → void
    for (int i = 0; i < 5; i++) {
        float oneShot = 0.0f;
        sensor.oneShot(oneShot);                            // One-shot reading, (celsius&) → bool
        logPrintf("one-shot %.4f C\n", (double)oneShot);
    }
    sensor.wake();                                          // Wake, () → void

    // --- Thermostat around the current temperature ---
    float t = sensor.readTemperature();                     // Read temperature, () → °C
    sensor.setTHigh(t + 1.0f);                              // Set T_HIGH, (celsius °C) → void
    sensor.setTLow(t - 1.0f);                               // Set T_LOW, (celsius °C) → void
    sensor.setFaultQueue(2);                                // Set fault queue, (count) → bool
    sensor.setPolarity(false);                              // Set ALERT polarity, (activeHigh) → void
    sensor.setThermostatMode(true);                         // Set thermostat mode, (interrupt) → void
    logPrintf("warm the sensor with a finger ...\n");
    for (int i = 0; i < 40; i++) {
        if (sensor.pollInterrupt()) {                       // Poll the alert state, () → bool
            logPrintf("ALERT: %.2f C, alert_state=%d\n", (double)sensor.readTemperature(), sensor.alertState());  // Read alert state, () → bool
        }
        delay(250);
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

void loop() {}
