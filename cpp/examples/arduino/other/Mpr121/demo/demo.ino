#include <Wire.h>
#include <Periph.h>

// 12-button musical keyboard: ELE0..ELE11 -> C4..B4
static const char* NOTES[12] = {
    "C4", "C#4", "D4", "D#4", "E4", "F4", "F#4", "G4", "G#4", "A4", "A#4", "B4"
};

I2CConnection connection(Wire, 0x5A);                                    // Create I2C connection, (Wire, addr=0x5A) → I2CConnection
MPR121Full mpr(connection);                                              // Create MPR121 Full, (connection) → MPR121Full
                                                                        // defaults, all 12 electrodes enabled
static uint16_t previous = 0;

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    uint16_t mask = mpr.touched();                                      // Read 12-bit touch bitmask, () → uint16_t bitmask
                                                                        // bit n=1 means ELEn is currently touched
    uint16_t newly_pressed = mask & ~previous;
    uint16_t newly_released = (~mask) & previous;
    for (uint8_t n = 0; n < 12; n++) {
        if (newly_pressed & (1u << n)) {
            Serial.print("NOTE ON:  "); Serial.println(NOTES[n]);
        }
        if (newly_released & (1u << n)) {
            Serial.print("NOTE OFF: "); Serial.println(NOTES[n]);
        }
    }
    previous = mask;
    delay(50);
}
