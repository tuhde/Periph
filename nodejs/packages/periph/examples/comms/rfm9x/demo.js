'use strict';
const { SpiTransport } = require('../../src/transport/spi');
const { RFM95Full } = require('../../src/chips/comms/rfm9x');

const spiBus = parseInt(process.env.SPI_BUS || '0', 10);
const spiDevice = parseInt(process.env.SPI_DEVICE || '0', 10);
const csPin = parseInt(process.env.SPI_CS || '0', 10);
const resetPin = parseInt(process.env.RESET_PIN || '1', 10);

const transport = new SpiTransport(spiBus, spiDevice, csPin);
const rfm = new RFM95Full(transport, 868000000, resetPin, 0);

// --- Configure for long-range desk link ---
// SF7 gives good balance of range and data rate; 125 kHz BW is ISM band default;
// 4/5 coding rate is standard; CRC ensures payload integrity.
rfm.configure(7, 125, 5, true);                             // Configure modem, (sf, bandwidth_khz, coding_rate, crc) → void

// +17 dBm is safe maximum for PA_BOOST without active cooling.
rfm.set_tx_power(17, true);                                // Set TX power, (power_dbm, use_pa_boost) → void

// --- Ping-pong exchange loop ---
// Send an incrementing counter, then wait up to 2s for an echo back.
// print round-trip time, RSSI, and SNR for each successful exchange.
let counter = 0;
let successes = 0;
let failures = 0;

const run = () => {
    if (counter >= 10) {
        console.log('=== ' + successes + ' success, ' + failures + ' lost ===');
        transport.close();
        return;
    }

    const txPayload = Buffer.from([(counter >> 8) & 0xFF, counter & 0xFF]);
    const txStart = Date.now();
    rfm.send(txPayload);                                   // Transmit packet, (data: Buffer) → void

    const rx = rfm.receive(2000);                         // Receive packet, (timeout_ms) → Buffer | null

    if (rx) {
        console.log('seq=' + counter + ' rssi=' + rfm.last_packet_rssi().toFixed(1) + ' snr=' + rfm.last_packet_snr().toFixed(1));
        successes++;
    } else {
        console.log('seq=' + counter + ' timeout');
        failures++;
    }

    counter++;
    setTimeout(run, 100);
};

run();