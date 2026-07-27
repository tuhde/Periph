#include <Wire.h>
#include "I2CConnection.h"
#include "INA219.h"

I2CConnection connection(Wire, 0x40);
INA219Full ina(connection);

void setup() {
    Serial.begin(115200);
    Wire.begin();

    Serial.println(ina.voltage());
    Serial.println(ina.shunt_voltage());
    Serial.println(ina.current());
    Serial.println(ina.power());
    Serial.println(ina.conversion_ready());
    Serial.println(ina.overflow());

    ina.configure(1, 3, 0x03, 0x03, 7);

    ina.shutdown();
    delay(1);
    ina.wake();

    ina.reset();
}

void loop() {}