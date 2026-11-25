'use strict';

const { BlockDevice, OutOfRange, NotErased } = require('./block_device');

/**
 * RAM-backed BlockDevice mock for tests.
 *
 * With `eraseBlocks > 1` it models NOR-like semantics: erased blocks read
 * 0xFF and programming an un-erased block while `autoErase` is false rejects
 * with NotErased.
 */
class RamBlockDevice extends BlockDevice {
    /**
     * @param {number} blockSize - Bytes per block.
     * @param {number} blockCount - Number of blocks.
     * @param {number} [eraseBlocks=1] - Blocks per erase unit (1 = no erase needed).
     * @param {boolean} [autoErase=false] - Erase automatically before programming.
     */
    constructor(blockSize, blockCount, eraseBlocks = 1, autoErase = false) {
        super();
        this._bs = blockSize;
        this._bc = blockCount;
        this._eb = eraseBlocks || 1;
        this._auto = autoErase;
        this._mem = Buffer.alloc(blockSize * blockCount, this._eb > 1 ? 0xFF : 0x00);
        this._erased = new Array(blockCount).fill(true);
    }

    blockSize() { return this._bs; }
    blockCount() { return this._bc; }
    eraseSize() { return this._bs * this._eb; }
    needsErase() { return this._eb > 1; }

    async read(block, count = 1) {
        if (block < 0 || block + count > this._bc) throw new OutOfRange('read beyond end');
        return Buffer.from(this._mem.subarray(block * this._bs, (block + count) * this._bs));
    }

    async write(block, data) {
        const count = Math.floor(data.length / this._bs);
        if (block < 0 || block + count > this._bc) throw new OutOfRange('write beyond end');
        if (this.needsErase()) {
            for (let i = 0; i < count; i++) {
                const b = block + i;
                if (!this._erased[b]) {
                    if (!this._auto) throw new NotErased(`block ${b} not erased`);
                    await this.erase(b - (b % this._eb), this._eb);
                }
            }
        }
        Buffer.from(data).copy(this._mem, block * this._bs);
        for (let i = 0; i < count; i++) this._erased[block + i] = false;
    }

    async erase(block, count = 1) {
        if (!this.needsErase()) return;
        if (block % this._eb || count % this._eb || block + count > this._bc) throw new OutOfRange('unaligned erase');
        this._mem.fill(0xFF, block * this._bs, (block + count) * this._bs);
        for (let i = 0; i < count; i++) this._erased[block + i] = true;
    }

    async sync() {}

    info() {
        return {
            blockSize: this._bs, blockCount: this._bc, eraseSize: this.eraseSize(), needsErase: this.needsErase(),
            readOnly: false, badBlocks: false, name: 'RAM',
        };
    }
}

module.exports = { RamBlockDevice };
