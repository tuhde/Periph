#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "PCA9541.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, PCA9541Minimal::I2C_ADDRESS);
    PCA9541Full selector(connection);                   // Create PCA9541 Full driver, (connection)
    uint8_t control = selector.getControl();            // Read the CONTROL byte, () → uint8_t
    PCA9541Full::Status st = selector.status();         // Decode CONTROL, () → Status
    // {hasControl, connected, busInitRequested, ownTest, otherTest}
    printk("CONTROL 0x%d hasControl %d connected %d\n", (int)control, (int)st.hasControl, (int)st.connected);
    uint8_t ie = 0, ctl = 0, istat = 0;
    selector.readAll(ie, ctl, istat);                   // Read IE, CONTROL, ISTAT, (ie&, control&, istat&) → void
    // one auto-increment read; clears the ISTAT flags
    printk("IE %d CONTROL %d ISTAT %d\n", (int)ie, (int)ctl, (int)istat);

    bool ok = selector.acquire();                       // Take the downstream bus, (busInit=false, timeoutMs=20) → bool
    // reads CONTROL, writes MYBUS/BUSON per the datasheet rule
    bool rec = selector.recoverBus();                   // Recover and take the bus, (timeoutMs=20) → bool
    // nine clocks, NACK and STOP on the downstream bus, then connect
    bool has = selector.hasControl();                   // Report control, () → bool
    bool conn = selector.isConnected();                 // Report the connection, () → bool
    // BUSON != NBUSON: some master is connected
    printk("acquire %d recover %d control %d connected %d\n", (int)ok, (int)rec, (int)has, (int)conn);
    selector.disconnect();                              // Isolate the downstream bus, () → void
    // BUSON = NBUSON; ownership bits are left
    selector.connect();                                 // Reconnect without changing ownership, () → void

    selector.setInterruptMask(false, true);             // Write IE, (busLost, busNotIdle, busInit, intIn) → void
    // true = masked, no interrupt
    uint8_t mask = selector.getInterruptMask();         // Read IE, () → uint8_t
    selector.disableInterrupt(PCA9541Full::SOURCE_INT_IN);  // Mask one source, (source) → void
    selector.enableInterrupt(PCA9541Full::SOURCE_INT_IN);  // Unmask one source, (source) → void
    printk("mask %d\n", (int)mask);
    selector.onInterrupt([](uint8_t status) { (void)status; });  // Subscribe, (callback(status), intPin=nullptr) → void
    // falling edge on this master's INT; call service() periodically without a pin
    selector.testInterrupt(true);                       // Drive this master's INT low, (own=false, other=false) → void
    // the bus bits are written back unchanged
    k_sleep(K_MSEC(200));
    selector.testInterrupt();                           // Clear the test, () → void
    selector.service();                                 // Poll ISTAT once and dispatch the callback, () → void
    uint8_t status = selector.pollInterrupt();          // Read and clear ISTAT, () → uint8_t
    printk("ISTAT %d\n", (int)status);
    selector.offInterrupt();                            // Unsubscribe, () → void
    selector.release();                                 // Give the bus to the other master, () → void
    return 0;
}
