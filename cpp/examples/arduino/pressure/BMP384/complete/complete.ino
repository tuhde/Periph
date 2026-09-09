#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/connection/I2CConnection.h"
#include "../../src/chips/pressure/BMP384.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, 0x76);
    BMP384Full bmp(connection);                             // Create BMP384 driver, (connection, spi=false)

    bmp.configure(4, 1, 2, 0x03);                          // Configure ADC and IIR filter, (osr_p 0–5, osr_t 0–5, iir_filter 0–7, odr_sel 0x00–0x11) → None
                                                            // sets oversampling, IIR coefficient, and output data rate
    bmp.set_mode(BMP384Full.MODE_NORMAL);                   // Set power mode, (mode 0/1/3) → None
    bool ready = bmp.is_data_ready();                       // Check data-ready flag, () → bool
                                                            // true if STATUS.drdy_press is set
    float t = bmp.temperature();                            // Read temperature, () → float °C
    float p = bmp.pressure();                               // Read pressure, () → float hPa
    float tp = 0.0f, tt = 0.0f;
    bmp.read(tp, tt);                                       // Read both values in one burst, (pressure_hpa, temperature_c) → None
    float fp = 0.0f, ft = 0.0f;
    bmp.read_forced(fp, ft);                                // Trigger forced measurement and read, (pressure_hpa, temperature_c) → None
    bmp.fifo_configure(true, true, 64);                     // Configure FIFO, (press_en bool, temp_en bool, wtm 0–511, stop_on_full=false) → None
                                                            // enables FIFO, sets watermark, arms pressure+temperature frames
    const char* types[16];
    double values[16];
    size_t n = bmp.fifo_read(types, values, 16);            // Read and parse FIFO frames, (types, values, max_frames) → size_t
    bmp.fifo_flush();                                       // Flush FIFO contents, () → None
    float alt = bmp.altitude();                             // Compute altitude, (sea_level_hpa=1013.25) → float m
    bmp.softreset();                                        // Soft reset chip, () → None

    check_true(t > -40.0f && t < 85.0f, "temperature_in_range");
    check_true(p > 300.0f && p < 1250.0f, "pressure_in_range");
    Serial.print("T="); Serial.print(t, 1);
    Serial.print(" C, P="); Serial.print(p, 1);
    Serial.print(" hPa, ready="); Serial.print(ready);
    Serial.print(", frames="); Serial.print(n);
    Serial.print(", alt="); Serial.print(alt, 1);
    Serial.println(" m");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
