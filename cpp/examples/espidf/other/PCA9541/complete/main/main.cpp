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
    uint8_t control = selector.getControl();            // Read the CONTROL byte, () → uint8_t
    PCA9541Full::Status st = selector.status();         // Decode CONTROL, () → Status
    // {hasControl, connected, busInitRequested, ownTest, otherTest}
    printf("CONTROL 0x%d hasControl %d connected %d\n", (int)control, (int)st.hasControl, (int)st.connected);
    uint8_t ie = 0, ctl = 0, istat = 0;
    selector.readAll(ie, ctl, istat);                   // Read IE, CONTROL, ISTAT, (ie&, control&, istat&) → void
    // one auto-increment read; clears the ISTAT flags
    printf("IE %d CONTROL %d ISTAT %d\n", (int)ie, (int)ctl, (int)istat);

    bool ok = selector.acquire();                       // Take the downstream bus, (busInit=false, timeoutMs=20) → bool
    // reads CONTROL, writes MYBUS/BUSON per the datasheet rule
    bool rec = selector.recoverBus();                   // Recover and take the bus, (timeoutMs=20) → bool
    // nine clocks, NACK and STOP on the downstream bus, then connect
    bool has = selector.hasControl();                   // Report control, () → bool
    bool conn = selector.isConnected();                 // Report the connection, () → bool
    // BUSON != NBUSON: some master is connected
    printf("acquire %d recover %d control %d connected %d\n", (int)ok, (int)rec, (int)has, (int)conn);
    selector.disconnect();                              // Isolate the downstream bus, () → void
    // BUSON = NBUSON; ownership bits are left
    selector.connect();                                 // Reconnect without changing ownership, () → void

    selector.setInterruptMask(false, true);             // Write IE, (busLost, busNotIdle, busInit, intIn) → void
    // true = masked, no interrupt
    uint8_t mask = selector.getInterruptMask();         // Read IE, () → uint8_t
    selector.disableInterrupt(PCA9541Full::SOURCE_INT_IN);  // Mask one source, (source) → void
    selector.enableInterrupt(PCA9541Full::SOURCE_INT_IN);  // Unmask one source, (source) → void
    printf("mask %d\n", (int)mask);
    selector.onInterrupt([](uint8_t status) { (void)status; });  // Subscribe, (callback(status), intPin=nullptr) → void
    // falling edge on this master's INT; call service() periodically without a pin
    selector.testInterrupt(true);                       // Drive this master's INT low, (own=false, other=false) → void
    // the bus bits are written back unchanged
    vTaskDelay(pdMS_TO_TICKS(200));
    selector.testInterrupt();                           // Clear the test, () → void
    selector.service();                                 // Poll ISTAT once and dispatch the callback, () → void
    uint8_t status = selector.pollInterrupt();          // Read and clear ISTAT, () → uint8_t
    printf("ISTAT %d\n", (int)status);
    selector.offInterrupt();                            // Unsubscribe, () → void
    selector.release();                                 // Give the bus to the other master, () → void
}
