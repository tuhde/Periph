/* RFM95W demo — two-node round-trip link test.
 *
 * Hardware: two RFM95W modules, one running this program and one running a
 * receive-and-echo loop. Both are configured for 868 MHz, SF=7, BW=125 kHz,
 * CR 4/5 (use RFM96Full at 433 MHz for the low-band RFM96/98 modules).
 *
 * Runs 10 TX/RX iterations: transmits an incrementing 4-byte big-endian
 * counter, then waits up to 1 s for the peer to echo it back. Prints the
 * round-trip time and per-packet RSSI/SNR on success, then reports the
 * total packet loss.
 */
#include <cstdio>
#include <ctime>
#include <unistd.h>
#include "SPIConnectionLinux.h"
#include "RFM9x.h"

#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
#endif

static long long now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000L;
}

int main() {
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, 0, 5000000); // Create SPI connection, (bus=0, device=0, mode=0, max_speed_hz=5e6 Hz) → SPIConnectionLinux
    RFM95Full radio(connection, 868000000);              // Create RFM95W driver, (connection, frequency_hz=868e6 Hz) → RFM95Full

    // --- Configure for a short-range link test ---
    // SF7 / 125 kHz / 4/5 keeps airtime low (about 40 ms for 4 bytes) so the
    // round trip fits in a 1 s window; +17 dBm on PA_BOOST gives plenty of
    // link margin for two modules on the same desk.
    radio.configure(7, 125.0f, 5);                       // Configure LoRa modem, (sf=7, bandwidth_khz=125.0 kHz, coding_rate=5) → void
    radio.set_tx_power(17, true);                        // Set TX power, (power_dbm=17 dBm, use_pa_boost=true) → void

    // --- Ping-pong loop ---
    // Each iteration sends the counter and immediately listens for the echo.
    // A missing or corrupted echo counts as a lost packet; the RSSI and SNR of
    // good echoes show the link quality in the return direction.
    const unsigned total = 10;
    unsigned loss = 0;
    for (unsigned n = 0; n < total; n++) {
        uint8_t tx[4] = { (uint8_t)(n >> 24), (uint8_t)(n >> 16), (uint8_t)(n >> 8), (uint8_t)n };

        long long t0 = now_ms();
        radio.send(tx, 4);                               // Send packet, (data, len=4) → void

        uint8_t rx[255];
        size_t got = 0;
        bool ok = radio.receive(rx, got, 1000);          // Receive single packet, (buf, len, timeout_ms=1000 ms) → bool
        long long t1 = now_ms();

        if (ok && got == 4 && rx[0] == tx[0] && rx[1] == tx[1] && rx[2] == tx[2] && rx[3] == tx[3]) {
            float rssi = radio.last_packet_rssi();       // Last packet RSSI, () → float dBm
            float snr = radio.last_packet_snr();         // Last packet SNR, () → float dB
            printf("[%u] echo rtt=%lld ms  rssi=%.1f dBm  snr=%.1f dB\n", n, t1 - t0, rssi, snr);
        } else {
            loss++;
            printf("[%u] no echo\n", n);
        }
        usleep(200 * 1000);
    }

    // --- Summary ---
    // Packet loss over the run is the headline number for link reliability.
    printf("done: %u/%u successful, %u lost\n", total - loss, total, loss);
    return 0;
}
