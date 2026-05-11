#include <Wire.h>
#include "I2CTransport.h"
#include "INA226.h"

I2CTransport transport(Wire, 0x40);
INA226Minimal ina(transport);

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    Serial.print(ina.voltage());   Serial.print("V  ");
    Serial.print(ina.current());   Serial.print("A  ");
    Serial.print(ina.power());     Serial.println("W");
    delay(1000);
}
