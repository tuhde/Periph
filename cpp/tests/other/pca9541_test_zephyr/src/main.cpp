#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include <math.h>
#include "PCA9541.h"

#define I2C_NODE DT_NODELABEL(i2c0)

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    if (!device_is_ready(i2c_dev)) {
        printk("FAIL device_ready\n");
        return 1;
    }
    I2CConnectionZephyr connection(i2c_dev, PCA9541Minimal::I2C_ADDRESS);
    PCA9541Minimal selector(connection);                    // Create PCA9541 driver, (connection)
    check_true(selector.present(), "present");
    bool has = selector.hasControl();                       // Report control, () → bool
    check_true(has == true || has == false, "has_control_is_bool");

    PCA9541Full full(connection);                           // Create PCA9541 Full driver, (connection)
    uint8_t control = full.getControl();                    // Read the CONTROL byte, () → uint8_t
    PCA9541Full::Status st = full.status();                 // Decode CONTROL, () → Status
    check_true(st.hasControl == (((control & 1) != 0) == ((control & 2) != 0)), "status_matches_control");
    uint8_t ie = 0, ctl = 0, istat = 0;
    full.readAll(ie, ctl, istat);                           // Read IE, CONTROL, ISTAT, (ie&, control&, istat&) → void
    check_true(ie <= 0x0F, "read_all_ie_range");
    full.setInterruptMask(false, true);                     // Write IE, (busLost, busNotIdle, busInit, intIn) → void
    check_true(full.getInterruptMask() == 0x04, "interrupt_mask_roundtrip"); // Read IE, () → uint8_t
    full.setInterruptMask();
    check_true(full.getInterruptMask() == 0x00, "interrupt_mask_cleared");
    full.testInterrupt(true);                               // Drive this master's INT low, (own=false, other=false) → void
    check_true(full.status().ownTest, "test_interrupt_sets_teston");
    full.testInterrupt();
    check_true(!full.status().ownTest, "test_interrupt_clears");
    check_true(full.acquire() && full.hasControl(), "acquire_takes_bus"); // Take the downstream bus, (busInit=false, timeoutMs=20) → bool
    full.disconnect();                                      // Isolate the downstream bus, () → void
    check_true(!full.isConnected(), "disconnect");
    full.connect();                                         // Reconnect, () → void
    check_true(full.isConnected(), "connect");
    full.release();                                         // Give the bus to the other master, () → void
    check_true(!full.hasControl(), "release_gives_bus_away");
    full.acquire();
    check_true(full.hasControl(), "reacquire");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
