#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x0B);
LC709203FFull* gaugePtr = nullptr;  // constructed in setup(): the constructor talks to the chip

void setup() {
    Serial.begin(115200);
    Wire.begin();
    gaugePtr = new LC709203FFull(connection, 1000, 1);   // Create LC709203F driver, (connection, capacityMah, profile)
    LC709203FFull& gauge = *gaugePtr;

    Serial.println(gauge.voltage());                            // Cell voltage, () → float V
    Serial.println(gauge.percent());                            // State of charge (ITE), () → float %
    Serial.println(gauge.temperature());                        // Cell temperature, () → float °C
    Serial.println(gauge.rsoc());                               // RSOC register, () → uint8_t %
    Serial.println(gauge.profile_id());                         // Number of the parameter, () → uint16_t
    Serial.println(gauge.ic_version());                         // IC version, () → uint16_t
    Serial.println(gauge.get_profile());                        // Battery profile, () → uint8_t
    Serial.println(gauge.get_apa());                            // Adjustment Pack Application, () → uint8_t
    Serial.println(gauge.get_apt());                            // Adjustment Pack Thermistor, () → uint16_t
    Serial.println(gauge.get_b_constant());                     // Thermistor B constant, () → uint16_t K
    Serial.println(gauge.get_current_direction());              // Current direction, () → uint16_t
    Serial.println(gauge.is_thermistor_mode());                 // Temperature source, () → bool
    Serial.println(gauge.get_alarm_rsoc());                     // Low-RSOC alarm threshold, () → uint8_t %
    Serial.println(gauge.get_alarm_voltage());                  // Low-voltage alarm threshold, () → float V
    Serial.println(gauge.pollInterrupt());                      // Alarm bitmask, () → uint8_t (ALARM_RSOC = 1, ALARM_VOLTAGE = 2)
    gauge.set_temperature(25.0f);                               // Host temperature (I²C mode), (celsius) clamped to -20...+60 °C
    gauge.set_rsoc(50);                                         // Force RSOC, (percent)
    gauge.init_rsoc();                                          // Initial RSOC from present voltage, ()
    gauge.before_rsoc();                                        // RSOC from max voltage since reset, ()
    gauge.use_thermistor(3380);                                 // Thermistor mode, (bConstant)
    gauge.use_i2c_temperature(25.0f);                           // Back to I²C temperature mode, (celsius)
    gauge.set_apa(0x19);                                        // Adjustment Pack Application, (value)
    gauge.set_apt(0x1E);                                        // Adjustment Pack Thermistor, (value)
    gauge.set_current_direction(LC709203FFull::DIRECTION_AUTO); // Current direction, (mode) AUTO / CHARGE / DISCHARGE
    gauge.set_profile(1);                                       // Battery profile 0/1, (profile) — re-initializes the chip (90 ms)
    gauge.set_alarm_rsoc(20);                                   // Low-RSOC alarm, (percent) 0 = off
    gauge.set_alarm_voltage(3.5f);                              // Low-voltage alarm, (volts) 0 = off
    gauge.sleep();                                              // Sleep mode, ()
    gauge.wake();                                               // Wake up, ()
    Serial.println(gauge.is_sleeping());                        // Power mode, () → bool
}

void loop() {
    delay(1000);
}
