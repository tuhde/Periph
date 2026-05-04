#include <Wire.h>
#include "I2CTransport.h"
#include "INA226.h"

I2CTransport transport(Wire, 0x40);
INA226Full ina(transport);

void setup() {
    Serial.begin(115200);
    Wire.begin();

    // 64-sample averaging smooths out switching noise from DC/DC converters
    ina.configure(3, 4, 4, 7);

    // latch the alert so a brief spike is not missed between loop iterations
    ina.set_alert(INA226Full::POL, 1.0f, false, true);

    Serial.println("V          A          W");
}

void loop() {
    // wait for a fresh conversion to avoid reading stale register values
    while (!ina.conversion_ready()) {}

    Serial.print(ina.voltage(), 3); Serial.print("V   ");
    Serial.print(ina.current(), 4); Serial.print("A   ");
    Serial.print(ina.power(),   4); Serial.println("W");

    // reading alert_flags clears the latch — do this after printing measurements
    if (ina.alert_flags() & INA226Full::POL) {
        Serial.println("ALERT: power limit exceeded");
    }

    delay(1000);
}
