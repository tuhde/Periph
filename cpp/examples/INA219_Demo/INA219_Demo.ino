#include <Wire.h>
#include "I2CTransport.h"
#include "INA219.h"

I2CTransport transport(Wire, 0x40);
INA219Full ina(transport);

float v_min = 1e9, v_max = -1e9, v_sum = 0;
float i_min = 1e9, i_max = -1e9, i_sum = 0;
float p_min = 1e9, p_max = -1e9, p_sum = 0;

void setup() {
    Serial.begin(115200);
    Wire.begin();

    ina.configure(INA219Full::BRNG_32V, INA219Full::PGA_8, INA219Full::ADC_12BIT, INA219Full::ADC_12BIT, INA219Full::MODE_SHUNT_BUS_CONT);

    Serial.println(F("V_bus       V_shunt     I          P"));
    for (int j = 0; j < 10; j++) {
        float v = ina.voltage();
        float vs = ina.shunt_voltage();
        float i = ina.current();
        float p = ina.power();

        if (v < v_min) v_min = v;
        if (v > v_max) v_max = v;
        v_sum += v;

        if (i < i_min) i_min = i;
        if (i > i_max) i_max = i;
        i_sum += i;

        if (p < p_min) p_min = p;
        if (p > p_max) p_max = p;
        p_sum += p;

        char buf[64];
        snprintf(buf, sizeof(buf), "%7.3f V  %7.5f V  %7.4f A  %7.4f W", v, vs, i, p);
        Serial.println(buf);

        if (j == 3) {
            Serial.println(F(">>> Switch on your load now <<<"));
        }

        delay(1000);
    }

    Serial.print(F("min: ")); Serial.print(v_min, 3); Serial.print(F(" V  ")); Serial.print(i_min, 4); Serial.print(F(" A  ")); Serial.println(p_min, 4);
    Serial.print(F("max: ")); Serial.print(v_max, 3); Serial.print(F(" V  ")); Serial.print(i_max, 4); Serial.print(F(" A  ")); Serial.println(p_max, 4);
    Serial.print(F("mean: ")); Serial.print(v_sum / 10, 3); Serial.print(F(" V  ")); Serial.print(i_sum / 10, 4); Serial.print(F(" A  ")); Serial.println(p_sum / 10, 4);
}

void loop() {}