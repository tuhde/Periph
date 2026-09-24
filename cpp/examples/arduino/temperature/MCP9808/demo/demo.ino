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

// Industrial freezer monitor: the healthy range is -25 C to -15 C, and
// -5 C means the door has been left open too long. The Alert output fires
// in interrupt mode each time the temperature leaves or re-enters the
// window; each event is reported with the boundary that tripped.

static const int MAX_ALERTS = 10;

static volatile uint8_t pendingStatus = 0;
static volatile bool pending = false;

static void onAlert(uint8_t status) {
    pendingStatus = status;
    pending = true;
}

void setup() {
    Serial.begin(115200);
    Wire.begin();
    I2CConnection connection(Wire, MCP9808Minimal::I2C_ADDRESS);
    MCP9808Full sensor(connection);                         // Create MCP9808 Full driver, (connection)

    // --- Trade resolution for faster sampling ---
    // 0.25 C is plenty for a freezer and converts in ~65 ms instead of 250 ms,
    // so a door opening shows up in the next reading almost immediately.
    sensor.setResolution(0.25f);                            // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → bool

    // --- Program the healthy window and the door-open threshold ---
    // TLOWER/TUPPER bracket normal operation; TCRIT flags a door left open.
    // 3 C of hysteresis stops the Alert chattering while the compressor cycles.
    sensor.setLowerLimit(-25.0f);                           // Set TLOWER, (celsius °C) → void
    sensor.setUpperLimit(-15.0f);                           // Set TUPPER, (celsius °C) → void
    sensor.setCriticalLimit(-5.0f);                         // Set TCRIT, (celsius °C) → void
    sensor.setHysteresis(3.0f);                             // Set hysteresis, (celsius 0|1.5|3.0|6.0) → bool

    // --- Route every boundary to the Alert pin as a latched interrupt ---
    // Interrupt mode latches each crossing until clearInterrupt(), so a short
    // excursion is never missed between two reads.
    sensor.configureAlert(MCP9808Full::AlertMode::All,
                          MCP9808Full::AlertOutput::Interrupt,
                          MCP9808Full::AlertPolarity::ActiveLow);  // Configure Alert, (mode=All, output=Comparator, polarity=ActiveLow) → bool
    sensor.enableAlert();                                   // Enable Alert output, () → void
    sensor.onInterrupt(onAlert);                            // Subscribe to Alert, (callback, intPin=nullptr) → void
    logPrintf("monitoring, %.2f C now\n", (double)sensor.readTemperature());  // Read ambient temperature, () → °C

    // --- Report which boundary tripped, then re-arm ---
    // Without an Alert pin on the connection, poll ALERT_STAT instead. An empty
    // status mask means the temperature has come back inside the healthy window.
    int alerts = 0;
    while (alerts < MAX_ALERTS) {
        if (!connection.intPin() && sensor.isAlertAsserted()) {  // Check Alert output, () → bool
            pendingStatus = sensor.pollInterrupt();         // Read boundary status, () → uint8_t mask
            pending = true;
        }
        if (pending) {
            pending = false;
            uint8_t status = pendingStatus;
            float t = sensor.readTemperature();             // Read ambient temperature, () → °C
            if (status & MCP9808Full::SOURCE_CRITICAL)   logPrintf("%.2f C  CRITICAL - door open?\n", (double)t);
            else if (status & MCP9808Full::SOURCE_UPPER) logPrintf("%.2f C  too warm\n", (double)t);
            else if (status & MCP9808Full::SOURCE_LOWER) logPrintf("%.2f C  too cold\n", (double)t);
            else                                         logPrintf("%.2f C  back in range\n", (double)t);
            sensor.clearInterrupt();                        // Clear interrupt-mode Alert, () → void
            alerts++;
        }
        delay(1000);
    }

    // --- Shut down cleanly after the demo run ---
    sensor.disableAlert();                                  // Disable Alert output, () → void
    sensor.offInterrupt();                                  // Unsubscribe, () → void
}

void loop() {}
