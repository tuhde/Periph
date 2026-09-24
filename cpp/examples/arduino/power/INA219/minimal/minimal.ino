#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x40);
INA219Minimal ina(connection);

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
