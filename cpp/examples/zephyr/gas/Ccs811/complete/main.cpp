#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Ccs811.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define CCS811_ADDR 0x5A

static void on_int(uint8_t status) {
    printk("interrupt 0x%02X\n", (unsigned)(status));
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, CCS811_ADDR);
    CCS811Full sensor(connection);                                 // Create CCS811 driver, (connection) → void
    bool ok = sensor.init();                                       // Check HW_ID, start application, mode 1, () → bool
                                                                   // HW_ID 0x81, APP_START if the chip is in boot mode, one sample per second
    uint8_t hw_id = 0, hw_version = 0;
    ok = sensor.hardware_version(hw_id, hw_version);               // Hardware ID and version, (hw_id&, hw_version&) → bool
                                                                   // HW_ID is 0x81, HW_VERSION 0x1X
    CCS811Version boot = {0, 0, 0}, app = {0, 0, 0};
    ok = sensor.firmware_version(boot, app);                       // Firmware versions, (boot&, app&) → bool
                                                                   // bootloader and application firmware, each major.minor.trivial
    printk("hw 0x%02X/0x%02X boot %u.%u.%u app %u.%u.%u\n", (unsigned)(hw_id), (unsigned)(hw_version), (unsigned)(boot.major), (unsigned)(boot.minor), (unsigned)(boot.trivial), (unsigned)(app.major), (unsigned)(app.minor), (unsigned)(app.trivial));
    uint8_t st = 0, err = 0;
    ok = sensor.status(st);                                        // STATUS register, (status&) → bool
                                                                   // bit 7 FW_MODE, bit 4 APP_VALID, bit 3 DATA_READY, bit 0 ERROR
    ok = sensor.error(err);                                        // ERROR_ID register, (error_id&) → bool
                                                                   // zero unless STATUS.ERROR is set
    printk("status 0x%02X error 0x%02X\n", (unsigned)(st), (unsigned)(err));

    uint16_t eco2 = 0, tvoc = 0;
    ok = sensor.read(eco2, tvoc);                                  // Next sample, (eco2_ppm&, tvoc_ppb&) → bool
                                                                   // waits for DATA_READY, then reads eCO2 and TVOC
    printk("eCO2 %u ppm TVOC %u ppb\n", (unsigned)(eco2), (unsigned)(tvoc));
    ok = sensor.read_eco2(eco2);                                   // eCO2 only, (eco2_ppm&) → bool
                                                                   // equivalent CO2 estimated from the VOC level, 400-8192 ppm
    ok = sensor.read_tvoc(tvoc);                                   // TVOC only, (tvoc_ppb&) → bool
                                                                   // total volatile organic compounds, 0-1187 ppb
    printk("eCO2 %u ppm TVOC %u ppb\n", (unsigned)(eco2), (unsigned)(tvoc));
    uint8_t current = 0;
    float voltage = 0;
    ok = sensor.read_raw(current, voltage);                        // Raw sensor data, (current_ua&, voltage_v&) → bool
                                                                   // sense current and the voltage across the sensor
    printk("raw %u uA %.3f V\n", (unsigned)(current), (double)(voltage));
    bool ready = false;
    ok = sensor.data_ready(ready);                                 // New sample waiting, (ready&) → bool
                                                                   // false right after a read, because reading the result clears DATA_READY
    printk("data ready %u\n", (unsigned)(ready));

    ok = sensor.set_environment(45.0f, 22.5f);                     // Compensation data, (humidity_rh, temperature_c) → bool
                                                                   // real ambient values improve accuracy; the chip assumes 50 %RH and 25 C otherwise
    uint16_t v_ref = 0, v_ntc = 0;
    ok = sensor.read_ntc(v_ref, v_ntc);                            // NTC circuit voltages, (v_ref_mv&, v_ntc_mv&) → bool
                                                                   // only meaningful with a reference resistor and NTC on the AUX pin
    float r_ntc = 0;
    ok = sensor.ntc_resistance(100000.0f, r_ntc);                  // NTC resistance, (r_ref_ohm, r_ntc_ohm&) → bool
                                                                   // V_NTC x R_REF / V_REF; false without an NTC circuit (V_REF = 0)
    printk("NTC %u mV / %u mV, %.0f ohm (ok=%u)\n", (unsigned)(v_ref), (unsigned)(v_ntc), (double)(r_ntc), (unsigned)(ok));

    ok = sensor.set_thresholds(1000, 2000, 50);                    // Threshold boundaries, (low_medium_ppm = 1500, medium_high_ppm = 2500, hysteresis_ppm = 50) → bool
                                                                   // eCO2 zones used by the threshold interrupt
    uint16_t baseline = 0;
    ok = sensor.get_baseline(baseline);                            // Algorithm baseline, (baseline&) → bool
                                                                   // save it to non-volatile storage about once a day
    printk("baseline 0x%04X\n", (unsigned)(baseline));
    ok = sensor.set_baseline(baseline);                            // Restore baseline, (baseline) → bool
                                                                   // restore a saved value only after about 20 min of warm-up

    ok = sensor.enable_interrupt(CCS811Full::SOURCE_DATA_READY);   // nINT on new data, (source) → bool
                                                                   // sets INT_DATARDY and keeps the drive mode
    sensor.on_interrupt(on_int);                                   // Subscribe, (callback, intPin = nullptr) → void
                                                                   // needs an INT pin on the connection; otherwise poll with poll_interrupt()
    uint8_t pending = sensor.poll_interrupt();                     // STATUS without releasing nINT, () → uint8_t
                                                                   // DATA_READY shows whether a sample is pending
    printk("poll 0x%02X\n", (unsigned)(pending));
    sensor.off_interrupt();                                        // Unsubscribe, () → void
                                                                   // removes the edge handler
    ok = sensor.disable_interrupt(CCS811Full::SOURCE_DATA_READY);  // nINT off, (source) → bool
                                                                   // clears INT_DATARDY

    ok = sensor.set_mode(4);                                       // Drive mode, (mode 0-4) → bool
                                                                   // mode 4: raw data every 250 ms, no eCO2/TVOC updates
    k_sleep(K_MSEC(500));
    ok = sensor.read_raw(current, voltage);                        // Raw sensor data, (current_ua&, voltage_v&) → bool
                                                                   // RAW_DATA keeps updating in mode 4
    ok = sensor.set_mode(0);                                       // Drive mode, (mode 0-4) → bool
                                                                   // idle before switching to another mode
    ok = sensor.set_mode(1);                                       // Drive mode, (mode 0-4) → bool
                                                                   // back to one sample per second

    ok = sensor.soft_reset();                                      // Software reset, () → bool
                                                                   // SW_RESET into boot mode, then APP_START and mode 1 again
    printk("ok %u\n", (unsigned)(ok));
    return 0;
}
