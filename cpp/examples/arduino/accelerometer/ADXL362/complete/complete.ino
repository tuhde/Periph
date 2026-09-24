#include <SPI.h>
#include <Periph.h>

#ifndef TEST_CS_PIN
#define TEST_CS_PIN 10
#endif

SPISettings settings(8000000, MSBFIRST, SPI_MODE0);
SPIConnection connection(SPI, TEST_CS_PIN, settings);                   // Create SPI connection, (SPI, cs_pin=10, settings) → SPIConnection
ADXL362Full accel(connection);                                           // Create ADXL362 Full driver, (connection) → ADXL362Full

void setup() {
    Serial.begin(115200);
    SPI.begin();
    delay(2000);

    uint8_t devad, devmst, partid, revid;
    accel.device_id(devad, devmst, partid, revid);                       // Read device IDs, (devid_ad, devid_mst, partid, revid) → 4× byte
                                                                        // returns (DEVID_AD, DEVID_MST, PARTID, REVID) raw register bytes
    Serial.print("DEVID_AD=0x"); Serial.print(devad, HEX);
    Serial.print(" DEVID_MST=0x"); Serial.print(devmst, HEX);
    Serial.print(" PARTID=0x"); Serial.print(partid, HEX);
    Serial.print(" REVID=0x"); Serial.println(revid, HEX);              // Print formatted IDs, () → None

    accel.set_range(4);                                                  // Set measurement range, (range_g=4) → None
                                                                        // sets FILTER_CTL.RANGE=±4 g
    accel.set_odr(200.0f);                                               // Set output data rate, (odr_hz=200.0) → None
                                                                        // sets FILTER_CTL.ODR to nearest supported rate
    accel.set_half_bandwidth(true);                                      // Set antialiasing bandwidth, (enabled=true) → None
                                                                        // FILTER_CTL.HALF_BW=1 → bandwidth = ODR/4
    accel.set_noise_mode(ADXL362Full::NOISE_LOW);                        // Set noise mode, (mode=NOISE_LOW=1) → None
                                                                        // POWER_CTL.LOW_NOISE=01 → low-noise mode

    float x, y, z;
    accel.read(x, y, z);                                                 // Read 12-bit acceleration, (x, y, z) → g, g, g
                                                                        // burst-reads XDATA_L/H, YDATA_L/H, ZDATA_L/H
    Serial.print("12-bit: x="); Serial.print(x, 3);
    Serial.print(" y="); Serial.print(y, 3);
    Serial.print(" z="); Serial.println(z, 3);

    accel.read_8bit(x, y, z);                                            // Read 8-bit acceleration, (x, y, z) → g, g, g
                                                                        // burst-reads XDATA/YDATA/ZDATA — lower bus/power cost
    Serial.print(" 8-bit: x="); Serial.print(x, 3);
    Serial.print(" y="); Serial.print(y, 3);
    Serial.print(" z="); Serial.println(z, 3);

    float t = accel.temperature();                                       // Read temperature, () → float °C
                                                                        // typical bias=350 LSB @25 °C, sensitivity=0.065 °C/LSB — uncalibrated
    Serial.print("temperature: "); Serial.print(t, 2); Serial.println(" C");

    uint8_t raw_status = accel.status();                                 // Read STATUS register, () → byte
                                                                        // raw byte: DATA_READY, FIFO_READY, FIFO_WATERMARK, FIFO_OVERRUN, ACT, INACT, AWAKE, ERR_USER_REGS
    Serial.print("status: 0x"); Serial.println(raw_status, HEX);
    Serial.print("awake: "); Serial.println(accel.awake() ? 1 : 0);     // Check AWAKE bit, () → bool
    Serial.print("data_ready: "); Serial.println(accel.data_ready() ? 1 : 0); // Check DATA_READY, () → bool
    Serial.print("fifo_entries: "); Serial.println(accel.fifo_entries()); // Read FIFO entry count, () → uint16_t

    accel.configure_fifo(ADXL362Full::FIFO_STREAM,                       // Configure FIFO, (mode=STREAM=2, store_temp=false, watermark=128) → None
                         false, 128);                                    // sets FIFO_CONTROL (mode, AH watermark bit, FIFO_TEMP) and FIFO_SAMPLES
    accel.set_activity_threshold(0.5f, true);                            // Set activity threshold, (threshold_g=0.5, referenced=true) → None
                                                                        // writes THRESH_ACT_L/H and sets ACT_INACT_CTL.ACT_REF=1
    accel.set_activity_time(5);                                          // Set activity time, (samples=5) → None
                                                                        // TIME_ACT = 5 — number of over-threshold samples required to fire ACT
    accel.set_inactivity_threshold(0.2f, true);                          // Set inactivity threshold, (threshold_g=0.2, referenced=true) → None
                                                                        // writes THRESH_INACT_L/H and sets ACT_INACT_CTL.INACT_REF=1
    accel.set_inactivity_time(30);                                       // Set inactivity time, (samples=30) → None
                                                                        // TIME_INACT_L/H = 30 — consecutive under-threshold samples before INACT fires
    accel.enable_activity_detection(true);                               // Enable activity detection, (enabled=true) → None
                                                                        // ACT_INACT_CTL.ACT_EN=1
    accel.enable_inactivity_detection(true);                             // Enable inactivity detection, (enabled=true) → None
                                                                        // ACT_INACT_CTL.INACT_EN=1
    accel.set_link_loop_mode(ADXL362Full::LINKLOOP_LOOP);                // Set link/loop mode, (mode=LOOP=3) → None
                                                                        // ACT_INACT_CTL.LINKLOOP=11 — chip autonomously toggles ACT/INACT

    accel.set_interrupt(1, ADXL362Full::SOURCE_DATA_READY, true);        // Map DATA_READY to INT1, (pin=1, source=DATA_READY=0, enabled=true) → None
                                                                        // sets INTMAP1.DATA_READY=1
    accel.set_interrupt(2, ADXL362Full::SOURCE_AWAKE, true);             // Map AWAKE to INT2, (pin=2, source=AWAKE=6, enabled=true) → None
                                                                        // sets INTMAP2.AWAKE=1
    accel.set_interrupt_polarity(1, true);                               // Set INT1 active-low, (pin=1, active_low=true) → None
                                                                        // INTMAP1.INT_LOW=1

    accel.self_test(true);                                               // Enable self-test, (enabled=true) → None
                                                                        // SELF_TEST.ST=1 — apply electrostatic force on all three axes
    delay(500);
    accel.self_test(false);                                              // Disable self-test, (enabled=false) → None
                                                                        // SELF_TEST.ST=0

    accel.soft_reset();                                                  // Soft-reset the chip, () → None
                                                                        // writes 0x52 to SOFT_RESET; all registers return to defaults — caller must re-init
}

void loop() {}
