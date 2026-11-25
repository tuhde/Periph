#include "MX25L12845E.h"
#include <string.h>

// ---- helpers ---------------------------------------------------------------

static inline void put_address(uint8_t* p, uint32_t a) {
    p[0] = static_cast<uint8_t>(a >> 16);
    p[1] = static_cast<uint8_t>(a >> 8);
    p[2] = static_cast<uint8_t>(a);
}

void MX25L12845EMinimal::_sleep(uint32_t ms) {
    if (_opt.delay_ms) _opt.delay_ms(ms);
}

BlockError MX25L12845EMinimal::_command(uint8_t cmd) {
    _c.write(&cmd, 1);
    return _c.ok() ? BlockError::Ok : BlockError::Io;
}

BlockError MX25L12845EMinimal::_readStatus(uint8_t* sr) {
    uint8_t cmd = CMD_RDSR;
    _c.write_read(&cmd, 1, sr, 1);
    return _c.ok() ? BlockError::Ok : BlockError::Io;
}

BlockError MX25L12845EMinimal::_wren() {
    BlockError e = _command(CMD_WREN);
    if (e != BlockError::Ok) return e;
    uint8_t sr = 0;
    e = _readStatus(&sr);
    if (e != BlockError::Ok) return e;
    return (sr & SR_WEL) ? BlockError::Ok : BlockError::Io;   // WEL must be set or the next command is ignored
}

BlockError MX25L12845EMinimal::_waitReady(uint32_t timeoutMs) {
    uint32_t start = _opt.now_ms ? _opt.now_ms() : 0;
    uint32_t polls = 0;
    while (true) {
        uint8_t sr = 0;
        BlockError e = _readStatus(&sr);
        if (e != BlockError::Ok) return e;
        if (!(sr & SR_WIP)) return BlockError::Ok;
        if (_opt.now_ms) {
            if (static_cast<uint32_t>(_opt.now_ms() - start) >= timeoutMs) return BlockError::Timeout;
            if (timeoutMs > 20) _sleep(1);   // long operations: do not hammer the bus
        } else if (++polls > timeoutMs * 8) {
            return BlockError::Timeout;      // no clock: ~8 polls per millisecond budget
        }
    }
}

BlockError MX25L12845EMinimal::_checkFail() {
    uint8_t cmd = CMD_RDSCUR, scur = 0;
    _c.write_read(&cmd, 1, &scur, 1);
    if (!_c.ok()) return BlockError::Io;
    if (!(scur & (SCUR_P_FAIL | SCUR_E_FAIL))) return BlockError::Ok;
    _command(CMD_CLSR);
    uint8_t sr = 0;
    _readStatus(&sr);
    return (sr & SR_BP_MASK) ? BlockError::WriteProtected : BlockError::Io;
}

BlockError MX25L12845EMinimal::_eraseCmd(uint8_t cmd, uint32_t address, uint32_t timeoutMs) {
    BlockError e = _wren();
    if (e != BlockError::Ok) return e;
    uint8_t frame[4] = {cmd};
    put_address(frame + 1, address);
    _c.write(frame, 4);
    if (!_c.ok()) return BlockError::Io;
    e = _waitReady(timeoutMs);
    if (e != BlockError::Ok) return e;
    return _checkFail();
}

BlockError MX25L12845EMinimal::_program(uint32_t address, const uint8_t* data, uint32_t len) {
    BlockError e = _wren();
    if (e != BlockError::Ok) return e;
    _frame[0] = CMD_PP;
    put_address(_frame + 1, address);
    memcpy(_frame + 4, data, len);
    _c.write(_frame, 4 + len);
    if (!_c.ok()) return BlockError::Io;
    e = _waitReady(T_PP_MS);
    if (e != BlockError::Ok) return e;
    return _checkFail();
}

BlockError MX25L12845EMinimal::_readRaw(uint8_t cmd, uint32_t address, uint8_t dummy, uint8_t* buf, uint32_t len) {
    uint8_t frame[5] = {cmd};
    put_address(frame + 1, address);
    _c.write_read(frame, dummy ? 5 : 4, buf, len);
    return _c.ok() ? BlockError::Ok : BlockError::Io;
}

BlockError MX25L12845EMinimal::_doRead(uint32_t address, uint8_t* buf, uint32_t len) {
    while (len) {
        uint32_t n = len > READ_CHUNK ? READ_CHUNK : len;
        BlockError e = _readRaw(CMD_READ, address, 0, buf, n);
        if (e != BlockError::Ok) return e;
        address += n; buf += n; len -= n;
    }
    return BlockError::Ok;
}

