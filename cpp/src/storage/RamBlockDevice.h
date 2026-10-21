#pragma once
#include <vector>
#include <string.h>
#include "BlockDevice.h"

/** @brief RAM-backed BlockDevice mock for tests (host builds only).
 *
 * Models NOR-like semantics when constructed with @p eraseBlocks > 1:
 * erased blocks read 0xFF and programming an un-erased unit while
 * `autoErase` is false returns `BlockError::NotErased`.
 */
class RamBlockDevice : public BlockDevice {
public:
    RamBlockDevice(uint32_t blockSize, uint32_t blockCount, uint32_t eraseBlocks = 1, bool autoErase = false)
        : _bs(blockSize), _bc(blockCount), _eb(eraseBlocks ? eraseBlocks : 1), _auto(autoErase),
          _mem(static_cast<size_t>(blockSize) * blockCount, _eb > 1 ? 0xFF : 0x00),
          _erased(blockCount, true) {}

    uint32_t block_size() const override { return _bs; }
    uint32_t block_count() const override { return _bc; }
    uint32_t erase_size() const override { return _bs * _eb; }
    bool needs_erase() const override { return _eb > 1; }

    BlockError read(uint32_t block, uint8_t* buf, uint32_t count) override {
        if (static_cast<uint64_t>(block) + count > _bc) return BlockError::OutOfRange;
        memcpy(buf, &_mem[static_cast<size_t>(block) * _bs], static_cast<size_t>(count) * _bs);
        return BlockError::Ok;
    }

    BlockError write(uint32_t block, const uint8_t* data, uint32_t count) override {
        if (static_cast<uint64_t>(block) + count > _bc) return BlockError::OutOfRange;
        if (needs_erase()) {
            for (uint32_t i = 0; i < count; i++) {
                if (!_erased[block + i]) {
                    if (!_auto) return BlockError::NotErased;
                    erase(block + i - ((block + i) % _eb), _eb);
                }
            }
        }
        memcpy(&_mem[static_cast<size_t>(block) * _bs], data, static_cast<size_t>(count) * _bs);
        for (uint32_t i = 0; i < count; i++) _erased[block + i] = false;
        return BlockError::Ok;
    }

    BlockError erase(uint32_t block, uint32_t count) override {
        if (!needs_erase()) return BlockError::Ok;
        if (block % _eb || count % _eb || static_cast<uint64_t>(block) + count > _bc) return BlockError::OutOfRange;
        memset(&_mem[static_cast<size_t>(block) * _bs], 0xFF, static_cast<size_t>(count) * _bs);
        for (uint32_t i = 0; i < count; i++) _erased[block + i] = true;
        return BlockError::Ok;
    }

    BlockError sync() override { return BlockError::Ok; }

    BlockInfo info() override {
        BlockInfo i;
        i.block_size = _bs; i.block_count = _bc; i.erase_size = erase_size(); i.needs_erase = needs_erase();
        strncpy(i.name, "RAM", sizeof(i.name) - 1);
        return i;
    }

private:
    uint32_t _bs, _bc, _eb;
    bool _auto;
    std::vector<uint8_t> _mem;
    std::vector<bool> _erased;
};
