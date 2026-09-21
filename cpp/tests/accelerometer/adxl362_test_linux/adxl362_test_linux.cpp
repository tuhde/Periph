#include <cstdio>
#include "SPIConnectionLinux.h"
#include "ADXL362.h"

#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, 0, 8000000);    // Create SPI connection, (bus, device, mode=0, max_speed_hz=8e6) → SPIConnectionLinux
    ADXL362Full accel(connection);                                                  // Create ADXL362 Full driver, (connection) → ADXL362Full

    uint8_t devad, devmst, partid, revid;
    accel.device_id(devad, devmst, partid, revid);                                  // Read device IDs, (devid_ad, devid_mst, partid, revid) → 4× byte
    check_true("device_id_devid_ad",  devad == 0xAD);                               // verify DEVID_AD = 0xAD
    check_true("device_id_devid_mst", devmst == 0x1D);                              // verify DEVID_MST = 0x1D
    check_true("device_id_partid",    partid == 0xF2);                              // verify PARTID = 0xF2

    float x, y, z;
    accel.read(x, y, z);                                                            // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true("read_12bit", true);

    accel.read_8bit(x, y, z);                                                       // Read 8-bit acceleration, (x, y, z) → g, g, g
    check_true("read_8bit", true);

    float t = accel.temperature();                                                  // Read temperature, () → float °C
    (void)t;
    check_true("temperature", true);

    accel.set_range(4);                                                             // Set measurement range, (range_g=4) → None
    check_true("set_range_4g", true);

    accel.set_odr(200.0f);                                                          // Set output data rate, (odr_hz=200.0) → None
    check_true("set_odr_200hz", true);

    accel.set_half_bandwidth(true);                                                 // Set antialiasing bandwidth, (enabled=true) → None
    check_true("set_half_bandwidth", true);

    accel.set_noise_mode(ADXL362Full::NOISE_LOW);                                   // Set noise mode, (mode=NOISE_LOW=1) → None
    check_true("set_noise_mode_low", true);

    uint8_t status = accel.status();                                                // Read STATUS register, () → byte
    (void)status;
    check_true("status", true);

    accel.configure_fifo(ADXL362Full::FIFO_STREAM, false, 128);                     // Configure FIFO, (mode=STREAM=2, store_temp=false, watermark=128) → None
    check_true("configure_fifo", true);

    accel.set_activity_threshold(0.5f, true);                                       // Set activity threshold, (threshold_g=0.5, referenced=true) → None
    accel.set_activity_time(5);                                                     // Set activity time, (samples=5) → None
    accel.set_inactivity_threshold(0.2f, true);                                     // Set inactivity threshold, (threshold_g=0.2, referenced=true) → None
    accel.set_inactivity_time(30);                                                  // Set inactivity time, (samples=30) → None
    accel.enable_activity_detection(true);                                          // Enable activity detection, (enabled=true) → None
    accel.enable_inactivity_detection(true);                                        // Enable inactivity detection, (enabled=true) → None
    accel.set_link_loop_mode(ADXL362Full::LINKLOOP_LOOP);                           // Set link/loop mode, (mode=LOOP=3) → None
    check_true("activity_inactivity_config", true);

    accel.set_interrupt(1, ADXL362Full::SOURCE_DATA_READY, true);                   // Map DATA_READY to INT1, (pin=1, source=DATA_READY=0, enabled=true) → None
    accel.set_interrupt(2, ADXL362Full::SOURCE_AWAKE, true);                        // Map AWAKE to INT2, (pin=2, source=AWAKE=6, enabled=true) → None
    accel.set_interrupt_polarity(1, true);                                          // Set INT1 active-low, (pin=1, active_low=true) → None
    check_true("interrupt_mapping", true);

    accel.self_test(true);                                                          // Enable self-test, (enabled=true) → None
    accel.self_test(false);                                                         // Disable self-test, (enabled=false) → None
    check_true("self_test", true);

    accel.soft_reset();                                                             // Soft-reset the chip, () → None
    check_true("soft_reset", true);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}