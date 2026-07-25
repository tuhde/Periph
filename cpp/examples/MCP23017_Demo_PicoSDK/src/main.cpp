#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "MCP23017.h"

// I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
i2c_init(i2c0, 100 * 1000);
gpio_set_function(4, GPIO_FUNC_I2C);
gpio_set_function(5, GPIO_FUNC_I2C);
gpio_pull_up(4);
gpio_pull_up(5);
I2CTransportPicoSDK transport(i2c0, 0x20);
MCP23017Full mcp(transport, /*addr=*/0x20);

int main(void) {

    stdio_init_all();


    mcp.configure_pullup(1, 0x7F);                          // Enable pull-ups on GPB0–GPB6, (port=1, mask=0x7F) → None
    printf("=== Knight Rider scanner with button override ===\n");
    printf("  pos  btn_msk  out_mask\n");
    while (true) {

    static int step = 0;

    uint8_t port_b = mcp.read_port(1);                      // Read GPB0–GPB6 buttons, (port=1) → uint8_t 0–127
    uint8_t btn_mask = (~port_b) & 0x7F;                     // Invert: active-low buttons → active-high mask

    int direction = (step / 7) % 2 == 0 ? 1 : -1;
    int pos = step % 7;
    if (direction == -1) pos = 6 - pos;

    uint8_t scanner = 1 << pos;
    uint8_t output = btn_mask | scanner;                    // Button takes priority over scanner

    mcp.write_port(0, output);                             // Write PORTA outputs, (port=0, mask) → None

    if (to_ms_since_boot(get_absolute_time()) - last_print >= 100) {
        last_print = to_ms_since_boot(get_absolute_time());
        printf("  ");
        printf("%d", pos);
        printf("     ");
        printf("0x%X", (unsigned)btn_mask);
        printf("        ");
        printf("0x%X\n", (unsigned)output);
        step++;
    }

    if (step >= 40) {
        mcp.write_port(0, 0x00);                             // Clear all outputs, (port=0, mask=0x00) → None
        printf("=== Done ===\n");
        while (1) sleep_ms(1000);
    }
        sleep_ms(10);
    }

    return 0;
}
