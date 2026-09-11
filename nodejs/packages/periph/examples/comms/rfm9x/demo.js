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
'use strict';

const { SPIConnection } = require('../../../src/connection/spi');
const { RFM95Full }     = require('../../../src/chips/comms/rfm9x');

const SPI_BUS  = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV  = parseInt(process.env.SPI_DEV || '0', 10);
const FREQ_HZ  = parseInt(process.env.RFM9X_FREQ || '868000000', 10);

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 0, maxSpeedHz: 5_000_000 });
    const radio = new RFM95Full(connection, FREQ_HZ);                                              // create RFM95W driver, (connection, frequencyHz=868e6) → RFM95Full
    await radio.init();

    // --- Configure for short-range link test ---
    // SF7 / 125 kHz / 4/5 keeps airtime low so the round-trip fits in a 1 s window;
    // +17 dBm on PA_BOOST gives enough link margin for desk-top loop-back.
    await radio.configure(7, 125.0, 5);                                                           // configure LoRa modem, (sf=7, bandwidthKhz=125.0, codingRate=5) → Promise<void>
    await radio.setTxPower(17, true);                                                              // set TX power, (powerDbm=17, usePaBoost=true) → Promise<void>

    let loss = 0;
    const total = 10;
    for (let n = 0; n < total; n++) {
        const tx = Buffer.alloc(4);
        tx.writeUInt32BE(n, 0);
        const t0 = Date.now();
        await radio.send(tx);                                                                      // send packet, (data=Buffer ≤255 B) → Promise<void>

        const rx = await radio.receive(1000);                                                      // receive single packet, (timeoutMs=1000) → Promise<Buffer | null>
        const t1 = Date.now();

        if (rx && rx.equals(tx)) {
            const rssi = await radio.lastPacketRssi();                                              // last packet RSSI, () → Promise<number> dBm
            const snr  = await radio.lastPacketSnr();                                               // last packet SNR, () → Promise<number> dB
            console.log(`[${n}] echo rtt=${t1 - t0} ms  rssi=${rssi.toFixed(1)}  snr=${snr.toFixed(1)}`);
        } else {
            loss++;
            console.log(`[${n}] no echo  pkt=${rx && rx.toString('hex')}`);
        }
        await new Promise(r => setTimeout(r, 200));
    }
    console.log(`done — ${total - loss}/${total} successful, ${loss} lost`);

    await connection.close();
})();
