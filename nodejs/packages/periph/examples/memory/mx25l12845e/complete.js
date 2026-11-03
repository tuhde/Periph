"use strict";
// Wiring: SPI0 (MOSI, MISO, SCK, CE0) to the MX25L12845E, 3.3 V. Override with SPI_BUS, SPI_DEV.
// All erases and writes stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
const { SPIConnection } = require('../../../src/connection/spi');                                // SPIConnection class, (bus, dev, options) → SPIConnection
const { MX25L12845EFull } = require('../../../src/chips/memory/mx25l12845e');                           // MX25L12845EFull class, (connection, options) → MX25L12845EFull

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);
const SCRATCH = 0xFF0000;
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const hex = (b) => Buffer.from(b).toString('hex').match(/../g).join(' ');

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 0, maxSpeedHz: 8_000_000 });  // Open SPI bus, (busNumber, deviceNumber, mode=0, maxSpeedHz=8e6) → SPIConnection
    const flash = new MX25L12845EFull(connection);                                              // Create MX25L12845E driver, (connection, options{autoErase=false}) → MX25L12845EFull
    await flash.init();                                                                         // Release deep power-down, check the JEDEC ID, () → Promise<void>

    const id = await flash.jedecId();                                                           // Read JEDEC ID, () → Promise<Buffer>
                                                                                                // C2 20 18 for the MX25L12845E
    const res = await flash.electronicId();                                                     // Read the old-style electronic ID (RES), () → Promise<number>
    const [man, dev] = await flash.deviceIds();                                                 // Read manufacturer + device ID (REMS), () → Promise<[number, number]>
    console.log(`JEDEC ${hex(id)}, RES ${res.toString(16)}, REMS ${man.toString(16)} ${dev.toString(16)}`);

    const sr = await flash.readStatus();                                                        // Read the status register, () → Promise<number>
    const bp = await flash.protection();                                                        // Read BP3:0, () → Promise<number>
    const scur = await flash.readSecurity();                                                    // Read the security register, () → Promise<number>
    console.log(`status 0x${sr.toString(16)}, BP=${bp}, security 0x${scur.toString(16)}`);
    console.log('ESN:', hex(await flash.serialNumber()));                                       // Read the serial-number OTP area, () → Promise<Buffer>
                                                                                                // the factory ESN when security bit 0 is set, blank (FF) otherwise
    console.log('SFDP:', hex(await flash.readSfdp(0, 16)));                                     // Read the SFDP table, (address, length) → Promise<Buffer>
                                                                                                // starts with the signature "SFDP"

    await flash.eraseSector(SCRATCH);                                                           // Erase one 4 KiB sector, (address) → Promise<void>
                                                                                                // takes 60 ms typ, 300 ms max
    const page = Buffer.from(Array.from({ length: 256 }, (_, i) => i));
    await flash.pageProgram(SCRATCH, page);                                                     // Program one page (must not cross a page boundary), (address, data) → Promise<void>
                                                                                                // 1.4 ms typ for 256 bytes; bits only go 1 -> 0
    const back = await flash.fastRead(SCRATCH, 256);                                            // FAST_READ, (address, length) → Promise<Buffer>
                                                                                                // same data as readBytes, for clocks above 50 MHz
    console.log('page program + fast_read:', page.equals(back));

    await flash.eraseBlock32k(SCRATCH);                                                         // Erase a 32 KiB block, (address) → Promise<void>
                                                                                                // 0.5 s typ
    await flash.eraseBlock64k(SCRATCH);                                                         // Erase a 64 KiB block, (address) → Promise<void>
                                                                                                // 0.7 s typ
    console.log('after block erase:', hex(await flash.readBytes(SCRATCH, 16)));                 // Read bytes, (address, length) → Promise<Buffer>
    // chipErase() (80 s typ, wipes all 16 MiB) is deliberately not called here.

    const text = Buffer.from('erase-aware write');
    await flash.writeBytes(SCRATCH + 100, text);                                                // Write bytes, erasing the sector only when needed, (address, data) → Promise<void>
    await flash.writeBytes(SCRATCH + 100, text);                                                // the same bytes again: nothing is programmed or erased
    console.log('text:', (await flash.readBytes(SCRATCH + 100, text.length)).toString());

    await flash.deepPowerDown();                                                                // Enter deep power-down, () → Promise<void>
                                                                                                // only RDP / RES are accepted afterwards
    await flash.releaseDeepPowerDown();                                                         // Leave deep power-down (RDP, waits tRES2), () → Promise<void>
    console.log('busy:', await flash.isBusy());                                                 // WIP bit, () → Promise<boolean>
    await flash.waitReady(1000);                                                                // Wait for WIP = 0, (timeoutMs) → Promise<void>

    console.log(flash.info());                                                                  // Geometry and capabilities, () → object
                                                                                                // 512-byte blocks, 4 KiB erase unit, needsErase
    // setProtection(), writeStatus(), programOtp() and lockOtp() change non-volatile state; see the spec before using them.

    await flash.eraseSector(SCRATCH);
    console.log('done');
})();
