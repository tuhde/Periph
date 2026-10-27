#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "LSM303.h"

static const char* const COMPASS16[16] = {
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
};

// 16-point compass name for a heading in degrees (0–360).
static const char* compassName(float deg) {
    int idx = static_cast<int>((deg + 11.25f) / 22.5f) % 16;
    if (idx < 0) idx += 16;
    return COMPASS16[idx];
}

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    // Accelerometer at 0x19 and magnetometer at 0x1E share the bus; each gets its own device handle.
    i2c_device_config_t accel_cfg = {};
    accel_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    accel_cfg.device_address = 0x19;
    accel_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t accelDev;
    i2c_master_bus_add_device(bus, &accel_cfg, &accelDev);

    i2c_device_config_t mag_cfg = {};
    mag_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    mag_cfg.device_address = 0x1E;
    mag_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t magDev;
    i2c_master_bus_add_device(bus, &mag_cfg, &magDev);

    I2CConnectionESPIDF accelConn(accelDev);                // Accelerometer connection at 0x19
    I2CConnectionESPIDF magConn(magDev);                    // Magnetometer connection at 0x1E

    LSM303Full chip(accelConn, magConn);                    // Create LSM303 full driver, (accel_conn, mag_conn) → sets ±2 g, 100 Hz, ±130 µT, 15 Hz

    // --- Configure for a tilt-compensated compass with tap and free-fall detection ---
    // ±2 g and 50 Hz keep the rate low enough for a steady average; high-resolution mode
    // gives 1 mg/LSB. The magnetometer runs at 15 Hz within ±1.3 gauss.
    chip.setRange(2);                                       // Set accel full scale, (range_g=2) → bool
    chip.setMode(2);                                        // Set accel mode, (mode=2) → bool
    chip.setDataRate(50);                                   // Set accel output rate, (rate_hz=50) → bool
    chip.setMagRange(130);                                  // Set mag full scale, (range_ut=130) → bool
    chip.setMagDataRate(15.0f);                             // Set mag output rate, (rate_hz=15) → bool

    // --- Arm the double-tap and free-fall detectors, both routed to INT1 ---
    // A double tap on Z (0x04) at 0.8 g with a 20 ms limit, 40 ms latency and 200 ms window.
    chip.setClick(0, 0x04, 0.8f, 20.0f, 40.0f, 200.0f);     // Configure click, (single=0, double=0x04, threshold_g=0.8, ...) → void
    // Free-fall: all three axes below 0.35 g for 60 ms, latched until the source is read.
    chip.setFreeFall(1, 0.35f, 60.0f);                      // Free-fall on generator 1, (generator=1, threshold_g=0.35, duration_ms=60) → bool
    chip.setLatch(1, true);                                 // Latch generator 1, (generator=1, enabled=true) → bool
    chip.enableInterrupt(LSM303Source::CLICK, 1);           // Route click to INT1, (source, pin=1) → bool
    chip.enableInterrupt(LSM303Source::IA1, 1);             // Route IA1 to INT1, (source, pin=1) → bool

    // --- Stream into the FIFO, 16-sample watermark ---
    chip.setFifo(2, 16, false);                             // Configure FIFO, (mode=2, watermark=16, trigger_int2=false) → bool

    const int SECONDS = 60;
    for (int s = 0; s < SECONDS; s++) {
        // --- Once a second: drain the FIFO, average, and read the magnetometer ---
        // Averaging the FIFO removes most per-sample noise before the tilt is computed.
        vTaskDelay(pdMS_TO_TICKS(1000));
        float xs[16], ys[16], zs[16];
        uint8_t n = chip.readFifo(xs, ys, zs, 16);          // Drain FIFO, (x, y, z, count=16) → uint8
        float ax = 0.0f, ay = 0.0f, az = 0.0f;
        if (n > 0) {
            for (uint8_t i = 0; i < n; i++) { ax += xs[i]; ay += ys[i]; az += zs[i]; }
            ax /= n; ay /= n; az /= n;
        } else {
            chip.readAcceleration(ax, ay, az);              // Read acceleration, (x, y, z) → g, g, g
        }

        float mx, my, mz;
        chip.readMagnetic(mx, my, mz);                      // Read magnetic field, (x, y, z) → µT, µT, µT
        float hdg = chip.heading(true);                     // Tilt-compensated heading, (tilt_compensate=true) → deg
        float dT = chip.readTemperatureDelta();             // Read temperature, () → float °C

        // Pitch and roll from the averaged gravity vector (degrees, display only).
        float roll  = atan2f(ay, az) * 180.0f / 3.14159265f;
        float pitch = atan2f(-ax, sqrtf(ay * ay + az * az)) * 180.0f / 3.14159265f;
        float fieldUt = sqrtf(mx * mx + my * my + mz * mz);

        printf("heading=%.1f deg %s  pitch=%.1f roll=%.1f  |B|=%.1f uT  temp_delta=%.2f C  samples=%u\n",
               hdg, compassName(hdg), pitch, roll, fieldUt, dT, (unsigned)n);

        // --- Poll for events and report them ---
        // Reading the source registers clears the latched flags, so each event prints once.
        uint8_t status = chip.pollInterrupt();              // Poll interrupt sources, () → uint8
        if (status & LSM303Source::CLICK) printf("DOUBLE TAP\n");
        if (status & LSM303Source::IA1)   printf("FREE FALL\n");
    }

    printf("done\n");
}
