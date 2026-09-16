#include "HX711Connection.h"
#include "HX710B.h"

HX711Connection connection(5, 6);
HX710BMinimal<HX711Connection> chip(connection);

void setup() {
    Serial.begin(115200);
}

void loop() {
    bool ready = chip.is_ready();
    int32_t raw = chip.read_raw();
    Serial.println(raw);
    delay(500);
}
