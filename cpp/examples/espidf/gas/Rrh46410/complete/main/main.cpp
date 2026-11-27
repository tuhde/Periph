#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Rrh46410.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define RRH46410_ADDR 0x38

static void print_result(const RRH46410Result& r) {
    unsigned long tvoc = (unsigned long)(r.tvoc_mg_m3 * 1000.0f + 0.5f);
    unsigned long etoh = (unsigned long)(r.etoh_ppm * 1000.0f + 0.5f);
    unsigned iaq = (unsigned)(r.iaq * 10.0f + 0.5f);
    printf("sample %u  IAQ %u.%u  TVOC %lu.%03lu mg/m3  EtOH %lu.%03lu ppm  eCO2 %u ppm  rel %u  %s\n",
          (unsigned)r.sample, iaq / 10, iaq % 10, tvoc / 1000, tvoc % 1000, etoh / 1000, etoh % 1000,
          (unsigned)r.eco2_ppm, (unsigned)r.relative_iaq, r.warmed_up ? "ok" : "warming up");
}

static volatile bool g_sample_ready = false;
static void on_sample() { g_sample_ready = true; }

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(I2C_SDA);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(I2C_SCL);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = RRH46410_ADDR;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    RRH46410Full sensor(connection);                               // Create RRH46410 driver, (connection) → void
    // The module has the fixed address 0x38 and stretches the I²C clock.
    sensor.init();                                                 // Check product ID, start IAQ 2nd Gen mode, () → bool
    // Retries Get Product ID for up to 1 s, verifies 0x2310, selects IAQ 2nd Gen (3 s samples).

    uint16_t pid = 0;
    uint8_t fw[3] = {0};
    sensor.product_id(pid, fw);                                    // Product ID and firmware, (productId&, firmware[3]) → bool
    // The product ID is always 0x2310.
    printf("product 0x%04X firmware %u.%u.%u\n", (unsigned)pid, fw[0], fw[1], fw[2]);
    uint8_t tn[6];
    sensor.tracking_number(tn);                                    // ASIC tracking number, (out[6]) → bool
    // Unique per module.
    printf("tracking %02X%02X%02X%02X%02X%02X\n", tn[0], tn[1], tn[2], tn[3], tn[4], tn[5]);
    printf("mode %d\n", sensor.get_mode());                        // Operation mode, () → int
    // 1 = IAQ 2nd Gen after init.

    sensor.set_humidity(45.0f);                                    // Ambient humidity, (percent %RH) → bool
    // Sent as round(percent / 100 × 255); the module assumes 50 %RH otherwise.
    RRH46410Result r;
    if (sensor.read(r)) print_result(r);                           // Next sample, (result&) → bool
    // Waits for the sample counter to change; values are fixed during the warm-up.
    printf("new sample %u\n", (unsigned)sensor.wait_for_sample(4000)); // Wait for a new sample, (timeoutMs) → bool
    // True when the counter changed (or INT went high) within the timeout.
    if (sensor.read_results(r)) print_result(r);                   // Latest result, (result&) → bool
    // Does not wait; fails with ERR_NOT_READY before the first sample of the mode.
    print_result(sensor.last_result());                            // Last decoded result, () → const RRH46410Result&
    // The sample stored by read(), read_results() or wait_for_sample().
    printf("%s\n", RRH46410Full::air_quality_label(2.4f));        // UBA rating, (iaq) → const char*
    // "very good", "good", "medium", "poor" or "bad".
    printf("period %u s warm-up %u\n", (unsigned)sensor.sample_period(), (unsigned)sensor.warmup_samples()); // Mode timing, (mode=-1) → float s / uint8_t
    // 3 s and 100 samples in IAQ 2nd Gen mode.
    RRH46410Status st = sensor.status();                           // Last status byte, () → RRH46410Status
    // Error bits and the operation-mode error code (0xA = sensor damaged).
    printf("status 0x%02X mode error %u\n", st.raw, st.mode_error);

    g_sample_ready = false;
    sensor.on_interrupt(on_sample);                                // Subscribe to INT, (callback, intPin=nullptr) → void
    // Rising edge on INT; without a pin nothing is delivered and poll_interrupt() is used.
    for (int i = 0; i < 40 && !g_sample_ready; i++) {
        if (sensor.poll_interrupt()) break;                        // New sample pending, () → bool
        // INT level if wired, otherwise a counter comparison.
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    sensor.off_interrupt();                                        // Unsubscribe, () → void
    // Detaches the edge handler.

    sensor.set_mode(RRH46410Full::MODE_PBAQ);                      // Operation mode, (mode) → bool
    // PBAQ: TVOC and ethanol only, every 5 s; restarts the warm-up.
    if (sensor.read(r)) print_result(r);                           // Next sample, (result&) → bool
    // PBAQ results have a finer resolution (raw / 1000); IAQ and eCO2 are 0.
    sensor.set_mode(RRH46410Full::MODE_ULP);                       // Operation mode, (mode) → bool
    // ULP: one sample every 90 s at 0.23 mW.
    printf("period %u s warm-up %u\n", (unsigned)sensor.sample_period(), (unsigned)sensor.warmup_samples()); // Mode timing, (mode=-1) → float s / uint8_t
    // 90 s and 10 samples.

    sensor.config_gpio(0x03);                                      // GPIO directions, (directions) → bool
    // Bit 0 = GPIO1 output, bit 1 = GPIO2 output.
    sensor.set_gpio(0x01);                                         // Drive high, (mask) → bool
    // GPIO1 high.
    printf("gpio 0x%02X\n", sensor.get_gpio());                    // GPIO levels, () → int
    // Bits 0–3 = GPIO1–GPIO4.
    sensor.clear_gpio(0x01);                                       // Drive low, (mask) → bool
    // GPIO1 low.

    uint8_t mode = 0;
    sensor.send_command(0x10, nullptr, 0, &mode, 1);               // Raw request, (command, params*=nullptr, len=0, out*=nullptr, responseLen=0) → int
    // Checksums added and verified; here Get Operation Mode.
    printf("raw mode %u\n", mode);
    // sensor.clean_sensor(true);                                  // One-time cleaning, (confirm=false) → bool
    // Runs for one minute, once in the sensor's lifetime; left disabled here.
    sensor.suspend();                                              // Suspend mode, () → bool
    // Stops measuring, about 0.7 µA.
    sensor.reset();                                                // Reset, () → bool
    // Waits 500 ms and verifies the product ID; the module restarts in SUSPEND.
    printf("done (error %d)\n", sensor.last_error());              // Reason of the last failure, () → int
}
