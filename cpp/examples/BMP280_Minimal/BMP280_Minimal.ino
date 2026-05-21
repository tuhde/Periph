#include <Wire.h>
#include "I2CTransport.h"
#include "BMP280.h"

I2CTransport transport(Wire, 0x76);
BMP280Minimal bmp(transport);

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    Serial.print(bmp.temperature());  Serial.print(" C  ");
    Serial.print(bmp.pressure());     Serial.println(" hPa");
    delay(1000);
}