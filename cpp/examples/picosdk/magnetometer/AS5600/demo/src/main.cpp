#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "AS5600.h"

// I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
i2c_init(i2c0, 100 * 1000);
gpio_set_function(4, GPIO_FUNC_I2C);
gpio_set_function(5, GPIO_FUNC_I2C);
gpio_pull_up(4);
gpio_pull_up(5);
I2CTransportPicoSDK transport(i2c0, 0x36);
AS5600Full as5600(transport);

int main(void) {

    stdio_init_all();
    sleep_ms(2000);


    // --- Motor feedback monitor: read angle 10 times per second ---
    // AGC monitoring detects magnet distance drift; status changes alert to magnet removal.
    // In 5 V mode, target AGC ≈ 128; in 3.3 V mode, target AGC ≈ 64.

    uint8_t prev_status = as5600.status_byte();

    for (int n = 0; n < 10; n++) {
        float a = as5600.angle();                    // Read absolute angle, () → float degrees
        uint16_t r = as5600.raw_angle();             // Read raw unscaled angle, () → int 0-4095
        uint8_t g = as5600.agc();                    // Read AGC value, () → int

        // --- Check for status changes (magnet inserted/removed) ---
        uint8_t status = as5600.status_byte();
        if (status != prev_status) {
            if (!as5600.is_magnet_detected()) {
                printf("[MAGNET REMOVED] MD=0\n");
            } else {
                printf("[MAGNET DETECTED] MD=1  MH=");
                printf("%d", as5600.is_magnet_too_strong());
                printf("  ML=");
                printf("%d\n", as5600.is_magnet_too_weak());
            }
            prev_status = status;
        }

        // --- AGC health check ---
        if (as5600.is_magnet_detected()) {
            const char* tag = "[OK]";
            if (g < 64 || g > 192) {
                tag = "[AGC low — magnet weak or too far]";
            }
            printf("angle="); printf("%.2f", a); printf("°  raw=");
            printf("%d", r); printf("  agc="); printf("%d", g);
            printf("%d\n", tag);
        }

        sleep_ms(100);
    }
    while (true) {

    sleep_ms(1000);
        sleep_ms(10);
    }

    return 0;
}
