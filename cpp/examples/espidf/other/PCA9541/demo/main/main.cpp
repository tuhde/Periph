#include <math.h>
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "PCA9541.h"

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

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = PCA9541Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    PCA9541Full selector(connection);                   // Create PCA9541 Full driver, (connection)
    // --- Power-up state ---
    // CONTROL 0x04 for master 0 means /01 (bus on, master 0); 0x00 means /03 (bus off).
    uint8_t ie = 0, ctl = 0, istat = 0;
    selector.readAll(ie, ctl, istat);                   // Read IE, CONTROL, ISTAT, (ie&, control&, istat&) → void
    printf("IE %d CONTROL %d ISTAT %d\n", (int)ie, (int)ctl, (int)istat);
    PCA9541Full::Status st = selector.status();         // Decode CONTROL, () → Status
    printf("hasControl %d connected %d\n", (int)st.hasControl, (int)st.connected);

    // --- Interrupts ---
    static volatile uint8_t lastStatus = 0;
    selector.setInterruptMask();                        // Unmask all interrupts, (...) → void
    selector.onInterrupt([](uint8_t status) { lastStatus = status; });  // Subscribe, (callback(status), intPin=nullptr) → void

    // --- Take the bus with a bus initialization ---
    // The chip clocks nine pulses, a NACK and a STOP on the downstream bus first, which
    // completes a transfer the previous master left half-done.
    bool rec = selector.recoverBus();                   // Recover and take the bus, (timeoutMs=20) → bool
    printf("recoverBus %d\n", (int)rec);
    vTaskDelay(pdMS_TO_TICKS(10));
    // ... talk to a downstream device through a second Connection here (after tBUF) ...

    // --- INT wire check ---
    selector.testInterrupt(true);                       // Drive this master's INT low, (own=false, other=false) → void
    vTaskDelay(pdMS_TO_TICKS(100));
    selector.testInterrupt();                           // Clear the test, () → void

    // --- Hand over and take back ---
    selector.release();                                 // Give the bus to the other master, () → void
    printf("has control after release %d\n", (int)selector.hasControl());
    bool back = selector.acquire();                     // Take the downstream bus, (busInit=false, timeoutMs=20) → bool
    printf("acquire %d\n", (int)back);
    selector.disconnect();                              // Isolate the downstream bus, () → void
    selector.connect();                                 // Reconnect, () → void

    // --- Watch the flags for 10 s ---
    for (int i = 0; i < 20; i++) {
        selector.service();                             // Poll ISTAT once and dispatch the callback, () → void
        printf("ISTAT %d\n", (int)lastStatus);
        vTaskDelay(pdMS_TO_TICKS(500));
    }
    selector.offInterrupt();                            // Unsubscribe, () → void
}
