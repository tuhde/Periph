#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "BMP581.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x46;
    I2CConnectionLinux connection(bus, addr);

    BMP581Full bmp(connection);                                             // Create BMP581 driver, (connection, spi=false)

    uint8_t cid = bmp.chip_id();                                           // Read chip ID, () → uint8_t
                                                                            // returns 0x50 for a genuine BMP581
    printf("chip_id=0x%02X\n", cid);

    bmp.configure(0x1C, BMP581Full::OSR_1X, BMP581Full::OSR_1X, true);      // Configure chip, (odr 0x00–0x1F, osr_p 0–7, osr_t 0–7, press_en) → void
                                                                            // writes OSR_CONFIG and ODR_CONFIG atomically
    bmp.set_mode(BMP581Full::MODE_NORMAL);                                  // Set power mode, (mode 0/1/2/3) → void
                                                                            // MODE_NORMAL: ODR-based duty-cycled autonomous mode
    bmp.set_iir_filter(BMP581Full::IIR_COEFF_3, BMP581Full::IIR_BYPASS);    // Set IIR filter, (coeff_p 0–7, coeff_t 0–7) → void
                                                                            // suppresses short-term pressure disturbances on the data registers
    bmp.configure_fifo(BMP581Full::FIFO_BOTH, BMP581Full::FIFO_STREAM, 8);  // Configure FIFO, (frame_sel 0–3, mode 0/1, threshold 0–31) → void
                                                                            // stores 8-frame batches of pressure+temperature samples
    uint8_t n = bmp.fifo_count();                                          // Read FIFO frame count, () → uint8_t
                                                                            // returns number of frames currently buffered
    bmp.enable_drdy_interrupt(true);                                       // Enable data-ready interrupt, (enable bool) → void
    bmp.data_ready();                                                      // Check data ready, () → bool
                                                                            // reads INT_STATUS (clear-on-read) and returns the drdy bit
    float p_f, t_f;
    bmp.forced(p_f, t_f);                                                  // Trigger FORCED measurement, (out p_pa, out t_c) → void
                                                                            // forces a single conversion and waits for completion
    float p_pa, t_c;
    bmp.both(p_pa, t_c);                                                   // Read both atomically, (out p_pa, out t_c) → void
                                                                            // single burst read of TEMP_XLSB..PRESS_MSB
    float alt = bmp.altitude();                                            // Compute altitude, (sea_level_pa=101325.0) → float m
                                                                            // barometric formula against the reference pressure
    uint8_t st = bmp.status();                                             // Read STATUS, () → uint8_t
                                                                            // raw status byte; bit 1 = nvm_rdy, bit 2 = nvm_err
    uint8_t ist = bmp.interrupt_status();                                  // Read INT_STATUS, () → uint8_t
                                                                            // clear-on-read; returns raw byte
    uint8_t op, ot;
    bmp.effective_osr(op, ot);                                             // Read effective OSR, (out osr_p 0–7, out osr_t 0–7) → void
                                                                            // actual OSR in use after any auto-reduction
    bmp.set_oor_threshold(110000.0f, 200.0f, 1);                           // Set OOR threshold, (threshold_pa, range_pa, count_limit 0–3) → void
                                                                            // fires INT_STATUS.oor_p when pressure leaves threshold +/- range_pa
    bmp.software_reset();                                                  // Soft reset chip, () → void
                                                                            // issues CMD=0xB6 and re-runs the init sequence

    printf("P=%.1f Pa, T=%.2f C, alt=%.1f m, frames=%u, drdy=%d, st=0x%02X, ist=0x%02X, eff=(%u, %u)\n",
        p_pa, t_c, alt, n, bmp.data_ready(), st, ist, op, ot);
    return 0;
}
