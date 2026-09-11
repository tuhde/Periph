#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../../src/connection/I2CConnection.h"
#include "../../../src/chips/pressure/BMP581.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, 0x46);
    BMP581Full bmp(connection);                           // Create BMP581 driver, (connection, spi=false)
    uint8_t cid = bmp.chip_id();                         // Read chip ID, () → int
                                                        // returns 0x50 for BMP581
    check_true(cid == 0x50, "chip_id");

    bmp.configure(0x1C, BMP581Full::OSR_1X, BMP581Full::OSR_1X, true);  // Configure chip, (odr 0x00–0x1F, osr_p 0–7, osr_t 0–7, press_en) → None
                                                        // writes OSR_CONFIG and ODR_CONFIG atomically
    bmp.set_mode(BMP581Full::MODE_NORMAL);               // Set power mode, (mode 0/1/2/3) → None
                                                        // MODE_NORMAL: ODR-based duty-cycled autonomous mode
    bmp.set_iir_filter(BMP581Full::IIR_COEFF_3, BMP581Full::IIR_BYPASS);  // Set IIR filter, (coeff_p 0–7, coeff_t 0–7) → None
                                                        // suppresses short-term pressure disturbances on the data registers
    bmp.configure_fifo(BMP581Full::FIFO_BOTH, BMP581Full::FIFO_STREAM, 8);  // Configure FIFO, (frame_sel 0–3, mode 0/1, threshold 0–31) → None
                                                        # store 8-frame batches of pressure+temperature samples
    uint8_t n = bmp.fifo_count();                        // Read FIFO frame count, () → int
                                                        // returns number of frames currently buffered
    bmp.enable_drdy_interrupt(true);                     // Enable data-ready interrupt, (enable bool) → None
    bmp.data_ready();                                     // Check data ready, () → bool
                                                        // reads INT_STATUS (clear-on-read) and returns the drdy bit
    float p_f, p;
    bmp.forced(p_f, p);                                  // Trigger FORCED measurement, (out p_pa, out t_c) → None
                                                        // forces a single conversion and waits for completion
    float p_pa, t_c;
    bmp.both(p_pa, t_c);                                 // Read both atomically, (out p_pa, out t_c) → None
                                                        // single burst read of TEMP_XLSB..PRESS_MSB
    float alt = bmp.altitude();                          // Compute altitude, (sea_level_pa=101325.0) → float m
                                                        // barometric formula against the reference pressure
    uint8_t st = bmp.status();                           // Read STATUS, () → int
                                                        // raw status byte; bit 1 = nvm_rdy, bit 2 = nvm_err
    uint8_t ist = bmp.interrupt_status();                // Read INT_STATUS, () → int
                                                        // clear-on-read; returns raw byte
    uint8_t op, ot;
    bmp.effective_osr(op, ot);                           // Read effective OSR, (out osr_p 0–7, out osr_t 0–7) → None
                                                        // actual OSR in use after any auto-reduction
    bmp.set_oor_threshold(110000.0f, 200.0f, 1);         // Set OOR threshold, (threshold_pa, range_pa, count_limit 0–3) → None
                                                        // fires INT_STATUS.oor_p when pressure leaves threshold +/- range_pa
    bmp.software_reset();                                // Soft reset chip, () → None
                                                        // issues CMD=0xB6 and re-runs the init sequence

    Serial.print("P=");
    Serial.print(p_pa, 1);
    Serial.print(" Pa, T=");
    Serial.print(t_c, 2);
    Serial.print(" C, alt=");
    Serial.print(alt, 1);
    Serial.print(" m, frames=");
    Serial.println(n);

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }