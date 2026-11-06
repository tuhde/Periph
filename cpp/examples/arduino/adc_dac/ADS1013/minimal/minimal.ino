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
    I2CConnection connection(Wire, ADS1013Minimal::I2C_ADDRESS);
    ADS1013Minimal adc(connection);                     // Create ADS1013 driver, (connection)

    while (1) {
        int16_t raw = adc.readRaw();                    // Read raw code, () → int16 (−2048…2047)
        float v = adc.readVoltage();                    // Read voltage, () → V
        logPrintf("%d -> %.3f V\n", (int)raw, (double)v);
        delay(1000);
    }
}

void loop() {}
