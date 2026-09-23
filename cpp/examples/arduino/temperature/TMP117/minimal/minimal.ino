#include <stdarg.h>
#include <Wire.h>
#include "I2CConnection.h"
#include "TMP117.h"

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
    I2CConnection connection(Wire, TMP117Minimal::I2C_ADDRESS);
    TMP117Minimal sensor(connection);                       // Create TMP117 driver, (connection)

    while (true) {
        float t = sensor.readTemperature();                     // Read temperature, () → °C
        logPrintf("%.4f C\n", (double)t);
        delay(1000);
    }
}

void loop() {}
