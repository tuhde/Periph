#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, DRV8830Minimal::I2C_ADDRESS);
DRV8830Minimal motor(connection);                        // Create DRV8830 driver, (connection)

void setup() {
    Wire.begin();
}

void loop() {
    motor.drive(3.0f);                                   // Drive at regulated voltage, (voltage V, + = forward) → void
    delay(2000);
    motor.drive(-3.0f);                                  // Drive at regulated voltage, (voltage V, - = reverse) → void
    delay(2000);
}
