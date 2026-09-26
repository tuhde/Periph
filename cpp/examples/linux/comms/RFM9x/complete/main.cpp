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

int main() {
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, 0, 5000000); // Create SPI connection, (bus=0, device=0, mode=0, max_speed_hz=5e6 Hz) → SPIConnectionLinux
    RFM95Full radio(connection, 868000000);              // Create RFM95W full driver, (connection, frequency_hz=868e6 Hz) → RFM95Full
                                                         // runs the LoRa init sequence: SF7 / 125 kHz / CR 4/5, CRC on, +17 dBm

    radio.reset();                                       // Reset radio registers, () → void
                                                         // re-runs the LoRa init sequence; pulse NRESET low > 100 µs first if it is wired
    uint8_t ver = radio.version();                       // Read silicon version, () → uint8_t
                                                         // expect 0x12 (SX1276); 0x00 or 0xFF points to a wiring or SPI problem
    printf("version=0x%02X\n", ver);

    radio.sleep();                                       // Enter SLEEP mode, () → void
                                                         // lowest-power mode; FIFO is not accessible
    radio.standby();                                     // Enter STDBY mode, () → void
                                                         // oscillator running; required before FIFO access or a frequency change
    radio.set_frequency(868100000);                      // Change carrier frequency, (frequency_hz=862e6–1020e6 Hz) → void
                                                         // writes RegFrf (61.035 Hz steps); only allowed in SLEEP or STDBY
    radio.configure(9, 125.0f, 5, true);                 // Configure LoRa modem, (sf=6–12, bandwidth_khz=7.8–500 kHz, coding_rate=5–8, crc=true) → void
                                                         // SF9 / 125 kHz / CR 4/5 with payload CRC; SF6 switches to implicit header
    radio.set_tx_power(14, true);                        // Set TX power, (power_dbm=2–20 dBm, use_pa_boost=true) → void
                                                         // PA_BOOST path at +14 dBm; +20 dBm also enables RegPaDac high-power mode

    const uint8_t msg[] = "hello";
    radio.send(msg, sizeof(msg) - 1);                    // Send packet, (data, len ≤ 255) → void
                                                         // STDBY → fill FIFO → TX → poll TxDone → STDBY

    uint8_t buf[255];
    size_t len = 0;
    if (radio.receive(buf, len, 2000)) {                 // Receive single packet, (buf, len, timeout_ms=2000 ms) → bool
                                                         // RXSINGLE; true on RxDone, false on timeout
        float pkt_rssi = radio.last_packet_rssi();       // Last packet RSSI, () → float dBm
                                                         // RegPktRssiValue − 137
        float pkt_snr = radio.last_packet_snr();         // Last packet SNR, () → float dB
                                                         // signed RegPktSnrValue / 4
        printf("rx %u B  rssi=%.1f dBm  snr=%.1f dB\n", (unsigned)len, pkt_rssi, pkt_snr);
    } else {
        printf("rx timeout\n");
    }

    radio.receive_continuous();                          // Enter continuous RX, () → void
                                                         // receiver stays on; each packet is buffered in the FIFO until read
    for (int i = 0; i < 100; i++) {                      // listen for about 5 s
        if (radio.read_packet(buf, len)) {               // Read buffered packet, (buf, len) → bool
                                                         // false if no packet has arrived since the last call
            printf("got %u B\n", (unsigned)len);
        }
        if (i % 20 == 0) {
            float ch_rssi = radio.rssi();                // Current channel RSSI, () → float dBm
                                                         // live RegRssiValue − 137; only meaningful while receiving
            printf("channel rssi=%.1f dBm\n", ch_rssi);
        }
        usleep(50 * 1000);
    }
    radio.stop_receive();                                // Leave continuous RX, () → void
                                                         // returns to STDBY

    radio.sleep();                                       // Enter SLEEP mode, () → void
                                                         // park the radio in its lowest-power mode
    return 0;
}
