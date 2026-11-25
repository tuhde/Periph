package it.uhde.periph.storage

import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor

/**
 * Geometry and capabilities of a {@link BlockDevice}.
 */
@CompileStatic
@TupleConstructor
class BlockInfo {
    /** Read/program unit in bytes. */
    final int blockSize
    /** Number of addressable blocks. */
    final long blockCount
    /** Erase unit in bytes (a multiple of {@code blockSize}). */
    final int eraseSize
    /** A block must be erased before it is programmed. */
    final boolean needsErase
    /** Write protected. */
    final boolean readOnly
    /** Device may contain bad erase units. */
    final boolean badBlocks
    /** Short device name (e.g. SD product name). */
    final String name
}
