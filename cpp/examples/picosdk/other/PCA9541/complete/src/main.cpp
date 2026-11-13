#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "PCA9541.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, PCA9541Minimal::I2C_ADDRESS);
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
    sleep_ms(200);
    selector.testInterrupt();                           // Clear the test, () → void
    selector.service();                                 // Poll ISTAT once and dispatch the callback, () → void
    uint8_t status = selector.pollInterrupt();          // Read and clear ISTAT, () → uint8_t
    printf("ISTAT %d\n", (int)status);
    selector.offInterrupt();                            // Unsubscribe, () → void
    selector.release();                                 // Give the bus to the other master, () → void
    return 0;
}
