/* MCP2515 demo — heartbeat loopback round-trip on the CAN bus.
 *
 * Hardware: a single MCP2515 wired up (e.g. paired with an MCP2551
 * transceiver on a real bus, or a second MCP2515 in loopback for
 * desk-top testing). The demo configures loopback mode so the chip
 * internally routes transmitted frames straight back into its own RX
 * buffers — no external CAN transceiver required for the round-trip
 * to succeed.
 *
 * Runs 10 TX/RX iterations: transmits a 4-byte uptime counter once
 * per second, immediately waits up to 1 s for the chip's own RX path
 * to deliver the frame back, and prints the received ID, DLC, and
 * data bytes (hex). Reports total successful round trips.
 */
'use strict';

const { SPIConnection } = require('../../../src/connection/spi');
const { MCP2515Full }    = require('../../../src/chips/comms/mcp2515');

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 0, maxSpeedHz: 10_000_000 });
    const chip = new MCP2515Full(connection, 125);                                                            // create MCP2515 full driver, (connection, bitrateKbps=125, oscMhz=8) → MCP2515Full

    // --- Configure for single-node loopback heartbeat ---
    // Loopback mode bypasses the physical CAN bus — the chip's TX path is
    // internally routed to RX, so a single MCP2515 can demonstrate the full
    // TX → RX round trip without an external transceiver. This is the same
    // mode the datasheet recommends for self-test / bench validation.
    await chip.setMode('loopback');                                                                           // switch to loopback mode, (mode='loopback') → Promise<void>
                                                                                                             // CANCTRL REQOP=010; chip stays on the bus but routes TX frames to its own RX

    let ok = 0;
    const total = 10;
    for (let n = 0; n < total; n++) {
        const tx = Buffer.alloc(4);
        tx.writeUInt32BE(n, 0);
        await chip.send(0x001, tx, false);                                                                   // send heartbeat frame, (id=0x001 std, data=4 B, extended=false) → Promise<void>
                                                                                                             // filled with the iteration counter, big-endian

        const frame = await chip.recv(1000);                                                                 // receive one CAN frame, (timeoutMs=1000) → Promise<CanFrame | null>
        if (frame && frame.id === 0x001 && frame.data.equals(tx)) {
            const hexId = '0x' + frame.id.toString(16).padStart(3, '0');
            const hexDlc = frame.data.length;
            const hexData = frame.data.toString('hex');
            const kind = frame.extended ? 'EXT' : (frame.rtr ? 'RTR' : 'STD');
            console.log(`[${n}] got ${kind} id=${hexId} dlc=${hexDlc} data=${hexData}`);
            ok++;
        } else {
            console.log(`[${n}] no round-trip  got=${frame ? frame.id : 'null'}`);
        }

        await new Promise(r => setTimeout(r, 1000));
    }

    // --- Return to Normal mode before exit ---
    // Leaving the chip in loopback would surprise the next user; switch
    // back to Normal so the bus is in a known state when this program ends.
    await chip.setMode('normal');                                                                            // switch to normal mode, (mode='normal') → Promise<void>

    console.log(`done — ${ok}/${total} round trips successful`);

    await connection.close();
})();
