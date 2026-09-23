#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "UARTConnectionLinux.h"
#include "NEO6.h"

int main() {
    const char* port = getenv("UART_PORT") ? getenv("UART_PORT") : "/dev/ttyS0";
    UARTConnectionLinux connection(port, 9600);

    NEO6Full gps(connection);                                               // Create NEO-6 driver, (connection, bus_type=Uart)

    // --- Track and log position every second ---
    // Waits for a valid GGA fix (quality >= 1) then logs lat/lon/alt/speed CSV.
    gps.setRate(1);                                                        // Set navigation update rate, (hz) → void
    gps.setPlatform(0);                                                    // Set dynamic platform model, (model 0-8) → void

    printf("lat,lon,alt_m,speed_m_s,sats\n");
    while (true) {
        if (!gps.update()) continue;                                       // Read + parse one NMEA sentence, () → bool
        if (gps.fix() < 1) continue;                                       // Read GGA fix quality, () → int 0=none 1=GPS 2=DGPS
        printf("%.6f,%.6f,%.1f,%.2f,%d\n",
               gps.latitude(), gps.longitude(),                            // Read latitude/longitude, () → float °
               gps.altitude(), gps.speed(),                                // Read altitude, () → float m ; speed() → float m/s
               gps.satellites());                                          // Read satellite count, () → int
    }
    return 0;
}
