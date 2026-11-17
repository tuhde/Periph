#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x40);
LM25066Minimal* hotswapPtr = nullptr;  // constructed in setup(): keeps construction explicit

void setup() {
    Serial.begin(115200);
    Wire.begin();
    hotswapPtr = new LM25066Minimal(connection, 1.0f, false);   // Create LM25066 driver, (connection, rSenseMohm, currentLimitHigh)
    LM25066Minimal& hotswap = *hotswapPtr;
}

void loop() {
    LM25066Minimal& hotswap = *hotswapPtr;
    Serial.print(hotswap.voltage_in());   Serial.print(" V in  ");      // Read VIN, () → float V
    Serial.print(hotswap.voltage_out());  Serial.print(" V out  ");     // Read VOUT, () → float V
    Serial.print(hotswap.current());      Serial.print(" A  ");         // Read input current, () → float A
    Serial.print(hotswap.power());        Serial.print(" W  ");         // Read input power, () → float W
    Serial.print(hotswap.temperature());  Serial.println(" C");         // Read MOSFET temperature, () → float °C
    delay(1000);
}
