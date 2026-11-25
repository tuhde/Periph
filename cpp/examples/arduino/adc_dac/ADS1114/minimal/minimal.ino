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
    I2CConnection connection(Wire, ADS1114Minimal::I2C_ADDRESS);
    ADS1114Minimal adc(connection);                         // Create ADS1114 driver, (connection)

    while (1) {
        float v = adc.readVoltage();                        // Read voltage, () → float V
        logPrintf("%.5f V\n", (double)v);
        delay(1000);
    }
}

void loop() {}
