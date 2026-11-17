#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x6A);                                   // Create I²C connection, (Wire, addr=0x6A) → Connection
LTC4151Full ltc(connection, 0.02f);                                     // Create LTC4151 driver, (connection, r_sense=0.02 Ω) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
    float f = 0;
    bool ok = false;
    LTC4151Full::Readings r;

    Serial.print("voltage "); Serial.print(ltc.voltage(), 3); Serial.println(" V");  // Read supply voltage, () → float V
                                                                        // VIN code × 25 mV
    Serial.print("current "); Serial.print(ltc.current(), 4); Serial.println(" A");  // Read load current, () → float A
                                                                        // sense voltage / r_sense
    Serial.print("power "); Serial.print(ltc.power(), 3); Serial.println(" W");  // Read load power, () → float W
                                                                        // one block read of SENSE and VIN
    Serial.print("sense voltage "); Serial.print(ltc.sense_voltage(), 5); Serial.println(" V");  // Read SENSE+ − SENSE−, () → float V
                                                                        // code × 20 µV
    Serial.print("adin "); Serial.print(ltc.adin_voltage(), 4); Serial.println(" V");  // Read ADIN pin voltage, () → float V
                                                                        // code × 0.5 mV
    ltc.read_all(r);                                                    // Consistent read of all channels, (out Readings) → void
                                                                        // one block read of registers 0x00–0x05
    Serial.print("vin "); Serial.print(r.vin, 2); Serial.print(" V, sense "); Serial.print(r.sense_v, 5); Serial.print(" V, current "); Serial.print(r.current, 4); Serial.print(" A, power "); Serial.print(r.power, 3); Serial.print(" W, adin "); Serial.print(r.adin, 4); Serial.println(" V");
    Serial.print("raw VIN code "); Serial.print((unsigned)ltc.read_raw(LTC4151Full::VIN)); Serial.println();  // Read raw 12-bit code, (channel 0–2) → uint16_t
                                                                        // SENSE = 0, VIN = 1, ADIN = 2

    ltc.set_sense_resistor(0.01f);                                      // Change sense resistor, (ohms) → bool
                                                                        // false (and no change) for ohms <= 0
    Serial.print("sense resistor "); Serial.print(ltc.get_sense_resistor(), 4); Serial.println(" ohm");  // Read sense resistor, () → float Ω
    ltc.set_sense_resistor(0.02f);                                      // Restore 20 mΩ

    ok = ltc.snapshot(LTC4151Full::SENSE, f);                           // One snapshot conversion, (channel 0–2, out V) → bool
                                                                        // returns to continuous scan afterwards; false on timeout
    Serial.print("snapshot ok "); Serial.print((unsigned)ok); Serial.print(", sense "); Serial.print(f, 5); Serial.println(" V");
    Serial.print("snapshot mode "); Serial.print((unsigned)ltc.is_snapshot_mode()); Serial.println();  // Snapshot mode active, () → bool
    Serial.print("VIN busy "); Serial.print((unsigned)ltc.is_busy(LTC4151Full::VIN)); Serial.println();  // Channel busy, (channel 0–2) → bool
                                                                        // BUSY bit of the channel low register
    ltc.set_continuous();                                               // Back to continuous scan, () → void

    ltc.set_stuck_bus_timer(true);                                      // Stuck-bus reset timer, (enabled) → void
                                                                        // CONTROL.G2 read-modify-write
    ltc.set_page_rw(true);                                              // I²C page read/write, (enabled) → void
                                                                        // CONTROL.G3 read-modify-write
    Serial.print("control 0x"); Serial.print((unsigned)ltc.read_control(), HEX); Serial.println();  // Read CONTROL, () → uint8_t
    ltc.write_control(0x0C);                                            // Write CONTROL, (value) → void
                                                                        // test mode bit 4 is always masked to 0
    Serial.print("temperature "); Serial.print(LTC4151Full::temperature_from_adin(900, 1200), 2); Serial.println(" C");  // NTC example circuit, (adin_code, vin_code) → float °C
    Serial.println("done");
}

void loop() {
    delay(1000);
}
