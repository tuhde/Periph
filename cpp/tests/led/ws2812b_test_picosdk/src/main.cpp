#include <stdio.h>
#include "pico/stdlib.h"

int main(void) {
    stdio_init_all();
    sleep_ms(2000);
    printf("PASS probe\n");
    printf("===DONE: 1 passed, 0 failed===\n");
    return 0;
}
