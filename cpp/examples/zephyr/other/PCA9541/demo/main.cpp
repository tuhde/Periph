#include <math.h>
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
    // --- Power-up state ---
    // CONTROL 0x04 for master 0 means /01 (bus on, master 0); 0x00 means /03 (bus off).
    uint8_t ie = 0, ctl = 0, istat = 0;
    selector.readAll(ie, ctl, istat);                   // Read IE, CONTROL, ISTAT, (ie&, control&, istat&) → void
    printk("IE %d CONTROL %d ISTAT %d\n", (int)ie, (int)ctl, (int)istat);
    PCA9541Full::Status st = selector.status();         // Decode CONTROL, () → Status
    printk("hasControl %d connected %d\n", (int)st.hasControl, (int)st.connected);

    // --- Interrupts ---
    static volatile uint8_t lastStatus = 0;
    selector.setInterruptMask();                        // Unmask all interrupts, (...) → void
    selector.onInterrupt([](uint8_t status) { lastStatus = status; });  // Subscribe, (callback(status), intPin=nullptr) → void

    // --- Take the bus with a bus initialization ---
    // The chip clocks nine pulses, a NACK and a STOP on the downstream bus first, which
    // completes a transfer the previous master left half-done.
    bool rec = selector.recoverBus();                   // Recover and take the bus, (timeoutMs=20) → bool
    printk("recoverBus %d\n", (int)rec);
    k_sleep(K_MSEC(10));
    // ... talk to a downstream device through a second Connection here (after tBUF) ...

    // --- INT wire check ---
    selector.testInterrupt(true);                       // Drive this master's INT low, (own=false, other=false) → void
    k_sleep(K_MSEC(100));
    selector.testInterrupt();                           // Clear the test, () → void

    // --- Hand over and take back ---
    selector.release();                                 // Give the bus to the other master, () → void
    printk("has control after release %d\n", (int)selector.hasControl());
    bool back = selector.acquire();                     // Take the downstream bus, (busInit=false, timeoutMs=20) → bool
    printk("acquire %d\n", (int)back);
    selector.disconnect();                              // Isolate the downstream bus, () → void
    selector.connect();                                 // Reconnect, () → void

    // --- Watch the flags for 10 s ---
    for (int i = 0; i < 20; i++) {
        selector.service();                             // Poll ISTAT once and dispatch the callback, () → void
        printk("ISTAT %d\n", (int)lastStatus);
        k_sleep(K_MSEC(500));
    }
    selector.offInterrupt();                            // Unsubscribe, () → void
    return 0;
}
