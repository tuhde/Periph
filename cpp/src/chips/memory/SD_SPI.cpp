#include "SD_SPI.h"
#include <string.h>

// ---- CRC helpers ---------------------------------------------------------

uint8_t SDSPIMinimal::crc7(const uint8_t* data, size_t len) {
    uint8_t crc = 0;
    for (size_t i = 0; i < len; i++) {
        uint8_t b = data[i];
        for (int bit = 0; bit < 8; bit++) {
            crc <<= 1;
            if ((b ^ crc) & 0x80) crc ^= 0x09;
            b <<= 1;
        }
    }
    return crc & 0x7F;
}

uint16_t SDSPIMinimal::crc16(const uint8_t* data, size_t len) {
    uint16_t crc = 0;
    for (size_t i = 0; i < len; i++) {
        crc ^= static_cast<uint16_t>(data[i]) << 8;
        for (int bit = 0; bit < 8; bit++)
            crc = (crc & 0x8000) ? static_cast<uint16_t>((crc << 1) ^ 0x1021) : static_cast<uint16_t>(crc << 1);
    }
    return crc;
}

// ---- timing / bus primitives ----------------------------------------------

SDSPIMinimal::Deadline SDSPIMinimal::_deadline(uint32_t ms) {
    return Deadline{_opt.now_ms ? _opt.now_ms() : 0, ms, 0};
}

bool SDSPIMinimal::_expired(Deadline& d) {
    if (_opt.now_ms) return static_cast<uint32_t>(_opt.now_ms() - d.start) >= d.ms;
    // No clock: budget of ~8 polls per millisecond.
    return ++d.polls > d.ms * 8;
}

uint8_t SDSPIMinimal::_rx() {
    uint8_t b = 0xFF;
    _spi.connection().read(&b, 1);
    return b;
}

void SDSPIMinimal::_tx(const uint8_t* data, size_t len) {
    _spi.connection().write(data, len);
}

void SDSPIMinimal::_release() {
    _spi.deselect();
    _spi.idle_clocks(8);
}

BlockError SDSPIMinimal::_waitReady(uint32_t ms) {
    Deadline d = _deadline(ms);
    do {
        if (_rx() == 0xFF) return BlockError::Ok;
    } while (!_expired(d));
    return BlockError::Timeout;
}

uint8_t SDSPIMinimal::_command(uint8_t cmd, uint32_t arg) {
    if (cmd != 12 && cmd != 0 && _waitReady(BUSY_TIMEOUT_MS) != BlockError::Ok) return 0xFF;
    uint8_t frame[6];
    frame[0] = static_cast<uint8_t>(0x40 | cmd);
    frame[1] = static_cast<uint8_t>(arg >> 24);
    frame[2] = static_cast<uint8_t>(arg >> 16);
    frame[3] = static_cast<uint8_t>(arg >> 8);
    frame[4] = static_cast<uint8_t>(arg);
    frame[5] = static_cast<uint8_t>((crc7(frame, 5) << 1) | 1);
    _tx(frame, 6);
    if (cmd == 12) _rx();  // CMD12: discard the stuff byte that follows the command
    for (int i = 0; i < 10; i++) {  // Ncr <= 8 bytes
        uint8_t r = _rx();
        if (!(r & 0x80)) return r;
    }
    return 0xFF;
}

uint8_t SDSPIMinimal::_appCommand(uint8_t cmd, uint32_t arg) {
    uint8_t r = _command(55, 0);
    if (r > 1) return r;
    return _command(cmd, arg);
}

BlockError SDSPIMinimal::_readData(uint8_t* buf, size_t len) {
    Deadline d = _deadline(TOKEN_TIMEOUT_MS);
    uint8_t token;
    do {
        token = _rx();
    } while (token == 0xFF && !_expired(d));
    if (token == 0xFF) return BlockError::Timeout;
    if (token != 0xFE) return BlockError::Io;
    _spi.connection().read(buf, len);
    uint8_t crc[2];
    _spi.connection().read(crc, 2);
    if (_crcOn) {
        uint16_t got = static_cast<uint16_t>((crc[0] << 8) | crc[1]);
        if (got != crc16(buf, len)) return BlockError::Crc;
    }
    return BlockError::Ok;
}

BlockError SDSPIMinimal::_writeData(uint8_t token, const uint8_t* data) {
    if (_waitReady(BUSY_TIMEOUT_MS) != BlockError::Ok) return BlockError::Timeout;
    _tx(&token, 1);
    if (token == 0xFD) return BlockError::Ok;  // stop-tran token has no payload
    _tx(data, BLOCK_SIZE);
    uint8_t crc[2] = {0xFF, 0xFF};
    if (_crcOn) {
        uint16_t c = crc16(data, BLOCK_SIZE);
        crc[0] = static_cast<uint8_t>(c >> 8);
        crc[1] = static_cast<uint8_t>(c);
    }
    _tx(crc, 2);
    uint8_t resp = _rx();
    switch (resp & 0x1F) {
        case 0x05: return BlockError::Ok;
        case 0x0B: return BlockError::Crc;
        default:   return BlockError::Io;
    }
}

