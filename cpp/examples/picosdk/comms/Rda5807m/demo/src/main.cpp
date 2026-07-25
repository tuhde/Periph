#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "Rda5807m.h"

// I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
i2c_init(i2c0, 100 * 1000);
gpio_set_function(4, GPIO_FUNC_I2C);
gpio_set_function(5, GPIO_FUNC_I2C);
gpio_pull_up(4);
gpio_pull_up(5);
I2CTransportPicoSDK transport(i2c0, 0x10);
Rda5807mFull rda5807m(transport, /*frequency_mhz=*/100.0f, /*volume=*/5);

int main(void) {

    stdio_init_all();


    // --- FM band scanner ---
    // Start at the bottom of the world-wide band and repeatedly seek upward
    // with SKMODE=1 (stop at band limit) so a seek that returns false means
    // the top of the band has been reached and the scan is done.
    fm.enable_rds(true);

    printf("Scanning...\n");
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
        unsigned long deadline = to_ms_since_boot(get_absolute_time()) + 2000;
        while (to_ms_since_boot(get_absolute_time()) < deadline) {
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
            sleep_ms(40);
        }

        printf("%f", freq); printf(" MHz  RSSI=");
        printf("%d", rssi); printf("%d", stereo ? "  stereo  " : "  mono  ");
        printf("%d\n", (have_all[0] && have_all[1] && have_all[2] && have_all[3]) ? ps_name : "(no RDS name)");
    }
    printf("Scan complete.\n");
    return 0;
}
