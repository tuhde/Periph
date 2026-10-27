#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>
#include <math.h>

#ifndef TEST_SDA
#define TEST_SDA 21
#endif
#ifndef TEST_SCL
#define TEST_SCL 22
#endif

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();                                    // other cores: board's default SDA/SCL
    Wire.setClock(400000);
#endif
    I2CConnection accel_connection(Wire, LSM303Minimal::I2C_ADDRESS_ACCEL);
    I2CConnection mag_connection(Wire, LSM303Minimal::I2C_ADDRESS_MAG);
    LSM303Full accel(accel_connection, mag_connection);  // Create LSM303 driver, (accel_connection, mag_connection)

    float ax, ay, az;
    float mx, my, mz;
    accel.readAcceleration(ax, ay, az);          // Read acceleration, (x, y, z) → g, g, g
                                                 // burst-reads the six output registers
    accel.readMagnetic(mx, my, mz);                // Read magnetic field, (x, y, z) → uT, uT, uT
                                                 // burst-reads X, Z, Y and reorders them
    Serial.print("accel x="); Serial.print(ax, 4);
    Serial.print(" y="); Serial.print(ay, 4);
    Serial.print(" z="); Serial.println(az, 4);
    Serial.print("mag   x="); Serial.print(mx, 2);
    Serial.print(" y="); Serial.print(my, 2);
    Serial.print(" z="); Serial.println(mz, 2);

    int16_t rx, ry, rz;
    accel.readAccelerationRaw(rx, ry, rz);       // Read raw acceleration, (x, y, z) → counts
                                                 // signed counts at the current resolution
    Serial.print("raw accel "); Serial.print(rx);
    Serial.print(" "); Serial.print(ry);
    Serial.println(rz);

    accel.setRange(4);                           // Set full-scale range, (range_g=4) → bool
                                                 // rewrites FS in CTRL_REG4_A and updates the sensitivity
    accel.setDataRate(50);                       // Set output data rate, (rate_hz=50) → bool
                                                 // rewrites ODR in CTRL_REG1_A
    accel.setMode(LSM303Full::MODE_HIGH_RESOLUTION);  // Set operating mode, (mode=MODE_HIGH_RESOLUTION=2) → bool
                                                 // LPen/HR select low-power, normal or high-resolution
    accel.setAxes(true, true, true);             // Enable axes, (x=true, y=true, z=true) → None
                                                 // rewrites Xen, Yen and Zen
    delay(200);

    accel.powerDown();                           // Power down the accelerometer, () → None
                                                 // ODR = 0000, the register contents are retained
    accel.setDataRate(100);                      // Set output data rate, (rate_hz=100) → bool
                                                 // leaves power-down and starts the conversions again

    accel.setHighPass(0, 0, false, true, false, false);  // Configure high-pass filter, (mode=0, cutoff=0, filter_output=false, on_ia1=false, on_ia2=false, on_click=false) → bool
                                                 // rewrites HPM, HPCF, FDS and the per-generator enables
    Serial.print("reference "); Serial.println(accel.resetHighPass());  // Reset high-pass filter, () → byte
                                                 // reads REFERENCE_A, which resets the filter
    accel.setReference(0);                       // Set reference value, (value=0) → None
                                                 // the value the high-pass filter compares against

    accel.setFifo(LSM303Full::FIFO_STREAM, 16);  // Configure FIFO, (mode=FIFO_STREAM=2, watermark=0, trigger_int2=false) → bool
                                                 // stream mode with a 16-sample watermark
    Serial.print("fifo level "); Serial.println(accel.fifoLevel());  // Read FIFO level, () → byte
                                                 // number of unread sample sets
    bool watermark, overrun, empty;
    uint8_t level;
    accel.readFifoStatus(watermark, overrun, empty, level);  // Read FIFO status, (watermark, overrun, empty, level) → None
                                                 // decodes FIFO_SRC_REG_A
    Serial.print("fifo status wm="); Serial.print(watermark);
    Serial.print(" ov="); Serial.print(overrun);
    Serial.print(" empty="); Serial.print(empty);
    Serial.print(" level="); Serial.println(level);
    float samples[3 * 8];
    uint8_t count = accel.readFifo(samples, 8);  // Read FIFO, (out, max_count=8) → byte
                                                 // pops one sample set per six-byte burst read
    Serial.print("fifo samples "); Serial.println(count);

    accel.setMotionInterrupt(1, 0x02, 0.5f, 10.0f, false);  // Configure motion interrupt, (generator=1, events=0x02, threshold_g=0.5, duration_ms=10, and_mode=false) → bool
                                                 // writes INT1_CFG_A, INT1_THS_A and INT1_DURATION_A
    accel.setFreeFall(1, 0.35f, 30.0f);          // Arm free-fall, (generator=1, threshold_g=0.35, duration_ms=30) → bool
                                                 // all three axes low, combined with AND
    accel.setOrientation(1, false, false);       // Configure orientation, (generator=1, position=false, four_d=false) → bool
                                                 // 6D movement recognition on all three axes
    accel.setLatch(1, true);                     // Latch the interrupt, (generator=1, enabled=true) → bool
                                                 // the INT1 line stays asserted until the source is read
    Serial.print("int1 source 0x"); Serial.println(accel.readInterruptSource(1), HEX);  // Read interrupt source, (generator=1) → byte
                                                 // reads INT1_SOURCE_A, which clears the latched flag

    accel.setClick(0, 0x04, 0.8f, 20.0f, 40.0f, 200.0f);  // Configure click detector, (single=0, double=0x04, threshold_g=0.8, time_limit_ms=20, latency_ms=40, window_ms=200) → bool
                                                 // writes CLICK_CFG_A, CLICK_THS_A and the three time registers
    Serial.print("click source 0x"); Serial.println(accel.readClickSource(), HEX);  // Read click source, () → byte
                                                 // reads CLICK_SRC_A, which clears the latched flag

    accel.setInterruptPolarity(false);           // Set interrupt polarity, (active_low=false) → None
                                                 // H_LACTIVE in CTRL_REG6_A; false keeps the active-high default
    accel.enableInterrupt(LSM303Full::SOURCE_CLICK, 1);  // Route an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → bool
                                                 // only the routing is set up, the generator is configured separately
    accel.disableInterrupt(LSM303Full::SOURCE_CLICK, 1);  // Remove an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → bool
                                                 // clears the routing bit again

    accel.setMagRange(250);                        // Set magnetometer range, (range_ut=250) → bool
                                                 // rewrites GN in CRB_REG_M and updates the gains
    accel.setMagDataRate(30.0f);                   // Set magnetometer data rate, (rate_hz=30) → bool
                                                 // rewrites DO in CRA_REG_M, keeping TEMP_EN
    accel.setMagMode(LSM303Full::MAG_MODE_CONTINUOUS);  // Set magnetometer mode, (mode=MAG_MODE_CONTINUOUS=0) → bool
                                                 // continuous, single or sleep conversion
    accel.readMagneticRaw(rx, ry, rz);            // Read raw magnetic field, (x, y, z) → counts
                                                 // signed counts in X, Y, Z order
    Serial.print("raw mag "); Serial.print(rx);
    Serial.print(" "); Serial.print(ry);
    Serial.println(rz);
    bool drdy, lock;
    accel.readMagStatus(drdy, lock);              // Read magnetometer status, (drdy, lock) → None
                                                 // decodes SR_REG_M
    Serial.print("mag drdy="); Serial.print(drdy);
    Serial.print(" lock="); Serial.println(lock);
    uint8_t identity[3];
    accel.readMagIdentity(identity);              // Read magnetometer identity, (out) → None
                                                 // returns the three identity bytes
    Serial.print("identity 0x"); Serial.print(identity[0], HEX);
    Serial.print(" 0x"); Serial.print(identity[1], HEX);
    Serial.print(" 0x"); Serial.println(identity[2], HEX);
    accel.enableTemperature(true);                // Enable the temperature sensor, (enabled=true) → None
                                                 // sets TEMP_EN in CRA_REG_M
    Serial.print("temperature "); Serial.print(accel.readTemperatureDelta(), 2); Serial.println(" C");

    Serial.print("heading "); Serial.print(accel.heading(), 1); Serial.println(" deg");  // Read the compass heading, (tilt_compensate=true) → float °
                                                 // tilt-compensated with the accelerometer
    Serial.print("flat heading "); Serial.print(accel.heading(false), 1); Serial.println(" deg");  // Read the compass heading, (tilt_compensate=true) → float °
                                                 // the magnetic-only heading

    Serial.print("status 0x"); Serial.println(accel.readStatus(), HEX);  // Read status register, () → byte
                                                 // raw STATUS_REG_A byte
    Serial.print("new data "); Serial.println(accel.newDataAvailable() ? 1 : 0);  // Check new data, () → bool
                                                 // reads STATUS_REG_A.ZYXDA

    accel.reboot();                             // Reboot the accelerometer, () → None
                                                 // reloads the factory trim values and re-runs init()
}

void loop() {}
