#include <Wire.h>
#include "I2CTransport.h"
#include "Rda5807m.h"

I2CTransport transport(Wire, 0x10);
RDA5807MFull fm(transport, 87.5f, 10);

void setup() {
    Serial.begin(115200);
    Wire.begin();

    // --- FM band scanner ---
    // Start at the bottom of the world-wide band and repeatedly seek upward
    // with SKMODE=1 (stop at band limit) so a seek that returns false means
    // the top of the band has been reached and the scan is done.
    fm.enable_rds(true);

    Serial.println("Scanning...");
    while (true) {
        float freq;
        if (!fm.seek(true, freq)) break;
        if (!fm.is_station()) continue;

        uint8_t rssi = fm.signal_strength();
        bool stereo = fm.is_stereo();

        // --- Try to read the Program Service (station) name via RDS ---
        // Group types 0A/0B carry the 8-character PS name, four segments of
        // two characters each, addressed by block B bits 1:0. Give the
        // decoder up to 2 seconds to assemble a full name.
        char ps_name[9] = {0};
        bool have_all[4] = {false, false, false, false};
        unsigned long deadline = millis() + 2000;
        while (millis() < deadline) {
            if (fm.rds_ready()) {
                uint16_t a, b, c, d;
                if (fm.read_rds_group(a, b, c, d)) {
                    uint8_t group_type = b >> 12;
                    uint8_t is_b_variant = (b >> 11) & 1;
                    if (group_type == 0 && is_b_variant == 0) {
                        uint8_t seg = b & 0x03;
                        ps_name[seg * 2] = static_cast<char>(d >> 8);
                        ps_name[seg * 2 + 1] = static_cast<char>(d & 0xFF);
                        have_all[seg] = true;
                        if (have_all[0] && have_all[1] && have_all[2] && have_all[3]) break;
                    }
                }
            }
            delay(40);
        }

        Serial.print(freq); Serial.print(" MHz  RSSI=");
        Serial.print(rssi); Serial.print(stereo ? "  stereo  " : "  mono  ");
        Serial.println((have_all[0] && have_all[1] && have_all[2] && have_all[3]) ? ps_name : "(no RDS name)");
    }
    Serial.println("Scan complete.");
}

void loop() {}
