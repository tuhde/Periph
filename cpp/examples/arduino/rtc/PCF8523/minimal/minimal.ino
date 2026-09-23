#include <stdarg.h>
#include <Wire.h>
#include "I2CConnection.h"
#include "PCF8523.h"

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
    I2CConnection connection(Wire, PCF8523Minimal::I2C_ADDRESS);

    PCF8523Minimal rtc(connection);                          // Create PCF8523 driver, (connection)

    for (int i = 0; i < 10; ++i) {
        PCF8523Minimal::DateTime dt;
        rtc.getDatetime(dt);                                 // Read calendar clock, () → DateTime
        logPrintf("%04u-%02u-%02u %02u:%02u:%02u\n",
            dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second);
        delay(1000);
    }
}

void loop() {}
