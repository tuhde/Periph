#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <cmath>
#include "I2CConnectionLinux.h"
#include "LSM303.h"

// Edge callback for on_interrupt; fires (if an INT pin is wired) with the
// combined status mask from poll_interrupt().
static void on_int(uint16_t status) {
    printf("INT 0x%04X\n", status);
}

int main() {
    const char* bus_env = getenv("I2C_BUS");
    int bus = bus_env ? atoi(bus_env) : 1;
    I2CConnectionLinux accel_connection(bus, 0x19);
    I2CConnectionLinux mag_connection(bus, 0x1E);

    LSM303Full sensor(accel_connection, mag_connection);     // Create LSM303 Full driver, (accel_connection, mag_connection)
                                                             // verifies the magnetometer identity 0x483433, then
                                                             // sets ±2 g / 100 Hz / high-resolution and
                                                             // ±130 µT / 15 Hz / continuous conversion

    // --- Accelerometer configuration ---
    sensor.set_range(8);                                     // Set accelerometer range, (range_g) → None
                                                             // selects ±8 g; sensitivity updates to 4 mg/LSB (HR mode)
    sensor.set_data_rate(50);                                // Set accelerometer data rate, (rate_hz) → None
                                                             // 50 Hz; 1620/5376 Hz are low-power-only, 1344 Hz is not
    sensor.set_mode(2);                                      // Set operating mode, (mode) → None
                                                             // 2 = high-resolution (12-bit, 7/ODR turn-on time)
    sensor.set_axes(false, true, true);                      // Set enabled axes, (x, y, z) → None
                                                             // disables the X axis
    sensor.set_axes();                                       // Set enabled axes, (x=y=z) → None
                                                             // re-enables all three axes
    sensor.power_down();                                     // Enter power-down, () → None
                                                             // ODR = 0000; register contents retained
    sensor.set_data_rate(50);                                // Set accelerometer data rate, (rate_hz) → None
                                                             // leaves power-down again (turn-on time 7/ODR)

    // --- Raw data and status ---
    int16_t rax, ray, raz;
    sensor.read_acceleration_raw(rax, ray, raz);             // Read raw acceleration, (x, y, z) → counts, counts, counts
                                                             // signed counts at the current resolution
    int16_t rmx, rmy, rmz;
    sensor.read_magnetic_raw(rmx, rmy, rmz);                 // Read raw magnetic counts, (x, y, z) → counts, counts, counts
                                                             // X, Y, Z order after the X, Z, Y wire reorder
    uint8_t status = sensor.read_status();                   // Read status register, () → STATUS byte
                                                             // ZYXOR/ZOR/YOR/XOR overrun, ZYXDA/XDA/YDA/ZDA data-ready
    bool nda = sensor.new_data_available();                  // Check new data available, () → bool
                                                             // true while STATUS_REG_A.ZYXDA is set

    // --- High-pass filter ---
    sensor.set_high_pass(1, 2, true, false, true, false);    // Configure high-pass filter, (mode, cutoff, filter_output, on_ia1, on_ia2, on_click) → None
                                                             // HPM=01 (reference signal), HPCF code 2, filtered outputs,
                                                             // high-pass on interrupt generator 2
    sensor.set_reference(0x55);                              // Write reference register, (value) → None
                                                             // high-pass reference value for HPM = 01
    uint8_t ref = sensor.reset_high_pass();                  // Reset high-pass filter, () → reference byte
                                                             // reading REFERENCE_A resets the filter in HPM = 00
    sensor.set_high_pass();                                  // Configure high-pass filter, (mode=0, cutoff=0, …) → None
                                                             // back to bypass (HPM=00), unfiltered outputs

    // --- FIFO ---
    sensor.set_fifo(2, 16);                                  // Configure FIFO, (mode, watermark, trigger_int2) → None
                                                             // stream mode with a 16-sample watermark
    uint8_t fss = sensor.fifo_level();                       // Read FIFO fill level, () → sample sets
                                                             // FSS bits 4:0 (0–32 unread sample sets)
    bool wtm, ovrn, empty;
    uint8_t flevel;
    sensor.read_fifo_status(wtm, ovrn, empty, flevel);       // Read FIFO status, (watermark, overrun, empty, level) → bool, bool, bool, sample sets
                                                             // decoded FIFO_SRC_REG_A
    float fx[32], fy[32], fz[32];
    uint8_t popped = sensor.read_fifo(fx, fy, fz, 32);       // Drain FIFO, (x, y, z, max_samples) → floats³, count
                                                             // one 6-byte burst per sample set, converted to g
    sensor.set_fifo(0);                                      // Configure FIFO, (mode=0) → None
                                                             // bypass — only bypass mode empties the buffer

    // --- Inertial interrupt generators ---
    sensor.set_motion_interrupt(1, 0x05, 0.5, 0, false);     // Configure motion interrupt, (generator, events, threshold_g, duration_ms, and_mode) → None
                                                             // X-high | X-low above 0.5 g, OR combination, on generator 1
    sensor.set_motion_interrupt(2, 0x0A, 0.5, 0, true);      // Configure motion interrupt, (generator=2, events, threshold_g, duration_ms=0, and_mode=false) → None
                                                             // Y-high | Y-low, AND combination, on generator 2
    sensor.set_free_fall(1, 0.35, 30);                       // Configure free-fall, (generator, threshold_g, duration_ms) → None
                                                             // all three axes low, AND mode (CFG = 0x95)
    sensor.set_orientation(1, false, false);                 // Configure 6D orientation, (generator, position, four_d) → None
                                                             // 4D/6D movement recognition; writes D4D + 6D bits
    sensor.set_latch(1, true);                               // Set latch, (generator, enabled) → None
                                                             // INT1 stays asserted until INT1_SOURCE_A is read
    uint8_t src1 = sensor.read_interrupt_source(1);          // Read interrupt source, (generator) → source byte
                                                             // clears the generator-1 interrupt
    sensor.set_latch(1, false);                              // Set latch, (generator=1, enabled=false) → None
                                                             // unlatch again

    // --- Click detection ---
    sensor.set_click(1, 4, 0.5, 10, 20, 50);                 // Configure click detection, (single, double, threshold_g, time_limit_ms, latency_ms, window_ms) → None
                                                             // single-tap on X, double-tap on Z, thresholds in g, times in ms
    uint8_t click_src = sensor.read_click_source();          // Read click source, () → source byte
                                                             // clears the click interrupt

    // --- INT polarity, routing, subscription ---
    sensor.set_interrupt_polarity(false);                    // Set INT polarity, (active_low) → None
                                                             // INT1/INT2 active-high (default)
    sensor.enable_interrupt(LSM303Full::SOURCE_IA1, 1);      // Route interrupt, (source, pin) → None
                                                             // generator-1 events → INT1 (boot routed only to INT2, mag DRDY not routable)
    sensor.enable_interrupt(LSM303Full::SOURCE_BOOT, 2);     // Route interrupt, (source=BOOT, pin=2) → None
                                                             // boot-running indication → INT2
    sensor.on_interrupt(on_int, 1);                          // Subscribe to INT edge, (callback, pin=1) → None
                                                             // uses connection.intPin(); no delivery without a wired pin
    sensor.off_interrupt(1);                                 // Unsubscribe, (pin=1) → None
    sensor.enable_interrupt(LSM303Full::SOURCE_MAG_DATA_READY, 1);  // never routed: DRDY is not routable
    uint16_t poll = sensor.poll_interrupt();                 // Poll all sources, () → status mask
                                                             // combined poll; reads clear latched flag registers
    sensor.disable_interrupt(LSM303Full::SOURCE_IA1, 1);     // Remove interrupt source, (source, pin) → None
    sensor.disable_interrupt(LSM303Full::SOURCE_BOOT, 2);    // Remove interrupt source, (source=BOOT, pin=2) → None

    // --- Magnetometer configuration ---
    sensor.set_mag_range(810);                               // Set magnetometer range, (range_ut) → None
                                                             // ±810 µT (±8.1 gauss, GN=111); gains update
    sensor.set_mag_range(130);                               // Set magnetometer range, (range_ut=130) → None
                                                             // back to ±130 µT (±1.3 gauss)
    sensor.set_mag_data_rate(30);                            // Set magnetometer data rate, (rate_hz) → None
                                                             // 30 Hz; keeps the TEMP_EN bit
    sensor.set_mag_data_rate(15);                            // Set magnetometer data rate, (rate_hz=15) → None
    sensor.set_mag_mode(1);                                  // Set magnetometer mode, (mode) → None
                                                             // 1 = single conversion (one shot, then sleep)
    sensor.set_mag_mode(0);                                  // Set magnetometer mode, (mode=0) → None
                                                             // 0 = continuous conversion
    bool mdrdy = sensor.mag_data_ready();                    // Check magnetometer data ready, () → bool
                                                             // true while SR_REG_M.DRDY is set
    bool mdy_drdy, mdy_lock;
    sensor.read_mag_status(mdy_drdy, mdy_lock);              // Read magnetometer status, (drdy, lock) → bool, bool
                                                             // decoded SR_REG_M
    uint8_t ida, idb, idc;
    sensor.read_mag_identity(ida, idb, idc);                 // Read magnetometer identity, (ida, idb, idc) → byte, byte, byte
                                                             // IRA/IRB/IRC = 0x48 0x34 0x33 ("H43", same as HMC5883L)

    // --- Relative temperature sensor ---
    sensor.enable_temperature(true);                         // Enable temperature sensor, (enabled) → None
                                                             // sets CRA_TEMP_EN; updates at the magnetometer rate
    sensor.enable_temperature(false);                        // Enable temperature sensor, (enabled=false) → None
    float temp = sensor.read_temperature_delta();            // Read relative temperature, () → °C
                                                             // enables TEMP_EN on first use; 8 LSB/°C, relative only

    // --- Software compass ---
    float hdg = sensor.heading(true);                        // Compute compass heading, (tilt_compensate) → degrees
                                                             // tilt-compensated atan2(y, x), wrapped to 0–360°
    float hdg_flat = sensor.heading(false);                  // Compute compass heading, (tilt_compensate=false) → degrees
                                                             // flat compass from magnetic X/Y only

    // --- Memory reboot (last: re-init required afterwards) ---
    sensor.reboot();                                         // Reboot accelerometer memory, () → None
                                                             // BOOT=1 reloads the factory trim; waits 5 ms;
                                                             // the datasheet is silent on register survival, so
                                                             // construct a fresh driver afterwards

    printf("raw a=(%d %d %d) m=(%d %d %d) status=0x%02X nda=%d ref=0x%02X\n",
           (int)rax, (int)ray, (int)raz, (int)rmx, (int)rmy, (int)rmz,
           status, (int)nda, ref);
    printf("fifo fss=%u (wtm=%d ovrn=%d empty=%d lvl=%u) popped=%u src1=0x%02X click=0x%02X\n",
           fss, (int)wtm, (int)ovrn, (int)empty, flevel, popped, src1, click_src);
    printf("poll=0x%04X mdrdy=%d status=(%d,%d) id=%02X%02X%02X temp=%.1f hdg=%.1f flat=%.1f\n",
           poll, (int)mdrdy, (int)mdy_drdy, (int)mdy_lock, ida, idb, idc,
           (double)temp, (double)hdg, (double)hdg_flat);
    return 0;
}
