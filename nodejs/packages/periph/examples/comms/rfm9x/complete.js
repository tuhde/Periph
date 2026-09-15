'use strict';

const { SPIConnection } = require('../../../src/connection/spi');
const { RFM95Full }     = require('../../../src/chips/comms/rfm9x');

const SPI_BUS  = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV  = parseInt(process.env.SPI_DEV || '0', 10);
const FREQ_HZ  = parseInt(process.env.RFM9X_FREQ || '868000000', 10);

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 0, maxSpeedHz: 5_000_000 });    // open SPI bus, (busNumber, deviceNumber, options) → SPIConnection
    const radio = new RFM95Full(connection, FREQ_HZ);                                              // create RFM95W full driver, (connection, frequencyHz=868e6) → RFM95Full
    await radio.init();

    const ver = await radio.version();                                                             // read silicon version, () → Promise<number>
                                                                                                  // expect 0x12 (SX1276)
    console.log('version:', '0x' + ver.toString(16));

    await radio.configure(7, 125.0, 5);                                                           // configure LoRa modem, (sf=6–12, bandwidthKhz=7.8–500, codingRate=5–8, crc=true) → Promise<void>
                                                                                                  // sets SF=7, BW=125 kHz, CR 4/5
    await radio.setTxPower(17, true);                                                              // set TX power, (powerDbm=2–20, usePaBoost=true) → Promise<void>
                                                                                                  // PA_BOOST pin, +17 dBm

    await radio.setFrequency(FREQ_HZ);                                                             // change carrier frequency, (frequencyHz=862e6–1020e6) → Promise<void>
    await radio.standby();                                                                         // enter STDBY mode, () → Promise<void>

    await radio.send(Buffer.from('hello world'));                                                  // send packet, (data=Buffer ≤255 B) → Promise<void>
                                                                                                  // STDBY → fill FIFO → TX → poll TxDone → STDBY

    const pkt = await radio.receive(2000);                                                         // receive single packet, (timeoutMs=2000) → Promise<Buffer | null>
                                                                                                  // RXSINGLE → poll RxDone/RxTimeout → read FIFO
    if (pkt) {
        const rssi = await radio.lastPacketRssi();                                                 // last packet RSSI, () → Promise<number> dBm
        const snr  = await radio.lastPacketSnr();                                                  // last packet SNR, () → Promise<number> dB
        console.log('packet:', pkt.toString('hex'), 'rssi=', rssi, 'snr=', snr);
    }

    await radio.receiveContinuous();                                                               // enter continuous RX, () → Promise<void>
    const buf = await radio.readPacket();                                                          // read buffered packet, () → Promise<Buffer | null>
                                                                                                  // drains FIFO when RxDone fires
    if (buf) console.log('rx:', buf);
    await radio.stopReceive();                                                                     // return to STDBY from RX_CONT, () → Promise<void>

    await radio.sleep();                                                                           // enter SLEEP mode, () → Promise<void>
                                                                                                  // lowest power; FIFO inaccessible
    await new Promise(r => setTimeout(r, 250));
    await radio.standby();

    await connection.close();
})();
