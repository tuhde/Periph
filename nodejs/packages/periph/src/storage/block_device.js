'use strict';

/**
 * Common block-device contract for storage chips (SD, SD NAND, SPI NOR, SPI NAND).
 *
 * See specs/feature_block_device.md. Only the Full chip classes implement
 * `BlockDevice`. Units are bytes and block indices; a block is the
 * read/program unit exposed to a filesystem. Periph ships no filesystem.
 */

const BLOCK_DEVICE = Symbol.for('periph.BlockDevice');

/** Base class of all block-device errors. */
class BlockError extends Error {}
/** Device absent or initialisation failed. */
class NotReady extends BlockError {}
/** Block index/count beyond blockCount(), or unaligned erase(). */
class OutOfRange extends BlockError {}
/** Program to an un-erased block while auto-erase is off. */
class NotErased extends BlockError {}
/** Card WP / status-register block protection. */
class WriteProtected extends BlockError {}
/** Target erase unit is marked bad (NAND). */
class BadBlock extends BlockError {}
/** Uncorrectable ECC error on read (NAND). */
class Ecc extends BlockError {}
/** SD command/data CRC mismatch. */
class Crc extends BlockError {}
/** Busy never cleared. */
class Timeout extends BlockError {}
/** Transport failure. */
class Io extends BlockError {}

/**
 * Contract implemented by the Full class of every storage chip. Methods that
 * touch the device are async.
 * @abstract
 */
class BlockDevice {
    /**
     * Mark a class that conforms to the contract without extending it (single inheritance),
     * so that `instance instanceof BlockDevice` is true.
     * @param {Function} cls
     */
    static mark(cls) { cls.prototype[BLOCK_DEVICE] = true; }

    static [Symbol.hasInstance](obj) { return !!obj && obj[BLOCK_DEVICE] === true; }

    /** @returns {number} Logical block size in bytes (read/program unit). */
    blockSize() { throw new Error('abstract'); }
    /** @returns {number} Number of addressable logical blocks. */
    blockCount() { throw new Error('abstract'); }
    /** @returns {number} Erase unit in bytes; equals blockSize() when needsErase() is false. */
    eraseSize() { throw new Error('abstract'); }
    /** @returns {boolean} True if blocks must be erased before they are programmed. */
    needsErase() { throw new Error('abstract'); }

    /**
     * Read `count` consecutive blocks.
     * @param {number} block - First block index.
     * @param {number} [count=1] - Number of blocks.
     * @returns {Promise<Buffer>} `count * blockSize()` bytes.
     */
    async read(_block, _count = 1) { throw new Error('abstract'); }
    /**
     * Program `data.length / blockSize()` consecutive blocks starting at `block`.
     * @param {number} block
     * @param {Buffer} data
     * @returns {Promise<void>}
     */
    async write(_block, _data) { throw new Error('abstract'); }
    /**
     * Erase the erase units covering `[block, block + count)`. No-op on SD.
     * @param {number} block
     * @param {number} [count=1]
     * @returns {Promise<void>}
     */
    async erase(_block, _count = 1) { throw new Error('abstract'); }
    /** @returns {Promise<void>} Resolves when all pending program/erase work has finished. */
    async sync() { throw new Error('abstract'); }
    /**
     * @returns {{blockSize:number, blockCount:number, eraseSize:number, needsErase:boolean, readOnly:boolean, badBlocks:boolean, name:string}}
     */
    info() { throw new Error('abstract'); }
    /** Optional (`info().badBlocks`): whether erase unit `unit` is bad. */
    async isBad(_unit) { return false; }
    /** Optional: persist a bad-block mark for erase unit `unit`. */
    async markBad(_unit) {}
}

BlockDevice.mark(BlockDevice);

module.exports = {
    BlockDevice, BlockError, NotReady, OutOfRange, NotErased, WriteProtected, BadBlock, Ecc, Crc, Timeout, Io,
};
