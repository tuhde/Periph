package it.uhde.periph.storage;

import java.io.IOException;

/**
 * Common block-device contract for storage chips (SD, SD NAND, SPI NOR, SPI NAND) —
 * see {@code specs/feature_block_device.md}.
 *
 * <p>Only the Full chip classes implement it. Units are bytes and block indices; a
 * block is the read/program unit exposed to a filesystem. Periph ships no filesystem.
 * Failures are {@link BlockDeviceException}s.
 */
public interface BlockDevice {

    /** @return logical block size in bytes (read/program unit) */
    int blockSize();

    /** @return number of addressable logical blocks */
    long blockCount();

    /** @return erase unit in bytes; equals {@link #blockSize()} when {@link #needsErase()} is false */
    int eraseSize();

    /** @return true if blocks must be erased before they are programmed */
    boolean needsErase();

    /**
     * Read {@code count} consecutive blocks.
     *
     * @param block first block index
     * @param count number of blocks
     * @return {@code count * blockSize()} bytes
     */
    byte[] read(long block, int count) throws IOException;

    /**
     * Program {@code data.length / blockSize()} consecutive blocks starting at {@code block}.
     *
     * @param block first block index
     * @param data  a multiple of {@code blockSize()} bytes
     */
    void write(long block, byte[] data) throws IOException;

    /**
     * Erase the erase units covering {@code [block, block + count)}. No-op on SD.
     *
     * @param block first block index
     * @param count number of blocks
     */
    void erase(long block, int count) throws IOException;

    /** Block until all pending program/erase work has finished. */
    void sync() throws IOException;

    /** @return geometry and capabilities */
    BlockInfo info();

    /**
     * Optional ({@code info().badBlocks()}): whether erase unit {@code unit} is bad.
     *
     * @param unit erase unit index
     * @return true if bad
     */
    default boolean isBad(long unit) {
        return false;
    }

    /**
     * Optional: persist a bad-block mark for erase unit {@code unit}.
     *
     * @param unit erase unit index
     */
    default void markBad(long unit) throws IOException {
    }
}
