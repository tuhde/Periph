#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/spi_master.h"
#include "esp_timer.h"
#include "SPIConnectionESPIDF.h"
#include "RFM9x.h"

static const int MOSI_PIN = 23;
static const int MISO_PIN = 19;
static const int SCLK_PIN = 18;
static const int CS_PIN   = 5;

extern "C" void app_main(void) {
    spi_bus_config_t bus_cfg = {};
    bus_cfg.mosi_io_num   = MOSI_PIN;
    bus_cfg.miso_io_num   = MISO_PIN;
    bus_cfg.sclk_io_num   = SCLK_PIN;
    bus_cfg.quadwp_io_num = -1;
    bus_cfg.quadhd_io_num = -1;
    spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);

    spi_device_interface_config_t dev_cfg = {};
    dev_cfg.mode            = 0;
    dev_cfg.clock_speed_hz  = 5000000;
    dev_cfg.spics_io_num    = CS_PIN;
    dev_cfg.queue_size      = 1;
    spi_device_handle_t dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &dev);

    SPIConnectionESPIDF connection(dev);                 // Create SPI connection, (dev) → SPIConnectionESPIDF
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
        vTaskDelay(pdMS_TO_TICKS(50));
    }
    radio.stop_receive();                                // Leave continuous RX, () → void
                                                         // returns to STDBY

    radio.sleep();                                       // Enter SLEEP mode, () → void
                                                         // park the radio in its lowest-power mode
}
