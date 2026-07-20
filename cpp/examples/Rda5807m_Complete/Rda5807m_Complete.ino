#include <Wire.h>
#include "I2CTransport.h"
#include "Rda5807m.h"

I2CTransport transport(Wire, 0x10);
RDA5807MFull fm(transport, 100.0f, 8);

void setup() {
    Serial.begin(115200);
    Wire.begin();

    fm.set_frequency(97.5f);
    Serial.println(fm.frequency());

    fm.set_volume(10);
    fm.mute(false);

    float freq;
    if (fm.seek(true, freq)) Serial.println(freq);

    fm.configure(RDA5807MFull::BAND_WORLD, RDA5807MFull::SPACE_100K, 1, 8, 1);

    fm.set_bass_boost(true);
    fm.set_mono(false);
    fm.set_softmute(true);

    fm.enable_rds(true);
    delay(1000);
    Serial.println(fm.rds_ready());
    uint16_t a, b, c, d;
    if (fm.read_rds_group(a, b, c, d)) {
        Serial.println(a); Serial.println(b); Serial.println(c); Serial.println(d);
    }

    Serial.println(fm.is_stereo());
    Serial.println(fm.is_station());
    Serial.println(fm.is_ready());
    Serial.println(fm.signal_strength());

    fm.standby(true);
    delay(10);
    fm.standby(false);

    fm.soft_reset();
}

void loop() {}
