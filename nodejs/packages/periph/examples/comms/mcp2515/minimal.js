'use strict';

const { SPIConnection } = require('../../../src/connection/spi');
const { MCP2515Minimal } = require('../../../src/chips/comms/mcp2515');

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);
const BITRATE = parseInt(process.env.MCP2515_BITRATE || '125', 10);

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 0, maxSpeedHz: 10_000_000 });           // open SPI bus, (busNumber, deviceNumber, mode=0, maxSpeedHz=10e6) → SPIConnection
    const chip = new MCP2515Minimal(connection, BITRATE);                                                     // create MCP2515 driver, (connection, bitrateKbps=125, oscMhz=8) → MCP2515Minimal
                                                                                                               // runs the chip's full init at construction (125 kbit/s @ 8 MHz, polled, accept-all)

    const data = Buffer.from([0x01, 0x02, 0x03, 0x04]);
    await chip.send(0x123, data);                                                                             // send CAN frame, (id=0x123 standard, data=4 B, extended=false) → Promise<void>
    console.log('sent id=0x123 data=' + data.toString('hex'));

    const frame = await chip.recv(1000);                                                                      // receive one CAN frame, (timeoutMs=1000) → Promise<CanFrame | null>
    if (frame) {
        console.log('received', frame);
    } else {
        console.log('no frame within 1000 ms');
    }

    await connection.close();
})();
