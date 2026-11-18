#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x36);
Max17048Full gauge(connection, 1);   // Create MAX17048 driver, (connection, cells)

static volatile uint16_t g_alert = 0;
static void on_alert(uint16_t status) { g_alert = status; }

void setup() {
    Serial.begin(115200);
    Wire.begin();

    Serial.print("version 0x"); Serial.print((unsigned)(gauge.version()), HEX); Serial.print(", id 0x"); Serial.println((unsigned)(gauge.chip_id()), HEX);
    gauge.clear_status(Max17048Full::SOURCE_RESET);         // Clear the reset indicator, (mask)

    // Battery fuel gauge: one line per second for 30 s
    for (int n = 0; n < 30; n++) {
        Serial.print(gauge.voltage(), 3); Serial.print(" V  "); Serial.print(gauge.percent(), 1); Serial.print(" "); Serial.print("%"); Serial.print("  "); Serial.print(gauge.charge_rate(), 1); Serial.print(" "); Serial.print("%"); Serial.println("/h");
        delay(1000);
    }

    gauge.set_alert_soc(10);                                // Low-SOC alert at 10 %, (percent)
    gauge.set_voltage_alert(3.3f, 4.25f);                   // Voltage window 3.3-4.25 V, (min_v, max_v)
    gauge.onInterrupt(on_alert);                            // Subscribe to ALRT, (callback, intPin = nullptr)
    delay(5000);
    gauge.offInterrupt();
    Serial.print("poll_interrupt 0x"); Serial.print((unsigned)(gauge.pollInterrupt()), HEX); Serial.print(" (callback saw 0x"); Serial.print((unsigned)((unsigned)g_alert), HEX); Serial.println(")");  // Without an ALRT pin, () → uint16_t

    gauge.compensate_temperature(5.0f);                     // RCOMP for the temperature, (celsius)
    Serial.print("RCOMP at 5 C: 0x"); Serial.println((unsigned)(gauge.get_rcomp()), HEX);
    gauge.compensate_temperature(35.0f);
    Serial.print("RCOMP at 35 C: 0x"); Serial.println((unsigned)(gauge.get_rcomp()), HEX);
    gauge.compensate_temperature(20.0f);

    gauge.disable_hibernate();                              // Never hibernate, ()
    Serial.print("hibernating "); Serial.println((int)gauge.is_hibernating());
    gauge.set_hibernate_thresholds(26.624f, 0.06f);         // Back to the defaults

    gauge.enable_sleep(true);                               // Sleep round trip, (enabled)
    gauge.sleep();
    delay(1000);
    gauge.wake();
    gauge.enable_sleep(false);
    Serial.print("after wake: "); Serial.print(gauge.voltage(), 3); Serial.println(" V");
}

void loop() {
    delay(1000);
}
