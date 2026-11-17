#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x0B);
LC709203FFull* gaugePtr = nullptr;  // constructed in setup(): the constructor talks to the chip

void setup() {
    Serial.begin(115200);
    Wire.begin();
    gaugePtr = new LC709203FFull(connection, 1000, 1);   // Create LC709203F driver, (connection, capacityMah, profile)
    LC709203FFull& gauge = *gaugePtr;

    Serial.print("IC version 0x"); Serial.print(gauge.ic_version(), HEX);
    Serial.print(", profile id 0x"); Serial.print(gauge.profile_id(), HEX);
    Serial.print(", RSOC "); Serial.println(gauge.rsoc());
    gauge.set_alarm_rsoc(20);                                   // Alarm at 20 % RSOC, (percent)
    gauge.set_alarm_voltage(3.5f);                              // Alarm at 3.5 V, (volts)
}

void loop() {
    // Battery monitor: one line per 5 s, host temperature fixed at 25 °C
    LC709203FFull& gauge = *gaugePtr;
    gauge.set_temperature(25.0f);
    Serial.print(gauge.voltage());      Serial.print(" V  ");
    Serial.print(gauge.percent());      Serial.print(" %  ");
    Serial.print(gauge.temperature());  Serial.print(" C  alarm=");
    Serial.println(gauge.pollInterrupt());
    delay(5000);
}
