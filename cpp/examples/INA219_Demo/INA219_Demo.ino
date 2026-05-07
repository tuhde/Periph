#include <Wire.h>
#include "I2CTransport.h"
#include "INA219.h"

I2CTransport transport(Wire, 0x40);
INA219Full ina(transport);

void setup() {
    Serial.begin(115200);
    Wire.begin();

    Serial.println("V          A          W");
}

void loop() {
    // wait for a fresh conversion to avoid reading stale register values
    while (!ina.conversion_ready()) {}

    Serial.print(ina.voltage(), 3); Serial.print("V   ");
    Serial.print(ina.current(), 4); Serial.print("A   ");
    Serial.print(ina.power(),   4); Serial.println("W");

    delay(1000);
}
