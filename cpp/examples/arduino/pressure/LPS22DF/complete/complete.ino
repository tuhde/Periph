#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/connection/I2CConnection.h"
#include "../../src/chips/pressure/LPS22DF.h"

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, 0x5C);
    LPS22DFFull lps(connection);                           // Create LPS22DF driver, (connection, spi=false)

    lps.configure(3, 0, false, 0, true);                   // Configure chip, (odr=3 [10 Hz], avg=0 [4], en_lpfp=false, lfpf_cfg=0, bdu=true) → None
                                                              // writes CTRL_REG1 and CTRL_REG2
    lps.oneshot();                                          // Trigger one-shot conversion, () → None
                                                              // sets power-down then ONESHOT=1, waits for data
    float p = lps.pressure();                               // Read pressure, () → float Pa
                                                              // 24-bit two's complement, 4096 LSB/hPa → Pa
    float t = lps.temperature();                            // Read temperature, () → float °C
                                                              // 16-bit two's complement, 100 LSB/°C
    float alt = lps.altitude(101325.0);                     // Compute altitude, (sea_level_pa=101325.0) → float m
                                                              // uses barometric formula to convert pressure to metres
    lps.software_reset();                                   // Reset chip, () → None
                                                              // self-clears SWRESET bit after <5 µs
    lps.set_pressure_offset(-50.0);                         // Set pressure offset, (offset_pa=-50.0) → None
                                                              // one-point calibration in pascals; persists in NVM
    lps.set_pressure_threshold(102000.0);                   // Set pressure threshold, (threshold_pa=102000.0) → None
                                                              // 15-bit unsigned; raises INT when pressure exceeds it
    lps.configure_interrupt(false, false, true, false, true, false, false, false);  // Configure interrupt, (int_h_l=false, pp_od=false, drdy=true, drdy_pls=false, int_en=true, int_f_wtm=false, int_f_full=false, int_f_ovr=false) → None
                                                              // routes DRDY + pressure-threshold events to INT pin
    lps.configure_pressure_event(true, false, false);      // Configure pressure event, (phe=true, ple=false, lir=false) → None
                                                              // arms high-event pressure interrupt
    lps.autozero();                                         // Capture AUTOZERO reference, () → None
                                                              // current pressure becomes the zero reference
    lps.reset_reference();                                  // Reset reference, () → None
                                                              // clears AUTOZERO/AUTOREFP and REF_P registers
    float ref = lps.reference_pressure();                   // Read reference pressure, () → float Pa
    lps.set_fifo_mode(LPS22DFFull::FIFO_FIFO);              // Set FIFO mode, (mode 0–5) → None
                                                              // selects FIFO mode; pass 0 first when switching
    lps.set_fifo_watermark(64);                             // Set FIFO watermark, (level 0–127) → None
                                                              // raises INT when 64 samples are buffered
    uint8_t count = lps.fifo_sample_count();                // Read FIFO sample count, () → int
    float samples[128];
    uint8_t n_read = lps.read_fifo(samples, 128);           // Read FIFO samples, (out_buf, max_samples) → int
                                                              // burst-reads up to 128 pressure samples in pascals
    uint8_t src = lps.interrupt_source();                   // Read interrupt source, () → int
                                                              // bit fields: 0x80 BOOT_ON, 0x04 IA, 0x02 PL, 0x01 PH
    Serial.print("T="); Serial.print(t, 2);
    Serial.print(" C, P="); Serial.print(p, 0);
    Serial.print(" Pa, alt="); Serial.print(alt, 1);
    Serial.print(" m, ref="); Serial.print(ref, 0);
    Serial.print(" Pa, fifo="); Serial.print(n_read);
    Serial.print("/", (int)count);
    Serial.print(", src=0x"); Serial.println(src, HEX);
    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }