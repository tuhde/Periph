#include <Wire.h>
#include "I2CTransport.h"
#include "INA219.h"

I2CTransport transport(Wire, 0x40);
INA219Full ina(transport);

void setup() {
    Serial.begin(115200);
    Wire.begin();

    Serial.println(ina.voltage());
    Serial.println(ina.shunt_voltage());
    Serial.println(ina.current());
    Serial.println(ina.power());
    Serial.println(ina.conversion_ready());
    Serial.println(ina.overflow());

    ina.configure(INA219Full::BRNG_32V, INA219Full::PGA_8, INA219Full::ADC_12BIT, INA219Full::ADC_12BIT, INA219Full::MODE_SHUNT_BUS_CONT);

    Serial.println(ina.conversion_ready());
    Serial.println(ina.overflow());

    ina.shutdown();
    delay(1);
    ina.wake();

    ina.trigger();

    ina.reset();
}

void loop() {}