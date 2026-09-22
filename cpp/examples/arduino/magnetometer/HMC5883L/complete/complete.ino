#include <Wire.h>
#include "I2CConnection.h"
#include "HMC5883L.h"

I2CConnection connection(Wire, 0x1E);
HMC5883LFull hmc5883l(connection);

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin();
    delay(6);  // Wait for first measurement

    // --- Identification ---
    uint8_t id_a, id_b, id_c;
    hmc5883l.identify(id_a, id_b, id_c);  // Read ID registers, () → (int, int, int)
    Serial.print("ID: 0x"); Serial.print(id_a, HEX); Serial.print(" 0x");
    Serial.print(id_b, HEX); Serial.print(" 0x"); Serial.println(id_c, HEX);

    // --- Status ---
    Serial.print("Status: 0x"); Serial.println(hmc5883l.status(), HEX);  // Read raw status, () → int
    Serial.print("Data ready: "); Serial.println(hmc5883l.data_ready());  // Check data ready, () → bool

    // --- Magnetic field readings ---
    float x, y, z;
    hmc5883l.magnetic_field(x, y, z);  // Read magnetic field, () → (float T, float T, float T)
    Serial.print("X="); Serial.print(x, 6); Serial.print(" T  Y=");
    Serial.print(y, 6); Serial.print(" T  Z=");
    Serial.print(z, 6); Serial.println(" T");

    // --- Configuration ---
    hmc5883l.configure(15, 8, 1);       // Configure chip, (odr 0.75-75 Hz, averaging 1/2/4/8, gain 0-7) → None
                                        // writes Config A and B registers
    hmc5883l.set_gain(2);               // Set gain, (gain 0-7) → None
                                        // updates GN bits in Config B
    hmc5883l.set_mode("single");        // Set operating mode, ('continuous'|'single'|'idle') → None
                                        // writes MD bits in Mode Register

    // --- Single-shot measurement ---
    hmc5883l.single_measurement(x, y, z);  // Single-shot measurement, () → (float T, float T, float T)
                                           // writes single-measurement mode, waits 6 ms, reads all axes
    Serial.print("Single: X="); Serial.print(x, 6); Serial.print(" T  Y=");
    Serial.print(y, 6); Serial.print(" T  Z=");
    Serial.print(z, 6); Serial.println(" T");

    hmc5883l.set_mode("continuous");    // Set operating mode, ('continuous'|'single'|'idle') → None

    // --- Self-test ---
    hmc5883l.self_test(true, x, y, z);  // Self-test with positive bias, (positive=bool) → (float T, float T, float T)
                                        // configures bias, takes measurement, restores normal mode
    Serial.print("Self-test: X="); Serial.print(x, 6); Serial.print(" T  Y=");
    Serial.print(y, 6); Serial.print(" T  Z=");
    Serial.print(z, 6); Serial.println(" T");
}

void loop() {
    delay(1000);
}