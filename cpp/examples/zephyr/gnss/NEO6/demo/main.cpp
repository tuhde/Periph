#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "UARTTransportZephyr.h"
#include "NEO6.h"

// --- Portable GPS logger ---
// The module self-configures at factory defaults (9600 baud NMEA, 1 Hz); no
// CFG messages are needed for a basic position log. Runs for 60 seconds,
// polling update() far faster than the 1 Hz sentence rate so no sentence is
// missed, and prints one line per second once a fresh GGA has been parsed.
#ifndef NEO6_UART_NODE
#define NEO6_UART_NODE DT_NODELABEL(uart1)
#endif

int main(void) {
    const struct device* dev = DEVICE_DT_GET(NEO6_UART_NODE);
    UARTTransportZephyr transport(dev);
    NEO6Full gps(transport);                             // Create NEO-6 driver, (transport, bus_type=Uart)

    int64_t startMs = k_uptime_get();
    while (k_uptime_get() - startMs < 60000) {
        bool gotFix = gps.update();                      // Read + parse one NMEA sentence, () → bool

        // --- No fix yet: show the wait state ---
        // gpsFix alone would not be trustworthy here; update() already only
        // reports true once the GGA fix-status field confirms a real fix,
        // so a plain fix() == 0 check is enough to detect the waiting state.
        if (gps.fix() == 0) {
            printk("waiting for fix... satellites in use: %d\n", gps.satellites());
        }
        // --- Fix acquired: log the full position record ---
        // Cold-start TTFF is ~26 s typical outdoors; once gotFix flips true
        // the position, altitude, and HDOP fields below are all populated
        // together.
        else if (gotFix) {
            printk("%s  lat=%d  lon=%d  alt=%d m  sats=%d  hdop=%d\n",
                   gps.utcTime(), (int)gps.latitude(), (int)gps.longitude(),
                   (int)gps.altitude(), gps.satellites(), (int)gps.hdop());
        }

        k_msleep(200);
    }
    return 0;
}
