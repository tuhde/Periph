#include <math.h>
#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "PCA9541.h"

int main() {
    I2CConnectionLinux connection(1, PCA9541Minimal::I2C_ADDRESS);
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
    usleep(10UL * 1000UL);
    // ... talk to a downstream device through a second Connection here (after tBUF) ...

    // --- INT wire check ---
    selector.testInterrupt(true);                       // Drive this master's INT low, (own=false, other=false) → void
    usleep(100UL * 1000UL);
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
        usleep(500UL * 1000UL);
    }
    selector.offInterrupt();                            // Unsubscribe, () → void
    return 0;
}
