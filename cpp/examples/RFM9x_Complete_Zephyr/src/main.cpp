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

int main(void) {
    const struct device* dev = DEVICE_DT_GET(RFM9X_SPI_NODE);
    if (!device_is_ready(dev)) {
        printf("SPI device not ready\n");
        return 1;
    }

    SpiTransportZephyr transport(dev, RFM9X_CS_PIN);
    RFM95Full rfm(transport, 868000000, RFM9X_RESET_PIN, 0);  // Create RFM95 driver, (transport, frequency_hz=868 MHz, reset_pin, dio0_pin)

    uint8_t ver = rfm.version();                               // Read silicon revision, () → uint8_t
    printf("version: 0x%02X\n", ver);
                                                            // checks silicon revision matches expected 0x12

    rfm.configure(7, 125, 5, true);                             // Configure modem, (sf, bandwidth_khz, coding_rate, crc) → void
                                                            // sets spreading factor, bandwidth, coding rate, and CRC mode

    rfm.set_tx_power(17, true);                                // Set TX power, (power_dbm, use_pa_boost) → void
                                                            // configures PA_BOOST pin for high-power transmission

    rfm.set_frequency(915000000);                              // Change carrier frequency, (frequency_hz) → void
                                                            // switches to 915 MHz US band

    rfm.send(reinterpret_cast<const uint8_t*>("Hello"), 5);    // Transmit packet, (data, len) → void
                                                            // enters TX mode, polls TxDone, returns to STDBY

    uint8_t len = 0;
    uint8_t* pkt = rfm.receive(2000, &len);                   // Receive packet, (timeout_ms, &len) → uint8_t* | nullptr
    if (pkt && len > 0) {
        printf("rx: ");
        for (uint8_t i = 0; i < len; i++) printf("%c", pkt[i]);
        printf("\n");
        printf("rssi: %.1f dBm\n", rfm.last_packet_rssi());   // Read packet RSSI, () → float dBm
        printf("snr: %.1f dB\n", rfm.last_packet_snr());      // Read packet SNR, () → float dB
    }

    rfm.receive_continuous();                                 // Enter continuous receive mode, () → void
                                                            // keeps receiver always on, packets queued in FIFO

    k_sleep(K_MSEC(500));
    uint8_t cont_len = 0;
    uint8_t* cont_pkt = rfm.read_packet(&cont_len);          // Read packet from FIFO, (&len) → uint8_t* | nullptr
    if (cont_pkt && cont_len > 0) {
        printf("continuous rx: ");
        for (uint8_t i = 0; i < cont_len; i++) printf("%c", cont_pkt[i]);
        printf("\n");
    }

    rfm.stop_receive();                                       // Return to STANDBY, () → void

    printf("channel rssi: %.1f dBm\n", rfm.rssi());          // Read channel RSSI, () → float dBm

    rfm.reset();                                              // Hardware reset via NRESET pin, () → void

    rfm.standby();                                            // Enter STANDBY mode, () → void

    rfm.sleep();                                              // Enter SLEEP mode, () → void

    printf("===DONE: 1 passed, 0 failed===\n");
    return 0;
}