#pragma once
#include "BlockDevice.h"

/** @brief BlockDevice decorator that counts (and optionally reports) every operation.
 *
 * Wraps any BlockDevice and forwards each call unchanged. Useful to see what a
 * filesystem actually asks of a storage chip: number of calls, blocks moved,
 * failures. The optional callback fires after every read/write/erase/sync.
 */
class TracingBlockDevice : public BlockDevice {
public:
    /** @brief Per-operation callback: @p op is 'R', 'W', 'E' or 'S'; @p block/@p count are 0 for 'S'. */
    typedef void (*TraceFn)(char op, uint32_t block, uint32_t count, BlockError result);

    /** @brief Cumulative traffic since construction or the last reset(). */
    struct Stats {
        uint32_t readCalls = 0, readBlocks = 0, writeCalls = 0, writeBlocks = 0;
        uint32_t eraseCalls = 0, syncCalls = 0, errors = 0;
    };

    /** @param inner Device to wrap (must outlive this object). */
    explicit TracingBlockDevice(BlockDevice& inner, TraceFn trace = nullptr) : _d(inner), _trace(trace) {}

    /** @brief Install or remove the per-operation callback. */
    void setTrace(TraceFn trace) { _trace = trace; }
    /** @brief Current counters. */
    const Stats& stats() const { return _s; }
    /** @brief Zero the counters. */
    void reset() { _s = Stats(); }

    uint32_t block_size() const override { return _d.block_size(); }
    uint32_t block_count() const override { return _d.block_count(); }
    uint32_t erase_size() const override { return _d.erase_size(); }
    bool needs_erase() const override { return _d.needs_erase(); }

    BlockError read(uint32_t block, uint8_t* buf, uint32_t count) override {
        _s.readCalls++; _s.readBlocks += count;
        return _done('R', block, count, _d.read(block, buf, count));
    }
    BlockError write(uint32_t block, const uint8_t* data, uint32_t count) override {
        _s.writeCalls++; _s.writeBlocks += count;
        return _done('W', block, count, _d.write(block, data, count));
    }
    BlockError erase(uint32_t block, uint32_t count) override {
        _s.eraseCalls++;
        return _done('E', block, count, _d.erase(block, count));
    }
    BlockError sync() override {
        _s.syncCalls++;
        return _done('S', 0, 0, _d.sync());
    }
    BlockInfo info() override { return _d.info(); }
    bool is_bad(uint32_t unit) override { return _d.is_bad(unit); }
    BlockError mark_bad(uint32_t unit) override { return _d.mark_bad(unit); }

private:
    BlockError _done(char op, uint32_t block, uint32_t count, BlockError e) {
        if (e != BlockError::Ok) _s.errors++;
        if (_trace) _trace(op, block, count, e);
        return e;
    }

    BlockDevice& _d;
    TraceFn _trace;
    Stats _s;
};
