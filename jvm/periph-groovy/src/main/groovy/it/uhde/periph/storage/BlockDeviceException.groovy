package it.uhde.periph.storage

import groovy.transform.CompileStatic

/**
 * Error of a {@link BlockDevice} operation; {@link #kind()} says which. A transport
 * failure is reported as {@link Kind#IO} with the underlying exception as the cause.
 */
@CompileStatic
class BlockDeviceException extends IOException {

    /** What went wrong (specs/feature_block_device.md). */
    static enum Kind {
        /** Device absent or initialisation failed. */
        NOT_READY,
        /** Block index/count beyond {@code blockCount()}, or unaligned {@code erase()}. */
        OUT_OF_RANGE,
        /** Program to an un-erased block while auto-erase is off. */
        NOT_ERASED,
        /** Card WP / status-register block protection. */
        WRITE_PROTECTED,
        /** Target erase unit is marked bad (NAND). */
        BAD_BLOCK,
        /** Uncorrectable ECC error on read (NAND). */
        ECC,
        /** SD command/data CRC mismatch. */
        CRC,
        /** Busy never cleared. */
        TIMEOUT,
        /** Transport failure. */
        IO
    }

    private final Kind errorKind

    /**
     * @param kind    error kind
     * @param message detail message
     */
    BlockDeviceException(Kind kind, String message) {
        super(kind.toString() + ': ' + message)
        this.errorKind = kind
    }

    /**
     * @param kind  error kind
     * @param cause underlying transport failure
     */
    BlockDeviceException(Kind kind, Throwable cause) {
        super(kind.toString(), cause)
        this.errorKind = kind
    }

    /** @return the error kind */
    Kind kind() {
        errorKind
    }
}
