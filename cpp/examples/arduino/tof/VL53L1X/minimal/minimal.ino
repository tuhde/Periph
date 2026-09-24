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
    I2CConnection connection(Wire, VL53L1XMinimal::I2C_ADDRESS);
    VL53L1XMinimal sensor(connection);                      // Create VL53L1X driver, (connection)

    while (1) {
        uint16_t d = sensor.distance();                     // Measure distance, () → uint16_t mm
        if (sensor.rangeValid()) {                          // Check last measurement, () → bool
            logPrintf("%u mm\n", (unsigned)d);
        } else {
            logPrintf("out of range\n");
        }
        delay(200);
    }
}

void loop() {}
