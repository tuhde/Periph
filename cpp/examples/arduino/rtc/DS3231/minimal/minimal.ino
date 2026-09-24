#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, DS3231Minimal::I2C_ADDRESS);
DS3231Minimal rtc(connection);                           // Create DS3231 driver, (connection)

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    DS3231Minimal::DateTime dt;
    rtc.getDatetime(dt);                                  // Read calendar clock, () → DateTime
    float tempC = rtc.readTemperature();                  // Read on-chip temperature, () → float C
    Serial.print(dt.year); Serial.print('-');
    Serial.print(dt.month); Serial.print('-');
    Serial.print(dt.day); Serial.print(' ');
    Serial.print(dt.hour); Serial.print(':');
    Serial.print(dt.minute); Serial.print(':');
    Serial.print(dt.second); Serial.print("  ");
    Serial.print(tempC); Serial.println(" C");
    delay(1000);
}
