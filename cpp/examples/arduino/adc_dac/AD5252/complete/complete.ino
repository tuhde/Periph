#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, AD5252Full::I2C_ADDRESS);
AD5252Full pot(connection);                             // Create AD5252 Full driver, (connection, rAb=10000.0 Ω, rWiper=75.0 Ω)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    pot.setWiper(0, 100);                               // Set wiper position, (channel 0–1, code 0–255) → bool
    // writes [instruction, code]; the instruction byte selects the channel
    uint8_t w = pot.getWiper(0);                        // Read wiper position, (channel 0–1) → uint8_t 0–255
    // sends the instruction alone, then reads one byte
    Serial.print("wiper "); Serial.println((int)w);
    pot.setResistance(0, 5000.0f);                      // Set W–B resistance, (channel 0–1, ohms Ω) → bool
    // inverts R = D/256·RAB + RW to the nearest code
    float r = pot.getResistance(0);                     // Read W–B resistance, (channel 0–1) → float Ω
    // evaluates D/256·RAB + RW for the current code
    Serial.print("R_WB "); Serial.print((double)r, 1); Serial.println(" ohm");
    pot.setBoth(32, 224);                               // Write both wipers, (code0 0–255, code1 0–255) → void
    // two transactions, channel 0 then channel 1
    pot.setRatio(0, 0.25f);                             // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → bool
    // code = round(ratio × 256), clamped to 255
    float ratio = pot.getRatio(0);                      // Read divider ratio, (channel 0–1) → float
    // code / 256
    Serial.print("ratio "); Serial.println((double)ratio, 4);
    pot.setResistanceWa(1, 3000.0f);                    // Set W–A resistance, (channel 0–1, ohms Ω) → bool
    // rheostat between W and A with B open
    float rwa = pot.getResistanceWa(1);                 // Read W–A resistance, (channel 0–1) → float Ω
    // (256 − D)/256·RAB + RW
    Serial.print("R_WA "); Serial.print((double)rwa, 1); Serial.println(" ohm");
    pot.increment(0);                                   // Step up one code, (channel=BOTH 0–1) → bool
    // quick command; BOTH addresses both channels
    pot.decrement(0);                                   // Step down one code, (channel=BOTH 0–1) → bool
    // quick command; saturates at 0
    pot.doubleCode(0);                                  // Raise by 6 dB, (channel=BOTH 0–1) → bool
    // quick command; the code doubles, saturating at 255
    pot.halve(0);                                       // Lower by 6 dB, (channel=BOTH 0–1) → bool
    // quick command; the code is halved
    pot.store();                                        // Store wipers in EEMEM, (channel=BOTH 0–1) → bool
    // quick command, then ACK polling (≈ 26 ms)
    pot.setBoth(1, 2);
    pot.restore();                                      // Restore wipers from EEMEM, (channel=BOTH 0–1) → bool
    // quick command followed by NOP
    Serial.print("restored "); Serial.println((int)pot.getWiper(0));
    pot.writeEemem(4, 0x5A);                            // Write user EEMEM byte, (index 4–15, value 0–255) → bool
    // followed by ACK polling (≈ 26 ms)
    uint8_t e = pot.readEemem(4);                       // Read user EEMEM byte, (index 4–15) → uint8_t
    // pointer write, then a one-byte read
    Serial.print("eemem4 "); Serial.println((int)e);
    float tol = pot.readTolerance(0);                   // Read stored RAB tolerance, (channel 0–1) → float %
    // sign-magnitude integer byte + decimal byte / 256
    Serial.print("tolerance "); Serial.println(tol, 4);
    float ra = pot.rabActual(0);                        // Actual RAB of a channel, (channel 0–1) → float Ω
    // rAb × (1 + tolerance / 100)
    Serial.print("rab_actual "); Serial.println(ra, 4);
    pot.nop();                                          // Send NOP, () → void
    // quick command 0x80; the chip returns to idle
    pot.setRab(50000.0f, 120.0f);                       // Change resistance model, (rAb Ω, rWiper=-1 Ω) → void
    // affects conversions only; no bus traffic
    pot.reset();                                        // Reset from EEMEM, () → bool
    // quick command 0xB8, then ACK polling
}

void loop() {
}
