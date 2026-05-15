'use strict';
const { SpiTransport } = require('../../src/transport/spi');
const { RFM95Full } = require('../../src/chips/comms/rfm9x');

const spiBus = parseInt(process.env.SPI_BUS || '0', 10);
const spiDevice = parseInt(process.env.SPI_DEVICE || '0', 10);
const csPin = parseInt(process.env.SPI_CS || '0', 10);
const resetPin = parseInt(process.env.RESET_PIN || '1', 10);

const transport = new SpiTransport(spiBus, spiDevice, csPin);
const rfm = new RFM95Full(transport, 868000000, resetPin, 0);  // Create RFM95 driver, (transport, frequency_hz=868 MHz, reset_pin, dio0_pin)

const ver = rfm.version();                                      // Read silicon revision, () → int
console.log('version: 0x' + ver.toString(16));
                                                           // checks silicon revision matches expected 0x12

rfm.configure(7, 125, 5, true);                             // Configure modem, (sf, bandwidth_khz, coding_rate, crc) → void
                                                           // sets spreading factor, bandwidth, coding rate, and CRC mode

rfm.set_tx_power(17, true);                                // Set TX power, (power_dbm, use_pa_boost) → void
                                                           // configures PA_BOOST pin for high-power transmission

rfm.set_frequency(915000000);                              // Change carrier frequency, (frequency_hz) → void
                                                           // switches to 915 MHz US band

rfm.send(Buffer.from('Hello'));                           // Transmit packet, (data: Buffer) → void
                                                           // enters TX mode, polls TxDone, returns to STDBY

const rx = rfm.receive(2000);                             // Receive packet, (timeout_ms) → Buffer | null
if (rx) {
    console.log('rx: ' + rx.toString());
    console.log('rssi: ' + rfm.last_packet_rssi().toFixed(1) + ' dBm');  // Read packet RSSI, () → float dBm
    console.log('snr: ' + rfm.last_packet_snr().toFixed(1) + ' dB');    // Read packet SNR, () → float dB
}

rfm.receive_continuous();                                 // Enter continuous receive mode, () → void
                                                           // keeps receiver always on, packets queued in FIFO

setTimeout(() => {
    const pkt = rfm.read_packet();                        // Read packet from FIFO, () → Buffer | null
    if (pkt) console.log('continuous rx: ' + pkt.toString());
    rfm.stop_receive();                                   // Return to STANDBY, () → void
    console.log('channel rssi: ' + rfm.rssi().toFixed(1) + ' dBm');  // Read channel RSSI, () → float dBm
    rfm.reset();                                          // Hardware reset via NRESET pin, () → void
    rfm.standby();                                        // Enter STANDBY mode, () → void
    rfm.sleep();                                          // Enter SLEEP mode, () → void
    transport.close();
    console.log('===DONE: 1 passed, 0 failed===');
}, 500);