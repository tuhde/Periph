#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "UARTConnectionLinux.h"
#include "NEO6.h"

int main() {
    const char* port = getenv("UART_PORT") ? getenv("UART_PORT") : "/dev/ttyS0";
    UARTConnectionLinux connection(port, 9600);

    NEO6Full gps(connection);                                               // Create NEO-6 driver, (connection, bus_type=Uart)

    gps.setRate(1);                                                        // Set navigation update rate, (hz) → void
    gps.setPlatform(0);                                                    // Set dynamic platform model, (model 0-8) → void
    gps.saveConfig();                                                      // Persist current configuration, () → void

    while (true) {
        if (gps.update()) {                                                // Read + parse one NMEA sentence, () → bool
            printf("lat=%.6f lon=%.6f alt=%.1f spd=%.2f fix=%d sats=%d\n",
                   gps.latitude(), gps.longitude(),                        // Read latitude/longitude, () → double °
                   gps.altitude(), gps.speed(),                            // Read altitude, () → float m ; speed() → float m/s
                   gps.fix_type(), gps.satellites());                      // Read fix type 0-3, () → int ; satellites() → int
        }
    }
    return 0;
}