// ---- Minimal -----------------------------------------------------------------

MX25L12845EMinimal::MX25L12845EMinimal(Connection& connection, const MX25L12845EOptions& opt)
    : _c(connection), _opt(opt) {
    _command(CMD_RDP);   // leave deep power-down if a previous session left it there (harmless otherwise)
    _sleep(1);           // tRES2 = 100 us
    uint8_t id[3] = {0, 0, 0};
    if (jedec_id(id) != BlockError::Ok || id[0] != 0xC2 || id[1] != 0x20 || id[2] != 0x18) {
        _status = BlockError::NotReady;
        return;
    }
    _status = _waitReady(T_CE_MS);   // a reset during an erase can leave the chip busy
}

BlockError MX25L12845EMinimal::jedec_id(uint8_t id[3]) {
    uint8_t cmd = CMD_RDID;
    _c.write_read(&cmd, 1, id, 3);
    return _c.ok() ? BlockError::Ok : BlockError::Io;
}

BlockError MX25L12845EMinimal::read_bytes(uint32_t address, uint8_t* buf, uint32_t len) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    if (static_cast<uint64_t>(address) + len > SIZE) return BlockError::OutOfRange;
    return _doRead(address, buf, len);
}

BlockError MX25L12845EMinimal::erase_sector(uint32_t address) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    if (address >= SIZE) return BlockError::OutOfRange;
    return _eraseCmd(CMD_SE, address, T_SE_MS);
}

BlockError MX25L12845EMinimal::write_bytes(uint32_t address, const uint8_t* data, uint32_t len) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    if (static_cast<uint64_t>(address) + len > SIZE) return BlockError::OutOfRange;
    while (len) {
        uint32_t sectorBase = address & ~(SECTOR_SIZE - 1);
        uint32_t off = address - sectorBase;
        uint32_t n = SECTOR_SIZE - off;
        if (n > len) n = len;

        BlockError e = _doRead(sectorBase, _sector, SECTOR_SIZE);
        if (e != BlockError::Ok) return e;

        bool needsErase = false;
        for (uint32_t i = 0; i < n; i++) {
            if ((_sector[off + i] & data[i]) != data[i]) { needsErase = true; break; }   // would need a 0 -> 1 transition
        }

        if (!needsErase) {
            // Program only the pages whose contents change; AND-ing new data onto old gives exactly the new data.
            uint32_t pos = 0;
            while (pos < n) {
                uint32_t a = address + pos;
                uint32_t chunk = PAGE_SIZE - (a & (PAGE_SIZE - 1));
                if (chunk > n - pos) chunk = n - pos;
                if (memcmp(&_sector[off + pos], data + pos, chunk) != 0) {
                    e = _program(a, data + pos, chunk);
                    if (e != BlockError::Ok) return e;
                }
                pos += chunk;
            }
        } else {
            memcpy(&_sector[off], data, n);
            e = _eraseCmd(CMD_SE, sectorBase, T_SE_MS);
            if (e != BlockError::Ok) return e;
            for (uint32_t page = 0; page < SECTOR_SIZE; page += PAGE_SIZE) {   // blank pages need no program
                bool blank = true;
                for (uint32_t i = 0; i < PAGE_SIZE; i++) if (_sector[page + i] != 0xFF) { blank = false; break; }
                if (blank) continue;
                e = _program(sectorBase + page, &_sector[page], PAGE_SIZE);
                if (e != BlockError::Ok) return e;
            }
        }
        address += n; data += n; len -= n;
    }
    return BlockError::Ok;
}

// ---- Full: raw operations -----------------------------------------------------------

BlockError MX25L12845EFull::fast_read(uint32_t address, uint8_t* buf, uint32_t len) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    if (static_cast<uint64_t>(address) + len > SIZE) return BlockError::OutOfRange;
    while (len) {
        uint32_t n = len > READ_CHUNK ? READ_CHUNK : len;
        BlockError e = _readRaw(CMD_FAST_READ, address, 1, buf, n);
        if (e != BlockError::Ok) return e;
        address += n; buf += n; len -= n;
    }
    return BlockError::Ok;
}

