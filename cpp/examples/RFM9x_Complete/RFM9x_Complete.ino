#include <SPI.h>
#include "SPIConnection.h"
#include "RFM9x.h"

SPISettings settings(5000000, MSBFIRST, SPI_MODE0);
SPIConnection connection(SPI, SS, settings);             // Create SPI connection, (bus, cs_pin, settings) → SPIConnection
RFM95Full radio(connection, 868000000);                 // Create RFM95W full driver, (connection, frequency_hz=868e6) → RFM95Full

void setup() {
    Serial.begin(115200);
    SPI.begin();

    uint8_t ver = radio.version();                      // Read silicon version, () → uint8_t
                                                         // expect 0x12 (SX1276)
    Serial.print("version=0x"); Serial.println(ver, HEX);

    radio.configure(7, 125.0, 5);                       // Configure LoRa modem, (sf=6–12, bandwidth_khz=7.8–500, coding_rate=5–8, crc=true) → void
                                                         // sets SF=7, BW=125 kHz, CR 4/5

    radio.set_tx_power(17, true);                       // Set TX power, (power_dbm=2–20, use_pa_boost=true) → void
                                                         // PA_BOOST pin, +17 dBm

    radio.set_frequency(868000000);                     // Change carrier frequency, (frequency_hz=862e6–1020e6) → void

    radio.standby();                                    // Enter STDBY mode, () → void

    radio.send((const uint8_t*)"hello", 5);             // Send packet, (data, len) → void
                                                         // STDBY → fill FIFO → TX → poll TxDone → STDBY

    uint8_t buf[64]; size_t got = 0;
    bool ok = radio.receive(buf, got, 2000);            // Receive single packet, (buf, len, timeout_ms=2000) → bool
                                                         // true on RxDone, false on timeout
    if (ok) {
        float rssi = radio.last_packet_rssi();          // Last packet RSSI, () → float dBm
        float snr  = radio.last_packet_snr();           // Last packet SNR, () → float dB
        Serial.print("rx "); Serial.print((int)got); Serial.print(" B  rssi="); Serial.print(rssi);
        Serial.print("  snr="); Serial.println(snr);
    } else {
        Serial.println("rx timeout");
    }

    radio.receive_continuous();                         // Enter continuous RX, () → void
    while (true) {
        size_t n = 0;
        if (radio.read_packet(buf, n)) {                // Read buffered packet, (buf, len) → bool
            float rssi = radio.rssi();                  // Current channel RSSI, () → float dBm
            Serial.print("got "); Serial.print((int)n); Serial.print(" B  rssi="); Serial.println(rssi);
        }
        delay(50);
    }
    radio.stop_receive();                               // Return to STDBY from RX_CONT, () → void

    radio.sleep();                                      // Enter SLEEP mode, () → void
    delay(250);
    radio.standby();
}

void loop() {}
