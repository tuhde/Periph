#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, PCA9541Full::I2C_ADDRESS);
PCA9541Full selector(connection);                       // Create PCA9541 Full driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    uint8_t control = selector.getControl();            // Read the CONTROL byte, () → uint8_t
    PCA9541Full::Status st = selector.status();         // Decode CONTROL, () → Status
    // {hasControl, connected, busInitRequested, ownTest, otherTest}
    Serial.print("CONTROL 0x"); Serial.print((int)control); Serial.print(" hasControl "); Serial.print((int)st.hasControl); Serial.print(" connected "); Serial.println((int)st.connected);
    uint8_t ie = 0, ctl = 0, istat = 0;
    selector.readAll(ie, ctl, istat);                   // Read IE, CONTROL, ISTAT, (ie&, control&, istat&) → void
    // one auto-increment read; clears the ISTAT flags
    Serial.print("IE "); Serial.print((int)ie); Serial.print(" CONTROL "); Serial.print((int)ctl); Serial.print(" ISTAT "); Serial.println((int)istat);

    bool ok = selector.acquire();                       // Take the downstream bus, (busInit=false, timeoutMs=20) → bool
    // reads CONTROL, writes MYBUS/BUSON per the datasheet rule
    bool rec = selector.recoverBus();                   // Recover and take the bus, (timeoutMs=20) → bool
    // nine clocks, NACK and STOP on the downstream bus, then connect
    bool has = selector.hasControl();                   // Report control, () → bool
    bool conn = selector.isConnected();                 // Report the connection, () → bool
    // BUSON != NBUSON: some master is connected
    Serial.print("acquire "); Serial.print((int)ok); Serial.print(" recover "); Serial.print((int)rec); Serial.print(" control "); Serial.print((int)has); Serial.print(" connected "); Serial.println((int)conn);
    selector.disconnect();                              // Isolate the downstream bus, () → void
    // BUSON = NBUSON; ownership bits are left
    selector.connect();                                 // Reconnect without changing ownership, () → void

    selector.setInterruptMask(false, true);             // Write IE, (busLost, busNotIdle, busInit, intIn) → void
    // true = masked, no interrupt
    uint8_t mask = selector.getInterruptMask();         // Read IE, () → uint8_t
    selector.disableInterrupt(PCA9541Full::SOURCE_INT_IN);  // Mask one source, (source) → void
    selector.enableInterrupt(PCA9541Full::SOURCE_INT_IN);  // Unmask one source, (source) → void
    Serial.print("mask "); Serial.println((int)mask);
    selector.onInterrupt([](uint8_t status) { (void)status; });  // Subscribe, (callback(status), intPin=nullptr) → void
    // falling edge on this master's INT; call service() periodically without a pin
    selector.testInterrupt(true);                       // Drive this master's INT low, (own=false, other=false) → void
    // the bus bits are written back unchanged
    delay(200);
    selector.testInterrupt();                           // Clear the test, () → void
    selector.service();                                 // Poll ISTAT once and dispatch the callback, () → void
    uint8_t status = selector.pollInterrupt();          // Read and clear ISTAT, () → uint8_t
    Serial.print("ISTAT "); Serial.println((int)status);
    selector.offInterrupt();                            // Unsubscribe, () → void
    selector.release();                                 // Give the bus to the other master, () → void
}

void loop() {
}
