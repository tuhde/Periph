package storage

// RamBlockDevice is a RAM-backed BlockDevice mock for tests.
//
// With eraseBlocks > 1 it models NOR-like semantics: erased blocks read 0xFF
// and programming an un-erased block while autoErase is false returns
// ErrNotErased.
type RamBlockDevice struct {
	bs, eb    int
	bc        uint32
	autoErase bool
	mem       []byte
	erased    []bool
}

// NewRamBlockDevice creates a RamBlockDevice of blockCount blocks of
// blockSize bytes; eraseBlocks is the number of blocks per erase unit
// (1 = no erase needed).
func NewRamBlockDevice(blockSize int, blockCount uint32, eraseBlocks int, autoErase bool) *RamBlockDevice {
	if eraseBlocks < 1 {
		eraseBlocks = 1
	}
	d := &RamBlockDevice{bs: blockSize, eb: eraseBlocks, bc: blockCount, autoErase: autoErase,
		mem: make([]byte, blockSize*int(blockCount)), erased: make([]bool, blockCount)}
	for i := range d.erased {
		d.erased[i] = true
	}
	if eraseBlocks > 1 {
		for i := range d.mem {
			d.mem[i] = 0xFF
		}
	}
	return d
}

// BlockSize implements BlockDevice.
func (d *RamBlockDevice) BlockSize() int { return d.bs }

// BlockCount implements BlockDevice.
func (d *RamBlockDevice) BlockCount() uint32 { return d.bc }

// EraseSize implements BlockDevice.
func (d *RamBlockDevice) EraseSize() int { return d.bs * d.eb }

// NeedsErase implements BlockDevice.
func (d *RamBlockDevice) NeedsErase() bool { return d.eb > 1 }

// Read implements BlockDevice.
func (d *RamBlockDevice) Read(block uint32, buf []byte) error {
	count := len(buf) / d.bs
	if uint64(block)+uint64(count) > uint64(d.bc) {
		return ErrOutOfRange
	}
	copy(buf, d.mem[int(block)*d.bs:])
	return nil
}

// Write implements BlockDevice.
func (d *RamBlockDevice) Write(block uint32, data []byte) error {
	count := len(data) / d.bs
	if uint64(block)+uint64(count) > uint64(d.bc) {
		return ErrOutOfRange
	}
	if d.NeedsErase() {
		for i := 0; i < count; i++ {
			b := int(block) + i
			if !d.erased[b] {
				if !d.autoErase {
					return ErrNotErased
				}
				if err := d.Erase(uint32(b-b%d.eb), uint32(d.eb)); err != nil {
					return err
				}
			}
		}
	}
	copy(d.mem[int(block)*d.bs:], data)
	for i := 0; i < count; i++ {
		d.erased[int(block)+i] = false
	}
	return nil
}

// Erase implements BlockDevice.
func (d *RamBlockDevice) Erase(block, count uint32) error {
	if !d.NeedsErase() {
		return nil
	}
	if int(block)%d.eb != 0 || int(count)%d.eb != 0 || uint64(block)+uint64(count) > uint64(d.bc) {
		return ErrOutOfRange
	}
	for i := int(block) * d.bs; i < int(block+count)*d.bs; i++ {
		d.mem[i] = 0xFF
	}
	for i := uint32(0); i < count; i++ {
		d.erased[block+i] = true
	}
	return nil
}

// Sync implements BlockDevice.
func (d *RamBlockDevice) Sync() error { return nil }

// Info implements BlockDevice.
func (d *RamBlockDevice) Info() BlockInfo {
	return BlockInfo{BlockSize: d.bs, BlockCount: d.bc, EraseSize: d.EraseSize(), NeedsErase: d.NeedsErase(), Name: "RAM"}
}

// IsBad implements BlockDevice (never).
func (d *RamBlockDevice) IsBad(uint32) bool { return false }

// MarkBad implements BlockDevice (no-op).
func (d *RamBlockDevice) MarkBad(uint32) error { return nil }
