#include <math.h>
#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, PCA9541Full::I2C_ADDRESS);
PCA9541Full selector(connection);                       // Create PCA9541 Full driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    // --- Power-up state ---
    // CONTROL 0x04 for master 0 means /01 (bus on, master 0); 0x00 means /03 (bus off).
    uint8_t ie = 0, ctl = 0, istat = 0;
    selector.readAll(ie, ctl, istat);                   // Read IE, CONTROL, ISTAT, (ie&, control&, istat&) → void
    Serial.print("IE "); Serial.print((int)ie); Serial.print(" CONTROL "); Serial.print((int)ctl); Serial.print(" ISTAT "); Serial.println((int)istat);
    PCA9541Full::Status st = selector.status();         // Decode CONTROL, () → Status
    Serial.print("hasControl "); Serial.print((int)st.hasControl); Serial.print(" connected "); Serial.println((int)st.connected);

    // --- Interrupts ---
    static volatile uint8_t lastStatus = 0;
    selector.setInterruptMask();                        // Unmask all interrupts, (...) → void
    selector.onInterrupt([](uint8_t status) { lastStatus = status; });  // Subscribe, (callback(status), intPin=nullptr) → void

    // --- Take the bus with a bus initialization ---
    // The chip clocks nine pulses, a NACK and a STOP on the downstream bus first, which
    // completes a transfer the previous master left half-done.
    bool rec = selector.recoverBus();                   // Recover and take the bus, (timeoutMs=20) → bool
    Serial.print("recoverBus "); Serial.println((int)rec);
    delay(10);
    // ... talk to a downstream device through a second Connection here (after tBUF) ...

    // --- INT wire check ---
    selector.testInterrupt(true);                       // Drive this master's INT low, (own=false, other=false) → void
    delay(100);
    selector.testInterrupt();                           // Clear the test, () → void

    // --- Hand over and take back ---
    selector.release();                                 // Give the bus to the other master, () → void
    Serial.print("has control after release "); Serial.println((int)selector.hasControl());
    bool back = selector.acquire();                     // Take the downstream bus, (busInit=false, timeoutMs=20) → bool
    Serial.print("acquire "); Serial.println((int)back);
    selector.disconnect();                              // Isolate the downstream bus, () → void
    selector.connect();                                 // Reconnect, () → void

    // --- Watch the flags for 10 s ---
    for (int i = 0; i < 20; i++) {
        selector.service();                             // Poll ISTAT once and dispatch the callback, () → void
        Serial.print("ISTAT "); Serial.println((int)lastStatus);
        delay(500);
    }
    selector.offInterrupt();                            // Unsubscribe, () → void
}

void loop() {
}
