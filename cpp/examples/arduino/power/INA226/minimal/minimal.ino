#include <Wire.h>
#include "I2CConnection.h"
#include "INA226.h"

I2CConnection connection(Wire, 0x40);
INA226Minimal ina(connection);

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
