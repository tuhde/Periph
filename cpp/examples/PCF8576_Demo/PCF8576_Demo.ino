#include <Arduino.h>
#include <Wire.h>
#include "../../src/transport/I2CTransport.h"
#include "../../src/chips/display/PCF8576.h"

I2CTransport transport(Wire, 0x38);
PCF8576Full lcd(transport);

int counter = 9999;

void setup() {
    Serial.begin(115200);
    Wire.begin();
    Wire.setClock(400000);
    lcd.clear();
}

void loop() {
    if (counter >= 0) {
        int digits[4] = {
            counter / 1000 % 10,
            counter / 100 % 10,
            counter / 10 % 10,
            counter % 10
        };
        Serial.println(counter);
        uint8_t data[4] = {
            PCF8576Full::SEG_7SEG[digits[0]],
            PCF8576Full::SEG_7SEG[digits[1]],
            PCF8576Full::SEG_7SEG[digits[2]],
            PCF8576Full::SEG_7SEG[digits[3]]
        };
        lcd.write_raw(0, data, 4);
        counter--;
        delay(1000);
    } else {
        uint8_t dash[4] = { 0x49, 0x49, 0x49, 0x49 };
        lcd.write_raw(0, dash, 4);
        Serial.println("done");
        while (true) {}
    }
}