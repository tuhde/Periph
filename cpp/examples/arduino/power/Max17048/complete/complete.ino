#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x36);
Max17048Full gauge(connection, 1);   // Create MAX17048 driver, (connection, cells)

static volatile uint16_t g_alert = 0;
static void on_alert(uint16_t status) { g_alert = status; }

void setup() {
    Serial.begin(115200);
    Wire.begin();

    Serial.print(gauge.voltage(), 3); Serial.println(" V");                                         // Cell voltage, () → float V
    Serial.print(gauge.percent(), 2); Serial.print(" "); Serial.println("%");                       // State of charge, () → float %
    Serial.print("raw VCELL 0x"); Serial.println((unsigned)(gauge.raw_voltage()), HEX);             // VCELL register, () → uint16_t
    Serial.print("raw SOC 0x"); Serial.println((unsigned)(gauge.raw_soc()), HEX);                   // SOC register, () → uint16_t
    Serial.print(gauge.charge_rate(), 2); Serial.print(" "); Serial.print("%"); Serial.println("/h");  // Charge/discharge rate, () → float %/h
    Serial.print("version 0x"); Serial.println((unsigned)(gauge.version()), HEX);                   // VERSION register, () → uint16_t
    Serial.print("id 0x"); Serial.println((unsigned)(gauge.chip_id()), HEX);                        // Factory ID, () → uint8_t
    gauge.set_rcomp(0x97);                                  // RCOMP, (value)
    Serial.print("rcomp 0x"); Serial.println((unsigned)(gauge.get_rcomp()), HEX);                   // () → uint8_t
    gauge.compensate_temperature(25.0f);                    // RCOMP from the temperature, (celsius)
    gauge.set_alert_soc(10);                                // Empty alert threshold, (percent) 1-32
    Serial.print("alert soc "); Serial.print(gauge.get_alert_soc()); Serial.print(" "); Serial.println("%");  // () → uint8_t %
    gauge.set_soc_change_alert(true);                       // Alert on a 1 % SOC change, (enabled)
    gauge.set_voltage_alert(3.3f, 4.25f);                   // Voltage window, (min_v, max_v) → bool; 0 / 5.1 V disable a limit
    float min_v = 0, max_v = 0;
    gauge.get_voltage_alert(&min_v, &max_v);                // () → min_v, max_v in V
    Serial.print("window "); Serial.print(min_v, 2); Serial.print(" .. "); Serial.print(max_v, 2); Serial.println(" V");
    gauge.set_reset_voltage(3.0f);                          // Reset voltage, (volts)
    Serial.print("reset "); Serial.print(gauge.get_reset_voltage(), 2); Serial.println(" V");       // () → float V
    gauge.enable_voltage_reset_alert(true);                 // Voltage reset asserts ALRT, (enabled)
    gauge.set_comparator_disabled(false);                   // Hibernate comparator off, (disabled)
    gauge.set_hibernate_thresholds(26.624f, 0.06f);         // Hibernate thresholds, (hib_thr_pct_per_h, act_thr_v)
    float hib = 0, act = 0;
    gauge.get_hibernate_thresholds(&hib, &act);             // () → hib_thr_pct_per_h, act_thr_v
    Serial.print("hibernate "); Serial.print(hib, 2); Serial.print(" "); Serial.print("%"); Serial.print("/h, "); Serial.print(act, 3); Serial.println(" V");
    gauge.disable_hibernate();                              // Never hibernate, ()
    gauge.force_hibernate();                                // Always hibernate, ()
    gauge.set_hibernate_thresholds(26.624f, 0.06f);         // Restore the defaults
    Serial.print("hibernating "); Serial.println((int)gauge.is_hibernating());                      // Hibernate status, () → bool
    Serial.print("status 0x"); Serial.println((unsigned)(gauge.read_status()), HEX);                // STATUS register, () → uint16_t
    gauge.clear_status(Max17048Full::SOURCE_RESET);         // Clear flags and ALRT, (mask)
    Serial.print("config 0x"); Serial.println((unsigned)(gauge.read_config()), HEX);                // CONFIG register, () → uint16_t
    gauge.write_config(gauge.read_config());                // Write CONFIG, (value)
    Serial.print("alert sources 0x"); Serial.println((unsigned)(gauge.pollInterrupt()), HEX);       // Alert sources, () → uint16_t
    gauge.enableInterrupt(Max17048Full::SOURCE_SOC_CHANGE); // Enable a source, (source)
    gauge.disableInterrupt(Max17048Full::SOURCE_SOC_CHANGE);// Disable a source, (source)
    gauge.onInterrupt(on_alert);                            // Subscribe to ALRT, (callback, intPin = nullptr)
    gauge.offInterrupt();                                   // Unsubscribe, ()
    gauge.enable_sleep(true);                               // Allow sleep mode, (enabled)
    gauge.sleep();                                          // Sleep, ()
    gauge.wake();                                           // Wake up, ()
    gauge.enable_sleep(false);
    gauge.quick_start();                                    // Restart the SOC estimate, (); only if the first SOC is clearly wrong
    gauge.reset();                                          // Power-on reset, (); waits 1 s
}

void loop() {
    delay(1000);
}
