#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "UARTTransportLinux.h"
#include "NEO6.h"

int main() {
    const char* port = getenv("UART_PORT") ? getenv("UART_PORT") : "/dev/ttyS0";
    UARTTransportLinux transport(port, 9600);

    NEO6Minimal gps(transport);                                            // Create NEO-6 driver, (transport, bus_type=Uart)

    while (true) {
        if (gps.update()) {                                                // Read + parse one NMEA sentence, () → bool
            printf("lat=%.6f lon=%.6f\n", gps.latitude(), gps.longitude()); // Read latitude, () → double °  ; longitude() → double °
        }
    }
    return 0;
}
