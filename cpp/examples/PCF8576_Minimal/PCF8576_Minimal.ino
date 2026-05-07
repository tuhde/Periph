#include <Arduino.h>
#include <Wire.h>
#include "../../src/transport/I2CTransport.h"
#include "../../src/chips/display/PCF8576.h"

I2CTransport transport(Wire, 0x38);
PCF8576Minimal lcd(transport);

void setup() {
    Serial.begin(115200);
    Wire.begin();
    Wire.setClock(400000);
    lcd.clear();
}

void loop() {
    lcd.set_digit_7seg(0, PCF8576Minimal::SEG_7SEG[5]);
    lcd.set_digit_7seg(1, PCF8576Minimal::SEG_7SEG[5]);
    lcd.set_digit_7seg(2, PCF8576Minimal::SEG_7SEG[5]);
    lcd.set_digit_7seg(3, PCF8576Minimal::SEG_7SEG[5]);
    delay(1000);
}