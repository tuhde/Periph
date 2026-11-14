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

void setup() {
    Serial.begin(115200);
    Wire.begin();
    I2CConnection connection(Wire, VL6180XMinimal::I2C_ADDRESS, nullptr, nullptr, 2);
    VL6180XMinimal sensor(connection);                      // Create VL6180X driver, (connection)
    sensor.init();                                          // Initialize, () → bool

    while (1) {
        uint8_t mm = 0;
        if (sensor.read_range(mm)) {                        // Single-shot range, (uint8_t& mm) → bool
            logPrintf("%u mm\n", (unsigned)mm);
        } else {
            logPrintf("range error %u\n", (unsigned)sensor.last_error()); // Last range error code, () → uint8_t
        }
        delay(200);
    }
}

void loop() {}
