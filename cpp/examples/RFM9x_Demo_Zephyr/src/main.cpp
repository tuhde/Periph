#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/spi.h>
#include <stdio.h>
#include "SpiTransportZephyr.h"
#include "RFM9x.h"

#ifndef RFM9X_SPI_NODE
#define RFM9X_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef RFM9X_CS_PIN
#define RFM9X_CS_PIN 0
#endif
#ifndef RFM9X_RESET_PIN
#define RFM9X_RESET_PIN 0
#endif

// Configure for long-range desk link
// SF7 gives good balance of range and data rate; 125 kHz BW is ISM band default;
// 4/5 coding rate is standard; CRC ensures payload integrity.
int main(void) {
    const struct device* dev = DEVICE_DT_GET(RFM9X_SPI_NODE);
    if (!device_is_ready(dev)) {
        printf("SPI device not ready\n");
        return 1;
    }

    SpiTransportZephyr transport(dev, RFM9X_CS_PIN);
    RFM95Full rfm(transport, 868000000, RFM9X_RESET_PIN, 0);  // Create RFM95 driver, (transport, frequency_hz=868 MHz, reset_pin, dio0_pin)

    rfm.configure(7, 125, 5, true);                             // Configure modem, (sf, bandwidth_khz, coding_rate, crc) → void

    rfm.set_tx_power(17, true);                                // Set TX power, (power_dbm, use_pa_boost) → void

    // Ping-pong exchange loop
    // Send an incrementing counter, then wait up to 2s for an echo back.
    // print round-trip time, RSSI, and SNR for each successful exchange.
    uint16_t counter = 0;
    uint8_t successes = 0;
    uint8_t failures = 0;

    for (uint8_t i = 0; i < 10; i++) {
        uint8_t tx_payload[2] = { static_cast<uint8_t>((counter >> 8) & 0xFF), static_cast<uint8_t>(counter & 0xFF) };
        rfm.send(tx_payload, 2);                               // Transmit packet, (data, len) → void

        uint8_t len = 0;
        uint8_t* rx = rfm.receive(2000, &len);               // Receive packet, (timeout_ms, &len) → uint8_t* | nullptr

        if (rx && len > 0) {
            printf("seq=%u rssi=%.1f snr=%.1f\n", counter, rfm.last_packet_rssi(), rfm.last_packet_snr());
            successes++;
        } else {
            printf("seq=%u timeout\n", counter);
            failures++;
        }

        counter++;
        k_sleep(K_MSEC(100));
    }

    printf("=== %u success, %u lost ===\n", successes, failures);
    return 0;
}