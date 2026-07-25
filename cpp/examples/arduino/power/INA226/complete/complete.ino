#include <Wire.h>
#include "I2CTransport.h"
#include "INA226.h"

I2CTransport transport(Wire, 0x40);
INA226Full ina(transport);

void setup() {
    Serial.begin(115200);
    Wire.begin();

    Serial.println(ina.manufacturer_id(), HEX);
    Serial.println(ina.die_id(), HEX);

    Serial.println(ina.voltage());
    Serial.println(ina.shunt_voltage());
    Serial.println(ina.current());
    Serial.println(ina.power());
    Serial.println(ina.conversion_ready());
    Serial.println(ina.overflow());

    ina.configure(3, 4, 4, 7);

    ina.set_alert(INA226Full::POL, 1.0f, false, true);
    Serial.println(ina.alert_flags(), HEX);

    ina.set_alert(INA226Full::BOL, 5.5f);
    ina.set_alert(INA226Full::BUL, 4.5f);
    ina.set_alert(INA226Full::SOL, 0.05f);
    ina.set_alert(INA226Full::CNVR);

    ina.shutdown();
    delay(1);
    ina.wake();

    ina.reset();
}

void loop() {}
