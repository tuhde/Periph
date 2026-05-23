#include <Wire.h>
#include "I2CTransport.h"
#include "AS5600.h"

I2CTransport transport(Wire, 0x36);
AS5600Minimal as5600(transport);

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    float a = as5600.angle();        // Read absolute angle, () → float degrees
    uint16_t r = as5600.angle_raw(); // Read scaled angle count, () → int 0-4095
    Serial.print("angle="); Serial.print(a, 2); Serial.print("°  raw="); Serial.println(r);
    delay(1000);
}
