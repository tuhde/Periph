package it.uhde.periph.storage;

import java.util.Arrays;

/**
 * RAM-backed {@link BlockDevice} mock for tests.
 *
 * <p>With {@code eraseBlocks > 1} it models NOR-like semantics: erased blocks read
 * {@code 0xFF} and programming an un-erased block while {@code autoErase} is false
 * fails with {@link BlockDeviceException.Kind#NOT_ERASED}.
 */
public final class RamBlockDevice implements BlockDevice {

    private final int bs;
    private final int bc;
    private final int eb;
    private final boolean auto;
    private final byte[] mem;
    private final boolean[] erased;

    /**
     * @param blockSize   bytes per block
     * @param blockCount  number of blocks
     * @param eraseBlocks blocks per erase unit (1 = no erase needed)
     * @param autoErase   erase automatically before programming
     */
    public RamBlockDevice(int blockSize, int blockCount, int eraseBlocks, boolean autoErase) {
        this.bs = blockSize;
        this.bc = blockCount;
        this.eb = Math.max(1, eraseBlocks);
        this.auto = autoErase;
        this.mem = new byte[blockSize * blockCount];
        if (eb > 1) Arrays.fill(mem, (byte) 0xFF);
        this.erased = new boolean[blockCount];
        Arrays.fill(erased, true);
    }

    @Override public int blockSize() { return bs; }
    @Override public long blockCount() { return bc; }
    @Override public int eraseSize() { return bs * eb; }
    @Override public boolean needsErase() { return eb > 1; }

    @Override
    public byte[] read(long block, int count) throws BlockDeviceException {
        if (block < 0 || block + count > bc) throw new BlockDeviceException(BlockDeviceException.Kind.OUT_OF_RANGE, "read beyond end");
        return Arrays.copyOfRange(mem, (int) block * bs, (int) (block + count) * bs);
    }

    @Override
    public void write(long block, byte[] data) throws BlockDeviceException {
        int count = data.length / bs;
        if (block < 0 || block + count > bc) throw new BlockDeviceException(BlockDeviceException.Kind.OUT_OF_RANGE, "write beyond end");
        if (needsErase()) {
            for (int i = 0; i < count; i++) {
                int b = (int) block + i;
                if (!erased[b]) {
                    if (!auto) throw new BlockDeviceException(BlockDeviceException.Kind.NOT_ERASED, "block " + b + " not erased");
                    erase(b - b % eb, eb);
                }
            }
        }
        System.arraycopy(data, 0, mem, (int) block * bs, data.length);
        for (int i = 0; i < count; i++) erased[(int) block + i] = false;
    }

    @Override
    public void erase(long block, int count) throws BlockDeviceException {
        if (!needsErase()) return;
        if (block % eb != 0 || count % eb != 0 || block + count > bc) {
            throw new BlockDeviceException(BlockDeviceException.Kind.OUT_OF_RANGE, "unaligned erase");
        }
        Arrays.fill(mem, (int) block * bs, (int) (block + count) * bs, (byte) 0xFF);
        for (int i = 0; i < count; i++) erased[(int) block + i] = true;
    }

    @Override public void sync() { }

    @Override
    public BlockInfo info() {
        return new BlockInfo(bs, bc, eraseSize(), needsErase(), false, false, "RAM");
    }
}
