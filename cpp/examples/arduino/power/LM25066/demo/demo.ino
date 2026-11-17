#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x40);
LM25066Full* hotswapPtr = nullptr;  // constructed in setup(): keeps construction explicit

void setup() {
    Serial.begin(115200);
    Wire.begin();
    hotswapPtr = new LM25066Full(connection, 1.0f, false);   // Create LM25066 driver, (connection, rSenseMohm, currentLimitHigh)
    LM25066Full& hotswap = *hotswapPtr;
    char id[16], mdl[16], rev[16];
    hotswap.manufacturer_id(id, sizeof id); hotswap.model(mdl, sizeof mdl); hotswap.revision(rev, sizeof rev); // Identify the chip, (buf, len)
    Serial.print(id); Serial.print(" "); Serial.print(mdl); Serial.print(" "); Serial.println(rev);
    hotswap.clear_faults();                                      // Release SMBA after power-up, ()
    hotswap.set_averaging(6);                                    // 64-sample averaging, (exponent)
    hotswap.set_vin_uv_warn(10.0f);                              // VIN under-voltage warning at 10 V, (volts)
    hotswap.set_iin_warn(20.0f);                                 // Input over-current warning at 20 A, (amps)
    hotswap.enable_interrupt(LM25066Full::SOURCE_VIN_UV_WARN | LM25066Full::SOURCE_IIN_WARN); // Unmask both alerts, (source)
}

void loop() {
    LM25066Full& hotswap = *hotswapPtr;
    // Hot-swap rail monitor: one line per second
    Serial.print(hotswap.voltage_in());   Serial.print(" V in  ");      // Read VIN, () → float V
    Serial.print(hotswap.voltage_out());  Serial.print(" V out  ");     // Read VOUT, () → float V
    Serial.print(hotswap.current());      Serial.print(" A (avg ");     // Read input current, () → float A
    Serial.print(hotswap.current_avg());  Serial.print(")  ");          // Averaged current, () → float A
    Serial.print(hotswap.power());        Serial.print(" W  ");         // Read input power, () → float W
    Serial.print(hotswap.temperature());  Serial.println(" C");         // Read MOSFET temperature, () → float °C
    Serial.print("peak W: "); Serial.println(hotswap.power_peak());     // Peak input power, () → float W
    Serial.print("diagnostic: "); Serial.println(hotswap.pollInterrupt(), HEX);   // Alert flags, () → uint16_t
    hotswap.clear_faults();                                             // Finish servicing, ()
    delay(1000);
}
