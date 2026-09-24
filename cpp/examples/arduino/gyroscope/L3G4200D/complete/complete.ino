#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();                                    // other cores: board's default SDA/SCL
    Wire.setClock(400000);
#endif
    I2CConnection connection(Wire, 0x68);
    L3G4200DFull gyro(connection);                            // Create L3G4200D driver, (connection, spi=false)
    uint8_t cid = gyro.who_am_i();                            // Read WHO_AM_I, () → int
                                                               // returns 0xD3 for L3G4200D
    gyro.configure(1, 0, 500);                                // Configure chip, (odr=1 [200 Hz], bandwidth=0, full_scale=500) → None
                                                               // sets CTRL_REG1 DR/BW and CTRL_REG4 FS
    gyro.enable_axes(true, true, true);                      // Enable axes, (x, y, z) → None
                                                               // sets Xen/Yen/Zen in CTRL_REG1
    gyro.set_full_scale(2000);                               // Set full scale, (full_scale 250/500/2000) → None
                                                               // updates FS[1:0] in CTRL_REG4
    bool ready = gyro.data_ready();                          // Check data ready, () → bool
                                                               // returns STATUS_REG.ZYXDA
    uint8_t status = gyro.status();                          // Read STATUS, () → int
                                                               // raw status byte (ZYXOR, ZOR, YOR, XOR, ZYXDA, ZDA, YDA, XDA)
    int8_t temp = gyro.temperature();                        // Read temperature, () → int
                                                               // 8-bit signed relative count (−1 °C/digit)
    gyro.enable_highpass(0, 4);                              // Enable high-pass, (mode 0–3, cutoff 0–9) → None
                                                               // sets HPen and HPM/HPCF; cutoff depends on ODR
    gyro.disable_highpass();                                 // Disable high-pass, () → None
                                                               // clears HPen in CTRL_REG5
    gyro.set_interrupt(true, false, true, false, true, false, false, true);  // Configure INT1, (x_high, x_low, y_high, y_low, z_high, z_low, and_mode, latch) → None
                                                               // enable high events on x/y/z; latch until INT1_SRC read
    gyro.set_threshold('x', 87.5);                           // Set X threshold, (axis 'x'/'y'/'z', threshold_dps) → None
                                                               // converts dps to raw 15-bit value via sensitivity
    gyro.set_duration(4, false);                             // Set INT1 duration, (samples 0–127, wait=false) → None
                                                               // INT1 must be true for `samples` ODR cycles before firing
    gyro.set_data_ready_pin(true);                           // Route DRDY to INT2, (enable=true) → None
                                                               // sets I2_DRDY in CTRL_REG3
    gyro.enable_fifo(2, 10);                                 // Enable FIFO, (mode 2=stream, watermark=10) → None
    gyro.disable_fifo();                                     // Disable FIFO, () → None
                                                               // bypass mode and clear FIFO_EN
    uint8_t samples = gyro.fifo_samples();                   // Read FIFO count, () → int
                                                               // FSS[4:0] from FIFO_SRC_REG
    gyro.power_down();                                       // Enter power-down, () → None
                                                               // clears PD in CTRL_REG1
    gyro.wake_up();                                          // Wake from power-down, () → None
                                                               // sets PD; previously enabled axes restored
    gyro.sleep();                                            // Enter sleep mode, () → None
                                                               // PD=1, all axes off
    uint8_t int_src = gyro.read_int_source();                // Read & clear INT1_SRC, () → int
                                                               // reading clears the interrupt-active bit
    float x, y, z;
    gyro.angular_rate(x, y, z);                              // Read X/Y/Z angular rate, () → (float, float, float) rad/s
    Serial.print("X="); Serial.print(x, 2);
    Serial.print(" Y="); Serial.print(y, 2);
    Serial.print(" Z="); Serial.print(z, 2);
    Serial.print(" rad/s, T="); Serial.print(temp);
    Serial.print(", ready="); Serial.print(ready);
    Serial.print(", status=0x"); Serial.print(status, HEX);
    Serial.print(", fifo="); Serial.print(samples);
    Serial.print(", src=0x"); Serial.print(int_src, HEX);
    Serial.print(", cid=0x"); Serial.println(cid, HEX);
    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
