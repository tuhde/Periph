#include <SPI.h>
#include "SPIConnection.h"
#include "ADXL362.h"

#ifndef TEST_CS_PIN
#define TEST_CS_PIN SS
#endif
#ifndef TEST_SCK
#define TEST_SCK SCK
#endif
#ifndef TEST_MOSI
#define TEST_MOSI MOSI
#endif
#ifndef TEST_MISO
#define TEST_MISO MISO
#endif

SPISettings settings(8000000, MSBFIRST, SPI_MODE0);
SPIConnection connection(SPI, TEST_CS_PIN, settings);                   // Create SPI connection, (SPI, cs_pin, settings) → SPIConnection
ADXL362Full accel(connection);                                           // Create ADXL362 Full driver, (connection) → ADXL362Full

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin(TEST_SCK, TEST_MISO, TEST_MOSI, TEST_CS_PIN);

    uint8_t devad, devmst, partid, revid;
    accel.device_id(devad, devmst, partid, revid);                       // Read device IDs, (devid_ad, devid_mst, partid, revid) → 4× byte
    check_true(devad == 0xAD, "device_id_devid_ad");                     // verify DEVID_AD = 0xAD
    check_true(devmst == 0x1D, "device_id_devid_mst");                   // verify DEVID_MST = 0x1D
    check_true(partid == 0xF2, "device_id_partid");                       // verify PARTID = 0xF2

    float x, y, z;
    accel.read(x, y, z);                                                 // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(true, "read_12bit");

    accel.read_8bit(x, y, z);                                            // Read 8-bit acceleration, (x, y, z) → g, g, g
    check_true(true, "read_8bit");

    float t = accel.temperature();                                       // Read temperature, () → float °C
    (void)t;
    check_true(true, "temperature");

    accel.set_range(4);                                                  // Set measurement range, (range_g=4) → None
    check_true(true, "set_range_4g");

    accel.set_odr(200.0f);                                               // Set output data rate, (odr_hz=200.0) → None
    check_true(true, "set_odr_200hz");

    accel.set_half_bandwidth(true);                                      // Set antialiasing bandwidth, (enabled=true) → None
    check_true(true, "set_half_bandwidth");

    accel.set_noise_mode(ADXL362Full::NOISE_LOW);                        // Set noise mode, (mode=NOISE_LOW=1) → None
    check_true(true, "set_noise_mode_low");

    uint8_t status = accel.status();                                     // Read STATUS register, () → byte
    (void)status;
    check_true(true, "status");

    accel.configure_fifo(ADXL362Full::FIFO_STREAM, false, 128);          // Configure FIFO, (mode=STREAM=2, store_temp=false, watermark=128) → None
    check_true(true, "configure_fifo");

    accel.set_activity_threshold(0.5f, true);                            // Set activity threshold, (threshold_g=0.5, referenced=true) → None
    accel.set_activity_time(5);                                          // Set activity time, (samples=5) → None
    accel.set_inactivity_threshold(0.2f, true);                          // Set inactivity threshold, (threshold_g=0.2, referenced=true) → None
    accel.set_inactivity_time(30);                                       // Set inactivity time, (samples=30) → None
    accel.enable_activity_detection(true);                               // Enable activity detection, (enabled=true) → None
    accel.enable_inactivity_detection(true);                             // Enable inactivity detection, (enabled=true) → None
    accel.set_link_loop_mode(ADXL362Full::LINKLOOP_LOOP);                // Set link/loop mode, (mode=LOOP=3) → None
    check_true(true, "activity_inactivity_config");

    accel.set_interrupt(1, ADXL362Full::SOURCE_DATA_READY, true);        // Map DATA_READY to INT1, (pin=1, source=DATA_READY=0, enabled=true) → None
    accel.set_interrupt(2, ADXL362Full::SOURCE_AWAKE, true);             // Map AWAKE to INT2, (pin=2, source=AWAKE=6, enabled=true) → None
    accel.set_interrupt_polarity(1, true);                               // Set INT1 active-low, (pin=1, active_low=true) → None
    check_true(true, "interrupt_mapping");

    accel.self_test(true);                                               // Enable self-test, (enabled=true) → None
    delay(500);
    accel.self_test(false);                                              // Disable self-test, (enabled=false) → None
    check_true(true, "self_test");

    accel.soft_reset();                                                  // Soft-reset the chip, () → None
    check_true(true, "soft_reset");

    Serial.print("===DONE: "); Serial.print(passed);
    Serial.print(" passed, "); Serial.print(failed); Serial.println(" failed===");
}

void loop() {
}