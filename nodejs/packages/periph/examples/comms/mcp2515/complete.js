'use strict';

const { SPIConnection } = require('../../../src/connection/spi');
const { MCP2515Full }    = require('../../../src/chips/comms/mcp2515');

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);
const BITRATE = parseInt(process.env.MCP2515_BITRATE || '125', 10);

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 0, maxSpeedHz: 10_000_000 });           // open SPI bus, (busNumber, deviceNumber, mode=0, maxSpeedHz=10e6) → SPIConnection
    const chip = new MCP2515Full(connection, BITRATE);                                                       // create MCP2515 full driver, (connection, bitrateKbps=125, oscMhz=8) → MCP2515Full

    const mode = await chip.getMode();                                                                       // read current operating mode, () → Promise<string>
                                                                                                             // 'normal' after the constructor's init sequence
    console.log('mode:', mode);

    await chip.setFilter(0, 0x123, false);                                                                   // configure filter 0, (filterNum=0, id=0x123, extended=false) → Promise<void>
                                                                                                             // matches standard ID 0x123 only (when used with the right mask)
    await chip.setMask(0, 0x7FF, false);                                                                     // configure mask 0, (maskNum=0, mask=0x7FF, extended=false) → Promise<void>
                                                                                                             // forces exact match on the 11-bit standard ID
    await chip.setRxMode(0, 0);                                                                              // set RX buffer 0 filter mode, (buf=0, mode=0) → Promise<void>
                                                                                                             // 0 = accept standard IDs through filters/masks; 3 would accept-all

    const data = Buffer.from([0xAA, 0xBB, 0xCC, 0xDD]);
    await chip.sendBuffered(0x123, data, false, 0);                                                          // send on TX buffer 0, (id=0x123, data=4 B, extended=false, buf=0) → Promise<void>
                                                                                                             // explicit buffer; 0/1/2 selectable; 0 wins ties at equal priority
    await chip.send(0x456, Buffer.from([0x01, 0x02]), false);                                                // send on next free buffer, (id=0x456, data=2 B, extended=false) → Promise<void>
                                                                                                             // defaults to the first free of TXB0/TXB1/TXB2

    await chip.send(0x18FF1234, Buffer.from([0x10, 0x20, 0x30, 0x40]), true);                               // send extended frame, (id=0x18FF1234 29-bit, data=4 B, extended=true) → Promise<void>
                                                                                                             // EXIDE bit set in TXBnSIDL; full 29-bit identifier

    const extendedFilterId = 0x18FF1234;
    await chip.setFilter(2, extendedFilterId, true);                                                         // configure filter 2, (filterNum=2, id=0x18FF1234, extended=true) → Promise<void>
                                                                                                             // matches the 29-bit extended ID exactly
    await chip.setMask(1, 0x1FFFFFFF, true);                                                                 // configure mask 1, (maskNum=1, mask=0x1FFFFFFF, extended=true) → Promise<void>
                                                                                                             // requires all 29 extended-ID bits to match
    await chip.setRxMode(1, 1);                                                                              // set RX buffer 1 filter mode, (buf=1, mode=1) → Promise<void>
                                                                                                             // 1 = accept extended IDs through filters/masks

    await chip.setOneShot(true);                                                                             // enable one-shot mode, (enable=true) → Promise<void>
                                                                                                             // OSM in CANCTRL set; no retransmit on error or arbitration loss
    await chip.setOneShot(false);                                                                            // disable one-shot mode, (enable=false) → Promise<void>
                                                                                                             // OSM cleared; default retransmit behaviour

    await chip.setMode('loopback');                                                                          // switch to loopback mode, (mode='loopback') → Promise<void>
                                                                                                             // TX frames are internally routed to RX; useful for self-test without a bus

    const loopbackFrame = await chip.recv(1000);                                                             // receive one CAN frame, (timeoutMs=1000) → Promise<CanFrame | null>
    if (loopbackFrame) {
        console.log('loopback:', loopbackFrame);
    }

    await chip.setMode('normal');                                                                            // switch to normal mode, (mode='normal') → Promise<void>
                                                                                                             // required for actual bus participation

    const errors = await chip.readErrors();                                                                  // read error counters, () → Promise<{tec,rec,eflg}>
                                                                                                             // tec/rec are TX/RX error counters; eflg is the EFLG register
    console.log('errors:', errors);

    const frame = await chip.recv(500);                                                                      // receive one CAN frame, (timeoutMs=500) → Promise<CanFrame | null>
    if (frame) {
        console.log('received', frame);
        await chip.clearOverflow(0);                                                                         // clear RX0 overflow flag, (buf=0) → Promise<void>
                                                                                                             // only relevant if the previous RX triggered RX0OVR
    }

    await chip.abortTx();                                                                                    // abort all pending TX, () → Promise<void>
                                                                                                             // sets ABAT in CANCTRL; waits for it to clear (max 500 ms)
    await chip.reset();                                                                                      // issue SPI RESET, () → Promise<void>
                                                                                                             // all registers return to POR defaults; run init() again

    await connection.close();
})();