BlockError MX25L12845EFull::page_program(uint32_t address, const uint8_t* data, uint32_t len) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    if (len == 0 || len > PAGE_SIZE || static_cast<uint64_t>(address) + len > SIZE) return BlockError::OutOfRange;
    if ((address & (PAGE_SIZE - 1)) + len > PAGE_SIZE) return BlockError::OutOfRange;   // would wrap inside the page
    return _program(address, data, len);
}

BlockError MX25L12845EFull::erase_block_32k(uint32_t address) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    if (address >= SIZE) return BlockError::OutOfRange;
    return _eraseCmd(CMD_BE32K, address, T_BE_MS);
}

BlockError MX25L12845EFull::erase_block_64k(uint32_t address) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    if (address >= SIZE) return BlockError::OutOfRange;
    return _eraseCmd(CMD_BE64K, address, T_BE_MS);
}

BlockError MX25L12845EFull::chip_erase() {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    BlockError e = _wren();
    if (e != BlockError::Ok) return e;
    e = _command(CMD_CE);
    if (e != BlockError::Ok) return e;
    e = _waitReady(T_CE_MS);
    if (e != BlockError::Ok) return e;
    return _checkFail();
}

// ---- Full: status / protection ----------------------------------------------------------

BlockError MX25L12845EFull::read_status(uint8_t* sr) { return _readStatus(sr); }

BlockError MX25L12845EFull::write_status(uint8_t value) {
    BlockError e = _wren();
    if (e != BlockError::Ok) return e;
    uint8_t frame[2] = {CMD_WRSR, value};
    _c.write(frame, 2);
    if (!_c.ok()) return BlockError::Io;
    return _waitReady(T_WRSR_MS);
}

BlockError MX25L12845EFull::protection(uint8_t* bp) {
    uint8_t sr = 0;
    BlockError e = _readStatus(&sr);
    if (e == BlockError::Ok) *bp = static_cast<uint8_t>((sr & SR_BP_MASK) >> SR_BP_SHIFT);
    return e;
}

BlockError MX25L12845EFull::set_protection(uint8_t bp) {
    if (bp > 15) return BlockError::OutOfRange;
    uint8_t sr = 0;
    BlockError e = _readStatus(&sr);
    if (e != BlockError::Ok) return e;
    return write_status(static_cast<uint8_t>((sr & ~SR_BP_MASK) | (bp << SR_BP_SHIFT)));
}

bool MX25L12845EFull::is_busy() {
    uint8_t sr = 0;
    return _readStatus(&sr) == BlockError::Ok && (sr & SR_WIP);
}

BlockError MX25L12845EFull::read_security(uint8_t* scur) {
    uint8_t cmd = CMD_RDSCUR;
    _c.write_read(&cmd, 1, scur, 1);
    return _c.ok() ? BlockError::Ok : BlockError::Io;
}

BlockError MX25L12845EFull::clear_fail_flags() { return _command(CMD_CLSR); }

// ---- Full: power / identification ----------------------------------------------------------

BlockError MX25L12845EFull::deep_power_down() { return _command(CMD_DP); }

BlockError MX25L12845EFull::release_deep_power_down() {
    BlockError e = _command(CMD_RDP);
    _sleep(1);   // tRES2 = 100 us
    return e;
}

BlockError MX25L12845EFull::electronic_id(uint8_t* id) {
    uint8_t frame[4] = {CMD_RES, 0, 0, 0};
    _c.write_read(frame, 4, id, 1);
    return _c.ok() ? BlockError::Ok : BlockError::Io;
}

BlockError MX25L12845EFull::device_ids(uint8_t* manufacturer, uint8_t* device) {
    uint8_t frame[4] = {CMD_REMS, 0, 0, 0};
    uint8_t out[2] = {0, 0};
    _c.write_read(frame, 4, out, 2);
    if (!_c.ok()) return BlockError::Io;
    *manufacturer = out[0];
    *device = out[1];
    return BlockError::Ok;
}

BlockError MX25L12845EFull::read_sfdp(uint32_t address, uint8_t* buf, uint32_t len) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    while (len) {
        uint32_t n = len > READ_CHUNK ? READ_CHUNK : len;
        BlockError e = _readRaw(CMD_RDSFDP, address, 1, buf, n);
        if (e != BlockError::Ok) return e;
        address += n; buf += n; len -= n;
    }
    return BlockError::Ok;
}

// ---- Full: secured OTP -----------------------------------------------------------------

