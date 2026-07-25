#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "UARTTransportPicoSDK.h"
#include "NEO6.h"

// UART0 on GP0 (TX) / GP1 (RX) — pico-sdk default UART pins.
uart_init(uart0, 9600);
gpio_set_function(0, GPIO_FUNC_UART);
gpio_set_function(1, GPIO_FUNC_UART);
UARTTransportPicoSDK transport(uart0, 9600);
NEO6Full gps(transport, /*bus_type=*/0);

int main(void) {
    unsigned long startMs;

    stdio_init_all();

    startMs = to_ms_since_boot(get_absolute_time());
    while (true) {

    if (to_ms_since_boot(get_absolute_time()) - startMs >= 60000) {
        while (true) sleep_ms(1000);  // done
    }

    bool gotFix = gps.update();                          // Read + parse one NMEA sentence, () → bool

    // --- No fix yet: show the wait state ---
    // gpsFix alone would not be trustworthy here; update() already only
    // reports true once the GGA fix-status field confirms a real fix, so
    // a plain fix() == 0 check is enough to detect the waiting state.
    if (gps.fix() == 0) {
        printf("waiting for fix... satellites in use: ");
        printf("%d\n", gps.satellites());
    }
    // --- Fix acquired: log the full position record ---
    // Cold-start TTFF is ~26 s typical outdoors; once gotFix flips true the
    // position, altitude, and HDOP fields below are all populated together.
    else if (gotFix) {
        printf("%d", gps.utcTime());
        printf("  lat=");
        printf("%.6f", gps.latitude());
        printf("  lon=");
        printf("%.6f", gps.longitude());
        printf("  alt=");
        printf("%.1f", gps.altitude());
        printf(" m  sats=");
        printf("%d", gps.satellites());
        printf("  hdop=");
        printf("%d\n", gps.hdop());
    }

    sleep_ms(200);
        sleep_ms(10);
    }

    return 0;
}
