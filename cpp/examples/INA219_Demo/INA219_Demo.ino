#include <Wire.h>
#include "I2CTransport.h"
#include "INA219.h"

I2CTransport transport(Wire, 0x40);
INA219Full ina(transport);

float v_readings[10];
float i_readings[10];
float p_readings[10];

void setup() {
    Serial.begin(115200);
    Wire.begin();

    Serial.println("V          A          W");
}

void loop() {
    for (int n = 0; n < 10; n++) {
        float v = ina.voltage();
        float i = ina.current();
        float p = ina.power();
        v_readings[n] = v;
        i_readings[n] = i;
        p_readings[n] = p;

        Serial.print(v, 3); Serial.print("V   ");
        Serial.print(i, 4); Serial.print("A   ");
        Serial.print(p, 4); Serial.println("W");

        if (n == 3) {
            Serial.println("--- switch on load now ---");
        }
        delay(1000);
    }

    float v_min = v_readings[0], v_max = v_readings[0], v_sum = 0;
    float i_min = i_readings[0], i_max = i_readings[0], i_sum = 0;
    float p_min = p_readings[0], p_max = p_readings[0], p_sum = 0;
    for (int n = 0; n < 10; n++) {
        if (v_readings[n] < v_min) v_min = v_readings[n];
        if (v_readings[n] > v_max) v_max = v_readings[n];
        v_sum += v_readings[n];
        if (i_readings[n] < i_min) i_min = i_readings[n];
        if (i_readings[n] > i_max) i_max = i_readings[n];
        i_sum += i_readings[n];
        if (p_readings[n] < p_min) p_min = p_readings[n];
        if (p_readings[n] > p_max) p_max = p_readings[n];
        p_sum += p_readings[n];
    }
    Serial.print("V min="); Serial.print(v_min, 4); Serial.print(" max="); Serial.print(v_max, 4); Serial.print(" mean="); Serial.println(v_sum / 10.0f, 4);
    Serial.print("I min="); Serial.print(i_min, 4); Serial.print(" max="); Serial.print(i_max, 4); Serial.print(" mean="); Serial.println(i_sum / 10.0f, 4);
    Serial.print("P min="); Serial.print(p_min, 4); Serial.print(" max="); Serial.print(p_max, 4); Serial.print(" mean="); Serial.println(p_sum / 10.0f, 4);

    while (true) { delay(1000); }
}
