/* RFM95W demo — two-node round-trip link test.
 *
 * Hardware: two RFM95W modules wired back-to-back (or two boards running
 * the same code). Both configured for 868 MHz, SF=7, BW=125 kHz, CR 4/5.
 *
 * Runs 10 TX/RX iterations: transmits an incrementing 4-byte counter, then
 * immediately waits up to 1 s for the peer to echo it back. Prints the
 * round-trip time and per-packet RSSI/SNR on success, then reports the
 * total packet loss.
 */
#include <SPI.h>
#include "SPIConnection.h"
#include "RFM9x.h"

SPISettings settings(5000000, MSBFIRST, SPI_MODE0);
SPIConnection connection(SPI, SS, settings);
RFM95Full radio(connection, 868000000);                 // Create RFM95W driver, (connection, frequency_hz=868e6) → RFM95Full

void setup() {
    Serial.begin(115200);
    SPI.begin();

    // --- Configure for short-range link test ---
    // SF7 / 125 kHz / 4/5 keeps airtime low so the round-trip fits in a 1 s window;
    // +17 dBm on PA_BOOST gives enough link margin for desk-top loop-back.
    radio.configure(7, 125.0, 5);                       // Configure LoRa modem, (sf=7, bandwidth_khz=125.0, coding_rate=5) → void
    radio.set_tx_power(17, true);                       // Set TX power, (power_dbm=17, use_pa_boost=true) → void

    uint32_t loss = 0;
    const uint32_t total = 10;
    for (uint32_t n = 0; n < total; n++) {
        uint8_t tx[4];
        tx[0] = (uint8_t)(n >> 24);
        tx[1] = (uint8_t)(n >> 16);
        tx[2] = (uint8_t)(n >>  8);
        tx[3] = (uint8_t)(n);

        unsigned long t0 = millis();
        radio.send(tx, 4);                              // Send packet, (data, len) → void

        uint8_t rx[4]; size_t got = 0;
        bool ok = radio.receive(rx, got, 1000);         // Receive single packet, (buf, len, timeout_ms=1000) → bool
        unsigned long t1 = millis();

        if (ok && got == 4 && rx[0] == tx[0] && rx[1] == tx[1] && rx[2] == tx[2] && rx[3] == tx[3]) {
            float rssi = radio.last_packet_rssi();      // Last packet RSSI, () → float dBm
            float snr  = radio.last_packet_snr();       // Last packet SNR, () → float dB
            Serial.print("["), Serial.print(n), Serial.print("] echo rtt="), Serial.print((unsigned long)(t1 - t0));
            Serial.print(" ms  rssi="), Serial.print(rssi), Serial.print("  snr="), Serial.println(snr);
        } else {
            loss++;
            Serial.print("["), Serial.print(n), Serial.println("] no echo");
        }
        delay(200);
    }
    Serial.print("done — "); Serial.print(total - loss);
    Serial.print("/"); Serial.print(total);
    Serial.print(" successful, "); Serial.print(loss); Serial.println(" lost");
}

void loop() {}
