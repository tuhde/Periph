#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x76
#endif

#include <cstdio>
#include "I2CConnectionLinux.h"
#include "BMP384.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);
    BMP384Minimal bmp(connection, /*spi=*/false);

    // Inject representative PAR coefficients to verify the compensation math
    // without waiting for a real conversion.
    bmp._par_t1 = 1.0e6;
    bmp._par_t2 = 1.0e-3;
    bmp._par_t3 = 1.0e-6;
    bmp._par_p1 = 0.5;
    bmp._par_p2 = -0.1;
    bmp._par_p3 = 1.0e-6;
    bmp._par_p4 = 1.0e-7;
    bmp._par_p5 = 1000.0;
    bmp._par_p6 = 1.0e-3;
    bmp._par_p7 = 1.0e-5;
    bmp._par_p8 = 1.0e-7;
    bmp._par_p9 = 1.0e-12;
    bmp._par_p10 = 1.0e-12;
    bmp._par_p11 = 1.0e-20;

    bmp._t_lin = 25.0;
    double comp_p = bmp._compensate_pressure(415148);
    check_true(comp_p > 0.0, "pressure_compensation_runs");

    BMP384Full bmp_full(connection, /*spi=*/false);

    bmp_full.configure(2, 1, 1, 0x04);                     // Configure ADC and IIR filter, (osr_p 0–5, osr_t 0–5, iir_filter 0–7, odr_sel 0x00–0x11) → None
    check_true(bmp_full._osr_p == 2 && bmp_full._iir == 1 && bmp_full._odr == 0x04, "configure_writes_through");

    bmp_full.set_mode(BMP384Full.MODE_FORCED);             // Set power mode, (mode 0/1/3) → None
    check_true(bmp_full._mode == BMP384Full.MODE_FORCED, "set_mode_forced");

    bmp_full.fifo_configure(true, true, 10);               // Configure FIFO, (press_en bool, temp_en bool, wtm 0–511, stop_on_full=false) → None
    const char* types[16];
    double values[16];
    size_t n = bmp_full.fifo_read(types, values, 16);      // Read and parse FIFO frames, (types, values, max_frames) → size_t
    check_true(n <= 16, "fifo_read_no_overflow");

    bmp_full.fifo_flush();                                 // Flush FIFO contents, () → None
    float alt = bmp_full.altitude(1013.25f);               // Compute altitude, (sea_level_hpa=1013.25) → float m
    check_true(alt > -500.0f && alt < 9000.0f, "altitude_in_range");
    bmp_full.softreset();                                  // Soft reset chip, () → None

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
