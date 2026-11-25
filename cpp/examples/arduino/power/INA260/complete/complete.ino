#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x40);
INA260Full ina(connection);

void setup() {
    Serial.begin(115200);
    Wire.begin();

    Serial.println(ina.manufacturer_id(), HEX);
    Serial.println(ina.die_id(), HEX);

    Serial.println(ina.voltage());
    Serial.println(ina.current());
    Serial.println(ina.power());
    Serial.println(ina.conversion_ready());
    Serial.println(ina.overflow());

    ina.configure(3, 4, 4, 7);

    ina.set_alert(INA260Full::POL, 1.0f, false, true);
    Serial.println(ina.alert_flags(), HEX);

    ina.set_alert(INA260Full::BOL, 5.5f);
    ina.set_alert(INA260Full::BUL, 4.5f);
    ina.set_alert(INA260Full::OCL, 5.0f);
    ina.set_alert(INA260Full::CNVR);
    ina.clear_alert();
    ina.trigger(INA260Full::MODE_SHUNT_BUS_TRIGGERED);

    ina.shutdown();
    delay(1);
    ina.wake();

    ina.reset();
}

void loop() {}
