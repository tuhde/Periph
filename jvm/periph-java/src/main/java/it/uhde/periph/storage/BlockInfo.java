package it.uhde.periph.storage;

/**
 * Geometry and capabilities of a {@link BlockDevice}.
 *
 * @param blockSize  read/program unit in bytes
 * @param blockCount number of addressable blocks
 * @param eraseSize  erase unit in bytes (a multiple of {@code blockSize})
 * @param needsErase a block must be erased before it is programmed
 * @param readOnly   write protected
 * @param badBlocks  device may contain bad erase units
 * @param name       short device name (e.g. SD product name)
 */
public record BlockInfo(int blockSize, long blockCount, int eraseSize, boolean needsErase,
                        boolean readOnly, boolean badBlocks, String name) {
}
