package it.uhde.periph.storage

import groovy.transform.CompileStatic

/**
 * RAM-backed {@link BlockDevice} mock for tests.
 *
 * <p>With {@code eraseBlocks > 1} it models NOR-like semantics: erased blocks read
 * {@code 0xFF} and programming an un-erased block while {@code autoErase} is false fails with
 * {@link BlockDeviceException.Kind#NOT_ERASED}.
 */
@CompileStatic
class RamBlockDevice implements BlockDevice {

    private final int bs
    private final int bc
    private final int eb
    private final boolean auto
    private final byte[] mem
    private final boolean[] erased

    /**
     * @param blockSize   bytes per block
     * @param blockCount  number of blocks
     * @param eraseBlocks blocks per erase unit (1 = no erase needed)
     * @param autoErase   erase automatically before programming
     */
    RamBlockDevice(int blockSize, int blockCount, int eraseBlocks, boolean autoErase) {
        this.bs = blockSize
        this.bc = blockCount
        this.eb = Math.max(1, eraseBlocks)
        this.auto = autoErase
        this.mem = new byte[blockSize * blockCount]
        if (eb > 1) Arrays.fill(mem, (byte) 0xFF)
        this.erased = new boolean[blockCount]
        Arrays.fill(erased, true)
    }

    int blockSize() { bs }
    long blockCount() { bc }
    int eraseSize() { bs * eb }
    boolean needsErase() { eb > 1 }

    byte[] read(long block, int count) throws IOException {
        if (block < 0 || block + count > bc) throw new BlockDeviceException(BlockDeviceException.Kind.OUT_OF_RANGE, 'read beyond end')
        Arrays.copyOfRange(mem, (int) block * bs, (int) (block + count) * bs)
    }

    void write(long block, byte[] data) throws IOException {
        int count = data.length.intdiv(bs)
        if (block < 0 || block + count > bc) throw new BlockDeviceException(BlockDeviceException.Kind.OUT_OF_RANGE, 'write beyond end')
        if (needsErase()) {
            for (int i = 0; i < count; i++) {
                int b = (int) block + i
                if (!erased[b]) {
                    if (!auto) throw new BlockDeviceException(BlockDeviceException.Kind.NOT_ERASED, 'block ' + b + ' not erased')
                    erase(b - b % eb, eb)
                }
            }
        }
        System.arraycopy(data, 0, mem, (int) block * bs, data.length)
        for (int i = 0; i < count; i++) erased[(int) block + i] = false
    }

    void erase(long block, int count) throws IOException {
        if (!needsErase()) return
        if (block % eb != 0 || count % eb != 0 || block + count > bc) {
            throw new BlockDeviceException(BlockDeviceException.Kind.OUT_OF_RANGE, 'unaligned erase')
        }
        Arrays.fill(mem, (int) block * bs, (int) (block + count) * bs, (byte) 0xFF)
        for (int i = 0; i < count; i++) erased[(int) block + i] = true
    }

    void sync() { }

    BlockInfo info() {
        new BlockInfo(bs, (long) bc, bs * eb, needsErase(), false, false, 'RAM')
    }
}