BlockError MX25L12845EFull::_otpEnter() { return _command(CMD_ENSO); }
BlockError MX25L12845EFull::_otpExit()  { return _command(CMD_EXSO); }

BlockError MX25L12845EFull::read_otp(uint32_t offset, uint8_t* buf, uint32_t len) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    if (static_cast<uint64_t>(offset) + len > 512) return BlockError::OutOfRange;
    BlockError e = _otpEnter();
    if (e != BlockError::Ok) return e;
    e = _doRead(offset, buf, len);
    BlockError x = _otpExit();
    return e != BlockError::Ok ? e : x;
}

BlockError MX25L12845EFull::program_otp(uint32_t offset, const uint8_t* data, uint32_t len) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    if (offset < 16 || len == 0 || static_cast<uint64_t>(offset) + len > 512) return BlockError::OutOfRange;   // 0..15 = factory ESN
    BlockError e = _otpEnter();
    if (e != BlockError::Ok) return e;
    while (len && e == BlockError::Ok) {
        uint32_t chunk = PAGE_SIZE - (offset & (PAGE_SIZE - 1));
        if (chunk > len) chunk = len;
        e = _program(offset, data, chunk);
        offset += chunk; data += chunk; len -= chunk;
    }
    BlockError x = _otpExit();
    return e != BlockError::Ok ? e : x;
}

BlockError MX25L12845EFull::lock_otp() {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    BlockError e = _wren();
    if (e != BlockError::Ok) return e;
    e = _command(CMD_WRSCUR);
    if (e != BlockError::Ok) return e;
    return _waitReady(T_WRSR_MS);
}

BlockError MX25L12845EFull::serial_number(uint8_t out[16]) { return read_otp(0, out, 16); }

// ---- Full: BlockDevice -----------------------------------------------------------------------

BlockError MX25L12845EFull::read(uint32_t block, uint8_t* buf, uint32_t count) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    if (static_cast<uint64_t>(block) + count > block_count()) return BlockError::OutOfRange;
    return _doRead(block * 512u, buf, count * 512u);
}

BlockError MX25L12845EFull::write(uint32_t block, const uint8_t* data, uint32_t count) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    if (static_cast<uint64_t>(block) + count > block_count()) return BlockError::OutOfRange;
    uint32_t address = block * 512u;
    uint32_t len = count * 512u;
    if (_opt.auto_erase) return write_bytes(address, data, len);

    // No auto-erase: program page by page, but only where no 0 -> 1 transition is needed.
    uint8_t old[PAGE_SIZE];
    while (len) {
        uint32_t chunk = PAGE_SIZE - (address & (PAGE_SIZE - 1));
        if (chunk > len) chunk = len;
        BlockError e = _doRead(address, old, chunk);
        if (e != BlockError::Ok) return e;
        bool changes = false;
        for (uint32_t i = 0; i < chunk; i++) {
            if ((old[i] & data[i]) != data[i]) return BlockError::NotErased;
            if (old[i] != data[i]) changes = true;
        }
        if (changes) {
            e = _program(address, data, chunk);
            if (e != BlockError::Ok) return e;
        }
        address += chunk; data += chunk; len -= chunk;
    }
    return BlockError::Ok;
}

BlockError MX25L12845EFull::erase(uint32_t block, uint32_t count) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    if ((block % 8) != 0 || (count % 8) != 0 || static_cast<uint64_t>(block) + count > block_count())
        return BlockError::OutOfRange;
    uint32_t address = block * 512u;
    uint32_t left = count * 512u;
    while (left) {
        BlockError e;
        if ((address & 0xFFFF) == 0 && left >= 65536) { e = erase_block_64k(address); address += 65536; left -= 65536; }
        else if ((address & 0x7FFF) == 0 && left >= 32768) { e = erase_block_32k(address); address += 32768; left -= 32768; }
        else { e = erase_sector(address); address += SECTOR_SIZE; left -= SECTOR_SIZE; }
        if (e != BlockError::Ok) return e;
    }
    return BlockError::Ok;
}

BlockInfo MX25L12845EFull::info() {
    BlockInfo i;
    i.block_size = 512;
    i.block_count = block_count();
    i.erase_size = SECTOR_SIZE;
    i.needs_erase = true;
    uint8_t bp = 0;
    protection(&bp);
    i.read_only = bp >= 8;
    i.bad_blocks = false;
    strncpy(i.name, "MX25L12845E", sizeof(i.name) - 1);
    return i;
}
