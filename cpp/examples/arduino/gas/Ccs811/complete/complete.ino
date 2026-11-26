#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x5A);
CCS811Full sensor(connection);                                     // Create CCS811 driver, (connection) → void

static void on_int(uint8_t status) {
    Serial.print("interrupt 0x"); Serial.print((unsigned)(status), HEX); Serial.println();
}

void setup() {
    Serial.begin(115200);
    Wire.begin();
    bool ok = sensor.init();                                       // Check HW_ID, start application, mode 1, () → bool
                                                                   // HW_ID 0x81, APP_START if the chip is in boot mode, one sample per second
    uint8_t hw_id = 0, hw_version = 0;
    ok = sensor.hardware_version(hw_id, hw_version);               // Hardware ID and version, (hw_id&, hw_version&) → bool
                                                                   // HW_ID is 0x81, HW_VERSION 0x1X
    CCS811Version boot = {0, 0, 0}, app = {0, 0, 0};
    ok = sensor.firmware_version(boot, app);                       // Firmware versions, (boot&, app&) → bool
                                                                   // bootloader and application firmware, each major.minor.trivial
    Serial.print("hw 0x"); Serial.print((unsigned)(hw_id), HEX); Serial.print("/0x"); Serial.print((unsigned)(hw_version), HEX); Serial.print(" boot "); Serial.print((unsigned)(boot.major)); Serial.print("."); Serial.print((unsigned)(boot.minor)); Serial.print("."); Serial.print((unsigned)(boot.trivial)); Serial.print(" app "); Serial.print((unsigned)(app.major)); Serial.print("."); Serial.print((unsigned)(app.minor)); Serial.print("."); Serial.print((unsigned)(app.trivial)); Serial.println();
    uint8_t st = 0, err = 0;
    ok = sensor.status(st);                                        // STATUS register, (status&) → bool
                                                                   // bit 7 FW_MODE, bit 4 APP_VALID, bit 3 DATA_READY, bit 0 ERROR
    ok = sensor.error(err);                                        // ERROR_ID register, (error_id&) → bool
                                                                   // zero unless STATUS.ERROR is set
    Serial.print("status 0x"); Serial.print((unsigned)(st), HEX); Serial.print(" error 0x"); Serial.print((unsigned)(err), HEX); Serial.println();

    uint16_t eco2 = 0, tvoc = 0;
    ok = sensor.read(eco2, tvoc);                                  // Next sample, (eco2_ppm&, tvoc_ppb&) → bool
                                                                   // waits for DATA_READY, then reads eCO2 and TVOC
    Serial.print("eCO2 "); Serial.print((unsigned)(eco2)); Serial.print(" ppm TVOC "); Serial.print((unsigned)(tvoc)); Serial.print(" ppb"); Serial.println();
    ok = sensor.read_eco2(eco2);                                   // eCO2 only, (eco2_ppm&) → bool
                                                                   // equivalent CO2 estimated from the VOC level, 400-8192 ppm
    ok = sensor.read_tvoc(tvoc);                                   // TVOC only, (tvoc_ppb&) → bool
                                                                   // total volatile organic compounds, 0-1187 ppb
    Serial.print("eCO2 "); Serial.print((unsigned)(eco2)); Serial.print(" ppm TVOC "); Serial.print((unsigned)(tvoc)); Serial.print(" ppb"); Serial.println();
    uint8_t current = 0;
    float voltage = 0;
    ok = sensor.read_raw(current, voltage);                        // Raw sensor data, (current_ua&, voltage_v&) → bool
                                                                   // sense current and the voltage across the sensor
    Serial.print("raw "); Serial.print((unsigned)(current)); Serial.print(" uA "); Serial.print((double)(voltage), 3); Serial.print(" V"); Serial.println();
    bool ready = false;
    ok = sensor.data_ready(ready);                                 // New sample waiting, (ready&) → bool
                                                                   // false right after a read, because reading the result clears DATA_READY
    Serial.print("data ready "); Serial.print((unsigned)(ready)); Serial.println();

    ok = sensor.set_environment(45.0f, 22.5f);                     // Compensation data, (humidity_rh, temperature_c) → bool
                                                                   // real ambient values improve accuracy; the chip assumes 50 %RH and 25 C otherwise
    uint16_t v_ref = 0, v_ntc = 0;
    ok = sensor.read_ntc(v_ref, v_ntc);                            // NTC circuit voltages, (v_ref_mv&, v_ntc_mv&) → bool
                                                                   // only meaningful with a reference resistor and NTC on the AUX pin
    float r_ntc = 0;
    ok = sensor.ntc_resistance(100000.0f, r_ntc);                  // NTC resistance, (r_ref_ohm, r_ntc_ohm&) → bool
                                                                   // V_NTC x R_REF / V_REF; false without an NTC circuit (V_REF = 0)
    Serial.print("NTC "); Serial.print((unsigned)(v_ref)); Serial.print(" mV / "); Serial.print((unsigned)(v_ntc)); Serial.print(" mV, "); Serial.print((double)(r_ntc), 0); Serial.print(" ohm (ok="); Serial.print((unsigned)(ok)); Serial.print(")"); Serial.println();

    ok = sensor.set_thresholds(1000, 2000, 50);                    // Threshold boundaries, (low_medium_ppm = 1500, medium_high_ppm = 2500, hysteresis_ppm = 50) → bool
                                                                   // eCO2 zones used by the threshold interrupt
    uint16_t baseline = 0;
    ok = sensor.get_baseline(baseline);                            // Algorithm baseline, (baseline&) → bool
                                                                   // save it to non-volatile storage about once a day
    Serial.print("baseline 0x"); Serial.print((unsigned)(baseline), HEX); Serial.println();
    ok = sensor.set_baseline(baseline);                            // Restore baseline, (baseline) → bool
                                                                   // restore a saved value only after about 20 min of warm-up

    ok = sensor.enable_interrupt(CCS811Full::SOURCE_DATA_READY);   // nINT on new data, (source) → bool
                                                                   // sets INT_DATARDY and keeps the drive mode
    sensor.on_interrupt(on_int);                                   // Subscribe, (callback, intPin = nullptr) → void
                                                                   // needs an INT pin on the connection; otherwise poll with poll_interrupt()
    uint8_t pending = sensor.poll_interrupt();                     // STATUS without releasing nINT, () → uint8_t
                                                                   // DATA_READY shows whether a sample is pending
    Serial.print("poll 0x"); Serial.print((unsigned)(pending), HEX); Serial.println();
    sensor.off_interrupt();                                        // Unsubscribe, () → void
                                                                   // removes the edge handler
    ok = sensor.disable_interrupt(CCS811Full::SOURCE_DATA_READY);  // nINT off, (source) → bool
                                                                   // clears INT_DATARDY

    ok = sensor.set_mode(4);                                       // Drive mode, (mode 0-4) → bool
                                                                   // mode 4: raw data every 250 ms, no eCO2/TVOC updates
    delay(500);
    ok = sensor.read_raw(current, voltage);                        // Raw sensor data, (current_ua&, voltage_v&) → bool
                                                                   // RAW_DATA keeps updating in mode 4
    ok = sensor.set_mode(0);                                       // Drive mode, (mode 0-4) → bool
                                                                   // idle before switching to another mode
    ok = sensor.set_mode(1);                                       // Drive mode, (mode 0-4) → bool
                                                                   // back to one sample per second

    ok = sensor.soft_reset();                                      // Software reset, () → bool
                                                                   // SW_RESET into boot mode, then APP_START and mode 1 again
    Serial.print("ok "); Serial.print((unsigned)(ok)); Serial.println();
}

void loop() {
}
