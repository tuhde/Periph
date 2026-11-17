#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x0B);
LC709203FMinimal* gaugePtr = nullptr;  // constructed in setup(): the constructor talks to the chip

void setup() {
    Serial.begin(115200);
    Wire.begin();
    gaugePtr = new LC709203FMinimal(connection, 1000, 1);   // Create LC709203F driver, (connection, capacityMah, profile)
}

void loop() {
    LC709203FMinimal& gauge = *gaugePtr;
    Serial.print(gauge.voltage());      Serial.print(" V  ");   // Read cell voltage, () → float V
    Serial.print(gauge.percent());      Serial.print(" %  ");   // Read state of charge, () → float %
    Serial.print(gauge.temperature());  Serial.println(" C");   // Read cell temperature, () → float °C
    delay(5000);
}