BlockError SDSPIMinimal::_readRegister(uint8_t cmd, uint8_t out[16]) {
    _spi.select();
    uint8_t r = _command(cmd, 0);
    BlockError e = (r == 0) ? _readData(out, 16) : BlockError::Io;
    _release();
    return e;
}

BlockError SDSPIMinimal::_readCsd(uint8_t csd[16]) {
    BlockError e = _readRegister(9, csd);
    if (e != BlockError::Ok) return e;
    if ((csd[0] >> 6) == 1) {  // CSD v2: C_SIZE = CSD[69:48]
        uint32_t csize = (static_cast<uint32_t>(csd[7] & 0x3F) << 16) | (static_cast<uint32_t>(csd[8]) << 8) | csd[9];
        _blockCount = (csize + 1) * 1024;
    } else {                    // CSD v1
        uint32_t readBlLen = csd[5] & 0x0F;
        uint32_t csize = (static_cast<uint32_t>(csd[6] & 0x03) << 10) | (static_cast<uint32_t>(csd[7]) << 2) | (csd[8] >> 6);
        uint32_t mult = ((csd[9] & 0x03) << 1) | (csd[10] >> 7);
        uint64_t bytes = static_cast<uint64_t>(csize + 1) << (mult + 2) << readBlLen;
        _blockCount = static_cast<uint32_t>(bytes / BLOCK_SIZE);
    }
    return BlockError::Ok;
}

// ---- Minimal ----------------------------------------------------------------

SDSPIMinimal::SDSPIMinimal(SPIExtensions& spi, const Options& opt) : _spi(spi), _opt(opt) {
    begin();
}

BlockError SDSPIMinimal::begin() {
    _type = CardType::None;
    _blockCount = 0;
    _crcOn = false;
    _status = BlockError::NotReady;

    _spi.set_speed(400000);
    _spi.deselect();
    _spi.idle_clocks(80);

    // CMD0 with CS low enters SPI mode; retry, a fresh card needs a few tries.
    bool idle = false;
    for (int i = 0; i < 10 && !idle; i++) {
        _spi.select();
        idle = (_command(0, 0) == 0x01);
        _release();
    }
    if (!idle) return _status;

    _spi.select();
    bool v2 = false;
    uint8_t r = _command(8, 0x1AA);
    if (r == 0x01) {
        uint8_t r7[4];
        _spi.connection().read(r7, 4);
        if ((r7[2] & 0x0F) != 0x01 || r7[3] != 0xAA) { _release(); return _status; }
        v2 = true;
    } else if (!(r & 0x04)) {  // anything but "illegal command" (v1 card) is a failure
        _release();
        return _status;
    }

    Deadline d = _deadline(INIT_TIMEOUT_MS);
    do {
        r = _appCommand(41, v2 ? 0x40000000u : 0u);
    } while (r != 0x00 && !_expired(d));
    if (r != 0x00) { _release(); _status = BlockError::Timeout; return _status; }

    _type = v2 ? CardType::SDv2 : CardType::SDv1;
    _blockAddressing = false;
    if (v2) {
        if (_command(58, 0) == 0x00) {
            uint8_t ocr[4];
            _spi.connection().read(ocr, 4);
            if (ocr[0] & 0x40) { _blockAddressing = true; _type = CardType::SDHC; }
        }
    }
    if (!_blockAddressing) _command(16, BLOCK_SIZE);
    if (_opt.crc && _command(59, 1) == 0x00) _crcOn = true;
    _release();

    _spi.set_speed(_opt.speed_hz);
    _status = _readCsd(_csd);
    if (_status != BlockError::Ok) _type = CardType::None;
    return _status;
}

BlockError SDSPIMinimal::read_block(uint32_t block, uint8_t* buf) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    if (block >= _blockCount) return BlockError::OutOfRange;
    _spi.select();
    BlockError e = BlockError::Io;
    if (_command(17, _address(block)) == 0x00) e = _readData(buf, BLOCK_SIZE);
    _release();
    return e;
}

BlockError SDSPIMinimal::write_block(uint32_t block, const uint8_t* data) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    if (block >= _blockCount) return BlockError::OutOfRange;
    _spi.select();
    BlockError e = BlockError::Io;
    if (_command(24, _address(block)) == 0x00) {
        e = _writeData(0xFE, data);
        if (e == BlockError::Ok) e = _waitReady(BUSY_TIMEOUT_MS);
    }
    _release();
    return e;
}

