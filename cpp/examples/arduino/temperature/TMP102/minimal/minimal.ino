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
    I2CConnection connection(Wire, TMP102Minimal::I2C_ADDRESS);
    TMP102Minimal sensor(connection);                       // Create TMP102 driver, (connection)

    while (1) {
        float t = sensor.readTemperature();                     // Read temperature, () → °C
        logPrintf("%.4f C\n", (double)t);
        delay(1000);
    }
}

void loop() {}
