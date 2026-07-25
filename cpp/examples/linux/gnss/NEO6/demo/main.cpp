#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "UARTTransportLinux.h"
#include "NEO6.h"

int main() {
    const char* port = getenv("UART_PORT") ? getenv("UART_PORT") : "/dev/ttyS0";
    UARTTransportLinux transport(port, 9600);

    NEO6Full gps(transport);                                               // Create NEO-6 driver, (transport, bus_type=Uart)

    // --- Track and log position every second ---
    // Waits for a valid fix (fix_type >= 2) then logs lat/lon/alt/speed CSV.
    gps.setRate(1);                                                        // Set navigation update rate, (hz) → void
    gps.setPlatform(0);                                                    // Set dynamic platform model, (model 0-8) → void

    printf("lat,lon,alt_m,speed_m_s,sats\n");
    while (true) {
        if (!gps.update()) continue;                                       // Read + parse one NMEA sentence, () → bool
        if (gps.fix_type() < 2) continue;                                  // Read fix type 0-3, () → int
        printf("%.6f,%.6f,%.1f,%.2f,%d\n",
               gps.latitude(), gps.longitude(),                            // Read latitude/longitude, () → double °
               gps.altitude(), gps.speed(),                                // Read altitude, () → float m ; speed() → float m/s
               gps.satellites());                                          // Read satellite count, () → int
    }
    return 0;
}