// ---- Full -------------------------------------------------------------------

SDSPIFull::SDSPIFull(SPIExtensions& spi, const Options& opt) : SDSPIMinimal(spi, opt) {
    if (_status == BlockError::Ok) {
        uint8_t c[16];
        if (_readRegister(10, c) == BlockError::Ok) memcpy(_name, c + 3, 5);  // PNM = CID[103:64]
    }
}

BlockError SDSPIFull::read(uint32_t block, uint8_t* buf, uint32_t count) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    if (count == 0) return BlockError::Ok;
    if (static_cast<uint64_t>(block) + count > _blockCount) return BlockError::OutOfRange;
    if (count == 1) return read_block(block, buf);

    _spi.select();
    BlockError e = BlockError::Io;
    if (_command(18, _address(block)) == 0x00) {
        e = BlockError::Ok;
        for (uint32_t i = 0; i < count && e == BlockError::Ok; i++)
            e = _readData(buf + static_cast<size_t>(i) * BLOCK_SIZE, BLOCK_SIZE);
        _command(12, 0);       // stop transmission (R1b), also after an error
        _waitReady(BUSY_TIMEOUT_MS);
    }
    _release();
    return e;
}

BlockError SDSPIFull::write(uint32_t block, const uint8_t* data, uint32_t count) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    if (count == 0) return BlockError::Ok;
    if (static_cast<uint64_t>(block) + count > _blockCount) return BlockError::OutOfRange;
    if (count == 1) return write_block(block, data);

    _spi.select();
    BlockError e = BlockError::Io;
    _appCommand(23, count);  // pre-erase hint; cards may ignore it
    if (_command(25, _address(block)) == 0x00) {
        e = BlockError::Ok;
        for (uint32_t i = 0; i < count && e == BlockError::Ok; i++)
            e = _writeData(0xFC, data + static_cast<size_t>(i) * BLOCK_SIZE);
        uint8_t stop = 0xFD;
        _waitReady(BUSY_TIMEOUT_MS);
        _tx(&stop, 1);
        _rx();  // stuff byte after the stop token
        BlockError b = _waitReady(BUSY_TIMEOUT_MS);
        if (e == BlockError::Ok) e = b;
    }
    _release();
    return e;
}

BlockError SDSPIFull::erase(uint32_t block, uint32_t count) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    if (count == 0) return BlockError::Ok;
    if (static_cast<uint64_t>(block) + count > _blockCount) return BlockError::OutOfRange;
    _spi.select();
    BlockError e = BlockError::Io;
    if (_command(32, _address(block)) == 0x00 && _command(33, _address(block + count - 1)) == 0x00 &&
        _command(38, 0) == 0x00)
        e = _waitReady(ERASE_TIMEOUT_MS);
    _release();
    return e;
}

BlockError SDSPIFull::sync() {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    _spi.select();
    BlockError e = _waitReady(BUSY_TIMEOUT_MS);
    _release();
    return e;
}

BlockInfo SDSPIFull::info() {
    BlockInfo i;
    i.block_size = BLOCK_SIZE;
    i.block_count = _blockCount;
    i.erase_size = BLOCK_SIZE;
    i.needs_erase = false;
    i.read_only = false;
    i.bad_blocks = false;
    memcpy(i.name, _name, sizeof(_name));
    return i;
}

BlockError SDSPIFull::cid(uint8_t out[16]) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    return _readRegister(10, out);
}

BlockError SDSPIFull::ocr(uint32_t* out) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    _spi.select();
    BlockError e = BlockError::Io;
    if (_command(58, 0) == 0x00) {
        uint8_t o[4];
        _spi.connection().read(o, 4);
        *out = (static_cast<uint32_t>(o[0]) << 24) | (static_cast<uint32_t>(o[1]) << 16) |
               (static_cast<uint32_t>(o[2]) << 8) | o[3];
        e = BlockError::Ok;
    }
    _release();
    return e;
}

BlockError SDSPIFull::set_crc(bool enabled) {
    if (_status != BlockError::Ok) return BlockError::NotReady;
    _spi.select();
    BlockError e = (_command(59, enabled ? 1 : 0) == 0x00) ? BlockError::Ok : BlockError::Io;
    _release();
    if (e == BlockError::Ok) _crcOn = enabled;
    return e;
}

bool SDSPIFull::is_present() {
    _spi.select();
    uint8_t r = _command(13, 0);
    if (r != 0xFF) _rx();  // R2 second byte
    _release();
    return r != 0xFF;
}
