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


    fm.set_frequency(97.5f);
    printf("%d\n", fm.frequency());

    fm.set_volume(10);
    fm.mute(false);

    float freq;
    if (fm.seek(true, freq)) printf("%f\n", freq);

    fm.configure(RDA5807MFull::BAND_WORLD, RDA5807MFull::SPACE_100K, 1, 8, 1);

    fm.set_bass_boost(true);
    fm.set_mono(false);
    fm.set_softmute(true);

    fm.enable_rds(true);
    sleep_ms(1000);
    printf("%d\n", fm.rds_ready());
    uint16_t a, b, c, d;
    if (fm.read_rds_group(a, b, c, d)) {
        printf("%d\n", a); printf("%d\n", b); printf("%d\n", c); printf("%d\n", d);
    }

    printf("%d\n", fm.is_stereo());
    printf("%d\n", fm.is_station());
    printf("%d\n", fm.is_ready());
    printf("%d\n", fm.signal_strength());

    fm.standby(true);
    sleep_ms(10);
    fm.standby(false);

    fm.soft_reset();
    return 0;
}
