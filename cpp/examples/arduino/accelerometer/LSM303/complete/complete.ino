#include <Wire.h>
#include <Periph.h>

I2CConnection accelConn(Wire, 0x19);                        // Accelerometer connection at 0x19
I2CConnection magConn(Wire, 0x1E);                          // Magnetometer connection at 0x1E

// Interrupt callback: receives the poll status byte. Not called in this example (no INT pin wired).
void onInt(uint8_t status) {
    Serial.print("int status=0x"); Serial.println(status, HEX);
}

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(21, 22, 400000);                             // SDA=GPIO21, SCL=GPIO22
#else
    Wire.begin();
    Wire.setClock(400000);
#endif

    LSM303Full chip(accelConn, magConn);                    // Create LSM303 full driver, (accel_conn, mag_conn) → sets ±2 g, 100 Hz, ±130 µT, 15 Hz
                                                            // identity check runs here; valid() reports the result

    Serial.print("valid="); Serial.println(chip.valid() ? 1 : 0);
    uint8_t id[3];
    chip.readMagIdentity(id);                               // Read magnetometer identity, (buf) → void
                                                            // copies IRA/IRB/IRC (0x48 0x34 0x33 on a genuine part)

    // --- Accelerometer configuration ---
    chip.setRange(4);                                       // Set accel full scale, (range_g=4) → bool
                                                            // selects ±4 g; updates the cached 2 mg/LSB sensitivity
    chip.setMode(2);                                        // Set accel mode, (mode=2) → bool
                                                            // high-resolution (12-bit): LPen=0, HR=1
    chip.setDataRate(200);                                  // Set accel output rate, (rate_hz=200) → bool
                                                            // 200 Hz is legal in high-resolution mode
    chip.setAxes(true, true, true);                         // Enable axes, (x=True, y=True, z=True) → void
                                                            // Xen, Yen and Zen all set
    chip.setHighPass(0, 0, false, false, false, false);     // Configure high-pass, (mode=0, cutoff=0, filter_output=False, ...) → void
                                                            // filter bypassed on output and all generators
    chip.setReference(0);                                   // Set high-pass reference, (value=0) → void
                                                            // REFERENCE_A value used only in HPM=01
    uint8_t ref = chip.resetHighPass();                     // Reset high-pass filter, () → uint8
                                                            // reading REFERENCE_A resets the filter in HPM=00
    (void)ref;
    chip.setFifo(2, 16, false);                             // Configure FIFO, (mode=2, watermark=16, trigger_int2=False) → bool
                                                            // stream mode, watermark 16 samples, trigger on INT1

    // --- Interrupts ---
    chip.setFreeFall(1, 0.35f, 30.0f);                      // Free-fall on generator 1, (generator=1, threshold_g=0.35, duration_ms=30) → bool
                                                            // all three axes below threshold, AND mode
    chip.setOrientation(2, false, false);                   // 6D movement on generator 2, (generator=2, position=False, four_d=False) → bool
                                                            // reports orientation changes between zones
    chip.setLatch(1, true);                                 // Latch generator 1, (generator=1, enabled=True) → bool
                                                            // holds IA1 until INT1_SOURCE_A is read
    chip.setClick(0x05, 0x02, 0.5f, 10.0f, 20.0f, 50.0f);   // Configure click, (single=0x05, double=0x02, threshold_g=0.5, ...) → void
                                                            // single on X and Z, double on Y
    chip.setInterruptPolarity(false);                       // Set INT polarity, (active_low=False) → void
                                                            // INT1 and INT2 active-high
    chip.enableInterrupt(LSM303Source::IA1, 1);             // Route IA1 to INT1, (source, pin=1) → bool
                                                            // generator 1 event appears on INT1
    chip.enableInterrupt(LSM303Source::CLICK, 1);           // Route click to INT1, (source, pin=1) → bool
                                                            // single/double click appears on INT1
    chip.disableInterrupt(LSM303Source::CLICK, 1);          // Remove click from INT1, (source, pin=1) → bool
                                                            // click no longer routed to INT1
    bool subscribed = chip.onInterrupt(onInt, 1);           // Subscribe to INT1, (callback, pin=1, int_pin=None) → bool
                                                            // returns false here: no InputPin is wired to accelConn
    (void)subscribed;
    chip.offInterrupt(1);                                   // Unsubscribe from INT1, (pin=1) → void
                                                            // removes this driver's handler, if any

    // --- Magnetometer configuration ---
    chip.setMagRange(190);                                  // Set mag full scale, (range_ut=190) → bool
                                                            // ±1.9 gauss; updates the cached gains
    chip.setMagDataRate(30.0f);                             // Set mag output rate, (rate_hz=30) → bool
                                                            // DO=101; TEMP_EN is kept
    chip.setMagMode(0);                                     // Set mag mode, (mode=0) → bool
                                                            // continuous conversion
    chip.enableTemperature(true);                           // Enable temperature, (enabled=True) → void
                                                            // sets TEMP_EN in CRA_REG_M

    // --- Read everything once ---
    delay(300);                                             // Let the sensors produce samples (7/ODR and 1/30 Hz)
    float ax, ay, az;
    chip.readAcceleration(ax, ay, az);                      // Read acceleration, (x, y, z) → g, g, g
                                                            // six-byte burst with auto-increment, converted to g
    int16_t rx, ry, rz;
    chip.readAccelerationRaw(rx, ry, rz);                   // Read raw acceleration, (x, y, z) → counts
                                                            // shifted to the current resolution (12-bit in HR)
    bool fresh = chip.newDataAvailable();                   // Check new accel data, () → bool
                                                            // STATUS_REG_A.ZYXDA
    uint8_t st = chip.readStatus();                         // Read accel status, () → uint8
                                                            // raw STATUS_REG_A byte
    bool wm, ov, em;
    uint8_t lvl;
    chip.readFifoStatus(wm, ov, em, lvl);                   // Decode FIFO status, (watermark, overrun, empty, level) → void
                                                            // FIFO_SRC_REG_A fields
    uint8_t fl = chip.fifoLevel();                          // Read FIFO level, () → uint8
                                                            // unread sample sets, 0–32
    float xs[8], ys[8], zs[8];
    uint8_t got = chip.readFifo(xs, ys, zs, 8);             // Drain FIFO, (x, y, z, count=8) → uint8
                                                            // up to 8 sample sets, in g
    uint8_t src1 = chip.readInterruptSource(1);             // Read INT1 source, (generator=1) → uint8
                                                            // reading clears IA1
    uint8_t csrc = chip.readClickSource();                  // Read click source, () → uint8
                                                            // reading clears the click flag
    float mx, my, mz;
    chip.readMagnetic(mx, my, mz);                          // Read magnetic field, (x, y, z) → µT, µT, µT
                                                            // reordered from X, Z, Y on the wire
    int16_t mrx, mry, mrz;
    chip.readMagneticRaw(mrx, mry, mrz);                    // Read raw magnetic field, (x, y, z) → counts
                                                            // signed counts in X, Y, Z order
    bool mdr = chip.magDataReady();                         // Check new mag data, () → bool
                                                            // SR_REG_M.DRDY
    bool drdy, lock;
    chip.readMagStatus(drdy, lock);                         // Decode mag status, (drdy, lock) → void
                                                            // SR_REG_M fields
    float dT = chip.readTemperatureDelta();                 // Read temperature, () → float °C
                                                            // relative delta at 8 LSB/°C, no absolute reference
    float hdg = chip.heading(true);                         // Tilt-compensated heading, (tilt_compensate=True) → deg
                                                            // atan2 of the tilt-corrected field, 0–360
    uint8_t poll = chip.pollInterrupt();                    // Poll interrupt sources, () → uint8
                                                            // combined status of every routed source

    Serial.print("a="); Serial.print(ax, 3); Serial.print(" ");
    Serial.print(ay, 3); Serial.print(" "); Serial.print(az, 3); Serial.println(" g");
    Serial.print("raw="); Serial.print(rx); Serial.print(" ");
    Serial.print(ry); Serial.print(" "); Serial.println(rz);
    Serial.print("fresh="); Serial.print(fresh ? 1 : 0);
    Serial.print(" status=0x"); Serial.print(st, HEX);
    Serial.print(" fifo_level="); Serial.print(fl);
    Serial.print(" got="); Serial.println(got);
    Serial.print("wm="); Serial.print(wm ? 1 : 0);
    Serial.print(" ov="); Serial.print(ov ? 1 : 0);
    Serial.print(" em="); Serial.print(em ? 1 : 0);
    Serial.print(" lvl="); Serial.println(lvl);
    Serial.print("src1=0x"); Serial.print(src1, HEX);
    Serial.print(" click=0x"); Serial.println(csrc, HEX);
    Serial.print("m="); Serial.print(mx, 1); Serial.print(" ");
    Serial.print(my, 1); Serial.print(" "); Serial.print(mz, 1); Serial.println(" uT");
    Serial.print("mraw="); Serial.print(mrx); Serial.print(" ");
    Serial.print(mry); Serial.print(" "); Serial.println(mrz);
    Serial.print("mdr="); Serial.print(mdr ? 1 : 0);
    Serial.print(" drdy="); Serial.print(drdy ? 1 : 0);
    Serial.print(" lock="); Serial.println(lock ? 1 : 0);
    Serial.print("temp_delta="); Serial.print(dT, 2); Serial.println(" C");
    Serial.print("heading="); Serial.print(hdg, 1); Serial.println(" deg");
    Serial.print("poll=0x"); Serial.println(poll, HEX);

    // --- Power management and maintenance ---
    chip.setMagMode(2);                                     // Set mag mode, (mode=2) → bool
                                                            // sleep mode to save power
    chip.powerDown();                                       // Power down accelerometer, () → void
                                                            // ODR=0; register contents are kept
    chip.reboot();                                          // Reboot accelerometer memory, () → void
                                                            // BOOT bit, wait 5 ms, then re-run the init sequence
}

void loop() {}
