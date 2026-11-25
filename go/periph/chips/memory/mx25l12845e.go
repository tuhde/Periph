package memory

import (
	"bytes"
	"fmt"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
	"github.com/tuhde/Periph/go/periph/storage"
)

// MX25L12845E — Macronix 128-Mbit (16 MiB) serial NOR flash (specs/memory/mx25l12845e.md).
//
// MX25L12845EMinimal is a byte-addressable store with transparent erase: ReadBytes,
// an erase-aware WriteBytes (read-modify-erase-write per 4 KiB sector, programming
// only what changed) and EraseSector. MX25L12845EFull adds the raw program / erase
// commands, status and block protection, deep power-down, SFDP and the secured OTP,
// and implements storage.BlockDevice (512-byte blocks, 4 KiB erase units,
// NeedsErase() == true).
//
// Plain single-I/O SPI Mode 0 over a bare connection.Connection (Write / WriteRead;
// CS is held low for each frame by the connection).

// MX25L12845E geometry.
const (
	// MX25L12845ESize is the capacity in bytes.
	MX25L12845ESize = 16 * 1024 * 1024
	// MX25L12845EPageSize is the program granularity.
	MX25L12845EPageSize = 256
	// MX25L12845ESectorSize is the smallest erase unit.
	MX25L12845ESectorSize = 4096
)

const (
	mx25CmdWREN     = 0x06
	mx25CmdRDID     = 0x9F
	mx25CmdRES      = 0xAB
	mx25CmdREMS     = 0x90
	mx25CmdRDSR     = 0x05
	mx25CmdWRSR     = 0x01
	mx25CmdREAD     = 0x03
	mx25CmdFASTRD   = 0x0B
	mx25CmdPP       = 0x02
	mx25CmdSE       = 0x20
	mx25CmdBE32K    = 0x52
	mx25CmdBE64K    = 0xD8
	mx25CmdCE       = 0xC7
	mx25CmdDP       = 0xB9
	mx25CmdRDP      = 0xAB
	mx25CmdRDSFDP   = 0x5A
	mx25CmdENSO     = 0xB1
	mx25CmdEXSO     = 0xC1
	mx25CmdRDSCUR   = 0x2B
	mx25CmdWRSCUR   = 0x2F
	mx25CmdCLSR     = 0x30
	mx25SRWIP       = 0x01
	mx25SRWEL       = 0x02
	mx25SRBPMask    = 0x3C
	mx25SRBPShift   = 2
	mx25SCURPFail   = 0x20
	mx25SCUREFail   = 0x40
	mx25ReadChunk   = 128
	mx25TimeoutWRSR = 150 * time.Millisecond
	mx25TimeoutPP   = 10 * time.Millisecond
	mx25TimeoutSE   = 400 * time.Millisecond
	mx25TimeoutBE   = 3 * time.Second
	mx25TimeoutCE   = 250 * time.Second
)

// MX25L12845EOptions configure MX25L12845EMinimal / MX25L12845EFull.
type MX25L12845EOptions struct {
	// AutoErase makes BlockDevice.Write erase sectors itself when a 0->1
	// transition is needed; otherwise it returns storage.ErrNotErased.
	AutoErase bool
	// InitTimeout is how long init waits for a chip that is still busy (a reset
	// during a chip erase can leave it busy for up to 200 s). Zero means 250 s.
	InitTimeout time.Duration
}

func mx25Addr(cmd byte, address uint32) []byte {
	return []byte{cmd, byte(address >> 16), byte(address >> 8), byte(address)}
}

// MX25L12845EMinimal is the minimal driver: byte-addressed read, erase-aware write.
type MX25L12845EMinimal struct {
	c   connection.Connection
	opt MX25L12845EOptions
}

// NewMX25L12845EMinimal runs the init sequence (RDP, RDID check C2 20 18, wait
// until not busy). It returns storage.ErrNotReady if the ID is wrong and
// storage.ErrTimeout if the chip stays busy.
func NewMX25L12845EMinimal(c connection.Connection, opt MX25L12845EOptions) (*MX25L12845EMinimal, error) {
	m := &MX25L12845EMinimal{c: c, opt: opt}
	if err := m.c.Write([]byte{mx25CmdRDP}); err != nil { // leave deep power-down if a previous session left it there
		return nil, err
	}
	time.Sleep(time.Millisecond) // tRES2 = 100 us
	id, err := m.JEDECID()
	if err != nil {
		return nil, err
	}
	if id != [3]byte{0xC2, 0x20, 0x18} {
		return nil, fmt.Errorf("%w: JEDEC ID % X", storage.ErrNotReady, id)
	}
	t := opt.InitTimeout
	if t == 0 {
		t = mx25TimeoutCE
	}
	if err := m.waitReady(t); err != nil {
		return nil, err
	}
	return m, nil
}

func (m *MX25L12845EMinimal) status() (byte, error) {
	r, err := m.c.WriteRead([]byte{mx25CmdRDSR}, 1)
	if err != nil {
		return 0, err
	}
	return r[0], nil
}

func (m *MX25L12845EMinimal) wren() error {
	if err := m.c.Write([]byte{mx25CmdWREN}); err != nil {
		return err
	}
	sr, err := m.status()
	if err != nil {
		return err
	}
	if sr&mx25SRWEL == 0 {
		return fmt.Errorf("%w: WEL not set after WREN", storage.ErrTimeout) // the next command would be ignored
	}
	return nil
}

func (m *MX25L12845EMinimal) waitReady(timeout time.Duration) error {
	start := time.Now()
	for {
		sr, err := m.status()
		if err != nil {
			return err
		}
		if sr&mx25SRWIP == 0 {
			return nil
		}
		if time.Since(start) >= timeout {
			return storage.ErrTimeout
		}
		if timeout > 20*time.Millisecond {
			time.Sleep(time.Millisecond) // long operations: do not hammer the bus
		}
	}
}

func (m *MX25L12845EMinimal) checkFail() error {
	r, err := m.c.WriteRead([]byte{mx25CmdRDSCUR}, 1)
	if err != nil {
		return err
	}
	if r[0]&(mx25SCURPFail|mx25SCUREFail) == 0 {
		return nil
	}
	if err := m.c.Write([]byte{mx25CmdCLSR}); err != nil {
		return err
	}
	sr, err := m.status()
	if err != nil {
		return err
	}
	if sr&mx25SRBPMask != 0 {
		return storage.ErrWriteProtected
	}
	return fmt.Errorf("MX25L12845E: program/erase failed (security register 0x%02X)", r[0])
}

func (m *MX25L12845EMinimal) eraseCmd(cmd byte, address uint32, timeout time.Duration) error {
	if err := m.wren(); err != nil {
		return err
	}
	if err := m.c.Write(mx25Addr(cmd, address)); err != nil {
		return err
	}
	if err := m.waitReady(timeout); err != nil {
		return err
	}
	return m.checkFail()
}

func (m *MX25L12845EMinimal) program(address uint32, data []byte) error {
	if err := m.wren(); err != nil {
		return err
	}
	if err := m.c.Write(append(mx25Addr(mx25CmdPP, address), data...)); err != nil {
		return err
	}
	if err := m.waitReady(mx25TimeoutPP); err != nil {
		return err
	}
	return m.checkFail()
}

func (m *MX25L12845EMinimal) readRaw(cmd byte, address uint32, dummy bool, n int) ([]byte, error) {
	frame := mx25Addr(cmd, address)
	if dummy {
		frame = append(frame, 0)
	}
	return m.c.WriteRead(frame, n)
}

func (m *MX25L12845EMinimal) doRead(address uint32, buf []byte) error {
	for len(buf) > 0 {
		n := len(buf)
		if n > mx25ReadChunk {
			n = mx25ReadChunk
		}
		r, err := m.readRaw(mx25CmdREAD, address, false, n)
		if err != nil {
			return err
		}
		copy(buf, r)
		address += uint32(n)
		buf = buf[n:]
	}
	return nil
}

// JEDECID returns the three RDID bytes (manufacturer, memory type, density).
func (m *MX25L12845EMinimal) JEDECID() ([3]byte, error) {
	r, err := m.c.WriteRead([]byte{mx25CmdRDID}, 3)
	var id [3]byte
	if err != nil {
		return id, err
	}
	copy(id[:], r)
	return id, nil
}

// Size returns the capacity in bytes (16 777 216).
func (m *MX25L12845EMinimal) Size() uint32 { return MX25L12845ESize }

// ReadBytes reads len(buf) bytes starting at address (READ, 03h), any length and alignment.
func (m *MX25L12845EMinimal) ReadBytes(address uint32, buf []byte) error {
	if uint64(address)+uint64(len(buf)) > MX25L12845ESize {
		return storage.ErrOutOfRange
	}
	return m.doRead(address, buf)
}

// EraseSector erases the 4 KiB sector containing address (SE, 20h).
func (m *MX25L12845EMinimal) EraseSector(address uint32) error {
	if address >= MX25L12845ESize {
		return storage.ErrOutOfRange
	}
	return m.eraseCmd(mx25CmdSE, address, mx25TimeoutSE)
}

// WriteBytes is the erase-aware write; it blocks until the chip is ready.
//
// Per 4 KiB sector: if only 1->0 transitions are needed just the changed pages
// are programmed, otherwise the sector is erased and rewritten with the merged
// contents. It returns storage.ErrWriteProtected if the chip reports a failure
// while block-protect bits are set.
func (m *MX25L12845EMinimal) WriteBytes(address uint32, data []byte) error {
	if uint64(address)+uint64(len(data)) > MX25L12845ESize {
		return storage.ErrOutOfRange
	}
	sector := make([]byte, MX25L12845ESectorSize)
	for len(data) > 0 {
		base := address &^ (MX25L12845ESectorSize - 1)
		off := int(address - base)
		n := MX25L12845ESectorSize - off
		if n > len(data) {
			n = len(data)
		}
		if err := m.doRead(base, sector); err != nil {
			return err
		}
		needsErase := false
		for i := 0; i < n; i++ {
			if sector[off+i]&data[i] != data[i] { // would need a 0 -> 1 transition
				needsErase = true
				break
			}
		}
		if !needsErase {
			for pos := 0; pos < n; {
				a := address + uint32(pos)
				chunk := MX25L12845EPageSize - int(a&(MX25L12845EPageSize-1))
				if chunk > n-pos {
					chunk = n - pos
				}
				if !bytes.Equal(sector[off+pos:off+pos+chunk], data[pos:pos+chunk]) {
					if err := m.program(a, data[pos:pos+chunk]); err != nil {
						return err
					}
				}
				pos += chunk
			}
		} else {
			copy(sector[off:], data[:n])
			if err := m.eraseCmd(mx25CmdSE, base, mx25TimeoutSE); err != nil {
				return err
			}
			for page := 0; page < MX25L12845ESectorSize; page += MX25L12845EPageSize {
				blk := sector[page : page+MX25L12845EPageSize]
				if bytes.Count(blk, []byte{0xFF}) != MX25L12845EPageSize { // blank pages need no program
					if err := m.program(base+uint32(page), blk); err != nil {
						return err
					}
				}
			}
		}
		address += uint32(n)
		data = data[n:]
	}
	return nil
}

// MX25L12845EFull is the full driver. It extends MX25L12845EMinimal and
// implements storage.BlockDevice.
type MX25L12845EFull struct {
	MX25L12845EMinimal
	bp byte
}

var _ storage.BlockDevice = (*MX25L12845EFull)(nil)

// NewMX25L12845EFull runs the init sequence (see NewMX25L12845EMinimal).
func NewMX25L12845EFull(c connection.Connection, opt MX25L12845EOptions) (*MX25L12845EFull, error) {
	m, err := NewMX25L12845EMinimal(c, opt)
	if err != nil {
		return nil, err
	}
	return &MX25L12845EFull{MX25L12845EMinimal: *m}, nil
}

// FastRead reads len(buf) bytes with FAST_READ (0Bh).
func (f *MX25L12845EFull) FastRead(address uint32, buf []byte) error {
	if uint64(address)+uint64(len(buf)) > MX25L12845ESize {
		return storage.ErrOutOfRange
	}
	for len(buf) > 0 {
		n := len(buf)
		if n > mx25ReadChunk {
			n = mx25ReadChunk
		}
		r, err := f.readRaw(mx25CmdFASTRD, address, true, n)
		if err != nil {
			return err
		}
		copy(buf, r)
		address += uint32(n)
		buf = buf[n:]
	}
	return nil
}

// PageProgram is one PP of 1..256 bytes that must not cross a 256-byte page
// boundary; the bytes must be erased.
func (f *MX25L12845EFull) PageProgram(address uint32, data []byte) error {
	n := len(data)
	if n == 0 || n > MX25L12845EPageSize || uint64(address)+uint64(n) > MX25L12845ESize {
		return storage.ErrOutOfRange
	}
	if int(address&(MX25L12845EPageSize-1))+n > MX25L12845EPageSize {
		return storage.ErrOutOfRange // would wrap inside the page
	}
	return f.program(address, data)
}

// EraseBlock32K erases the 32 KiB block containing address (BE32K, 52h).
func (f *MX25L12845EFull) EraseBlock32K(address uint32) error {
	if address >= MX25L12845ESize {
		return storage.ErrOutOfRange
	}
	return f.eraseCmd(mx25CmdBE32K, address, mx25TimeoutBE)
}

// EraseBlock64K erases the 64 KiB block containing address (BE, D8h).
func (f *MX25L12845EFull) EraseBlock64K(address uint32) error {
	if address >= MX25L12845ESize {
		return storage.ErrOutOfRange
	}
	return f.eraseCmd(mx25CmdBE64K, address, mx25TimeoutBE)
}

// ChipErase erases the whole chip (CE, C7h): 80 s typ, 200 s max; needs BP3:0 == 0.
func (f *MX25L12845EFull) ChipErase() error {
	if err := f.wren(); err != nil {
		return err
	}
	if err := f.c.Write([]byte{mx25CmdCE}); err != nil {
		return err
	}
	if err := f.waitReady(mx25TimeoutCE); err != nil {
		return err
	}
	return f.checkFail()
}

// ReadStatus returns the status register.
func (f *MX25L12845EFull) ReadStatus() (byte, error) { return f.status() }

// WriteStatus does WREN + WRSR and waits for tW.
func (f *MX25L12845EFull) WriteStatus(value byte) error {
	if err := f.wren(); err != nil {
		return err
	}
	if err := f.c.Write([]byte{mx25CmdWRSR, value}); err != nil {
		return err
	}
	return f.waitReady(mx25TimeoutWRSR)
}

// Protection returns BP3:0 (0..15).
func (f *MX25L12845EFull) Protection() (byte, error) {
	sr, err := f.status()
	if err != nil {
		return 0, err
	}
	f.bp = (sr & mx25SRBPMask) >> mx25SRBPShift
	return f.bp, nil
}

// SetProtection sets BP3:0 (non-volatile); QE and SRWD are preserved.
func (f *MX25L12845EFull) SetProtection(bp byte) error {
	if bp > 15 {
		return storage.ErrOutOfRange
	}
	sr, err := f.status()
	if err != nil {
		return err
	}
	if err := f.WriteStatus(sr&^mx25SRBPMask | bp<<mx25SRBPShift); err != nil {
		return err
	}
	f.bp = bp
	return nil
}

// IsBusy reports whether a program, erase or WRSR is running (WIP).
func (f *MX25L12845EFull) IsBusy() (bool, error) {
	sr, err := f.status()
	return sr&mx25SRWIP != 0, err
}

// WaitReady waits until WIP = 0.
func (f *MX25L12845EFull) WaitReady(timeout time.Duration) error { return f.waitReady(timeout) }

// ReadSecurity returns the security register (RDSCUR).
func (f *MX25L12845EFull) ReadSecurity() (byte, error) {
	r, err := f.c.WriteRead([]byte{mx25CmdRDSCUR}, 1)
	if err != nil {
		return 0, err
	}
	return r[0], nil
}

// ClearFailFlags sends CLSR: clear P_FAIL / E_FAIL.
func (f *MX25L12845EFull) ClearFailFlags() error { return f.c.Write([]byte{mx25CmdCLSR}) }

// DeepPowerDown enters deep power-down (only RDP / RES are accepted afterwards).
func (f *MX25L12845EFull) DeepPowerDown() error { return f.c.Write([]byte{mx25CmdDP}) }

// ReleaseDeepPowerDown leaves deep power-down and waits tRES2.
func (f *MX25L12845EFull) ReleaseDeepPowerDown() error {
	err := f.c.Write([]byte{mx25CmdRDP})
	time.Sleep(time.Millisecond)
	return err
}

// ElectronicID returns the RES electronic ID (17h).
func (f *MX25L12845EFull) ElectronicID() (byte, error) {
	r, err := f.c.WriteRead([]byte{mx25CmdRES, 0, 0, 0}, 1)
	if err != nil {
		return 0, err
	}
	return r[0], nil
}

// DeviceIDs returns the REMS manufacturer (C2h) and device ID (17h).
func (f *MX25L12845EFull) DeviceIDs() (manufacturer, device byte, err error) {
	r, err := f.c.WriteRead([]byte{mx25CmdREMS, 0, 0, 0}, 2)
	if err != nil {
		return 0, 0, err
	}
	return r[0], r[1], nil
}

// ReadSFDP reads len(buf) bytes of the SFDP table from address (RDSFDP, 5Ah).
func (f *MX25L12845EFull) ReadSFDP(address uint32, buf []byte) error {
	for len(buf) > 0 {
		n := len(buf)
		if n > mx25ReadChunk {
			n = mx25ReadChunk
		}
		r, err := f.readRaw(mx25CmdRDSFDP, address, true, n)
		if err != nil {
			return err
		}
		copy(buf, r)
		address += uint32(n)
		buf = buf[n:]
	}
	return nil
}

// ReadOTP reads len(buf) bytes at OTP offset (0..511) of the 4 Kbit secured OTP.
func (f *MX25L12845EFull) ReadOTP(offset uint32, buf []byte) error {
	if uint64(offset)+uint64(len(buf)) > 512 {
		return storage.ErrOutOfRange
	}
	if err := f.c.Write([]byte{mx25CmdENSO}); err != nil {
		return err
	}
	err := f.doRead(offset, buf)
	if e := f.c.Write([]byte{mx25CmdEXSO}); err == nil {
		err = e
	}
	return err
}

// ProgramOTP programs OTP bytes at offset (16..511 only: 0..15 is the ESN area); one-time programmable.
func (f *MX25L12845EFull) ProgramOTP(offset uint32, data []byte) error {
	if offset < 16 || len(data) == 0 || uint64(offset)+uint64(len(data)) > 512 {
		return storage.ErrOutOfRange
	}
	if err := f.c.Write([]byte{mx25CmdENSO}); err != nil {
		return err
	}
	var err error
	for len(data) > 0 && err == nil {
		n := MX25L12845EPageSize - int(offset&(MX25L12845EPageSize-1))
		if n > len(data) {
			n = len(data)
		}
		err = f.program(offset, data[:n])
		offset += uint32(n)
		data = data[n:]
	}
	if e := f.c.Write([]byte{mx25CmdEXSO}); err == nil {
		err = e
	}
	return err
}

// LockOTP locks the customer OTP area for good (WRSCUR, sets LDSO) — IRREVERSIBLE.
func (f *MX25L12845EFull) LockOTP() error {
	if err := f.wren(); err != nil {
		return err
	}
	if err := f.c.Write([]byte{mx25CmdWRSCUR}); err != nil {
		return err
	}
	return f.waitReady(mx25TimeoutWRSR)
}

// SerialNumber returns the 16 bytes of OTP 000h-00Fh (the factory ESN when
// ReadSecurity()&1 != 0; blank 0xFF otherwise).
func (f *MX25L12845EFull) SerialNumber() ([16]byte, error) {
	var out [16]byte
	err := f.ReadOTP(0, out[:])
	return out, err
}

// BlockSize implements storage.BlockDevice.
func (f *MX25L12845EFull) BlockSize() int { return 512 }

// BlockCount implements storage.BlockDevice.
func (f *MX25L12845EFull) BlockCount() uint32 { return MX25L12845ESize / 512 }

// EraseSize implements storage.BlockDevice.
func (f *MX25L12845EFull) EraseSize() int { return MX25L12845ESectorSize }

// NeedsErase implements storage.BlockDevice.
func (f *MX25L12845EFull) NeedsErase() bool { return true }

// Read implements storage.BlockDevice.
func (f *MX25L12845EFull) Read(block uint32, buf []byte) error {
	count := uint64(len(buf) / 512)
	if uint64(block)+count > uint64(f.BlockCount()) {
		return storage.ErrOutOfRange
	}
	return f.doRead(block*512, buf)
}

// Write implements storage.BlockDevice. Without AutoErase it returns
// storage.ErrNotErased when a 0->1 transition would be needed.
func (f *MX25L12845EFull) Write(block uint32, data []byte) error {
	count := uint64(len(data) / 512)
	if uint64(block)+count > uint64(f.BlockCount()) {
		return storage.ErrOutOfRange
	}
	address := block * 512
	if f.opt.AutoErase {
		return f.WriteBytes(address, data)
	}
	// No auto-erase: program page by page, but only where no 0 -> 1 transition is needed.
	for len(data) > 0 {
		n := MX25L12845EPageSize - int(address&(MX25L12845EPageSize-1))
		if n > len(data) {
			n = len(data)
		}
		old := make([]byte, n)
		if err := f.doRead(address, old); err != nil {
			return err
		}
		for i := 0; i < n; i++ {
			if old[i]&data[i] != data[i] {
				return storage.ErrNotErased
			}
		}
		if !bytes.Equal(old, data[:n]) {
			if err := f.program(address, data[:n]); err != nil {
				return err
			}
		}
		address += uint32(n)
		data = data[n:]
	}
	return nil
}

// Erase implements storage.BlockDevice: the range must be 4 KiB-aligned (multiples of 8 blocks).
func (f *MX25L12845EFull) Erase(block, count uint32) error {
	if block%8 != 0 || count%8 != 0 || uint64(block)+uint64(count) > uint64(f.BlockCount()) {
		return storage.ErrOutOfRange
	}
	address := block * 512
	left := count * 512
	for left > 0 {
		var err error
		switch {
		case address&0xFFFF == 0 && left >= 65536:
			err = f.EraseBlock64K(address)
			address += 65536
			left -= 65536
		case address&0x7FFF == 0 && left >= 32768:
			err = f.EraseBlock32K(address)
			address += 32768
			left -= 32768
		default:
			err = f.EraseSector(address)
			address += MX25L12845ESectorSize
			left -= MX25L12845ESectorSize
		}
		if err != nil {
			return err
		}
	}
	return nil
}

// Sync implements storage.BlockDevice: wait until the chip is idle.
func (f *MX25L12845EFull) Sync() error { return f.waitReady(mx25TimeoutCE) }

// Info implements storage.BlockDevice.
func (f *MX25L12845EFull) Info() storage.BlockInfo {
	return storage.BlockInfo{BlockSize: 512, BlockCount: f.BlockCount(), EraseSize: MX25L12845ESectorSize,
		NeedsErase: true, ReadOnly: f.bp >= 8, Name: "MX25L12845E"}
}

// IsBad implements storage.BlockDevice (NOR flash has no bad blocks).
func (f *MX25L12845EFull) IsBad(uint32) bool { return false }

// MarkBad implements storage.BlockDevice (no-op).
func (f *MX25L12845EFull) MarkBad(uint32) error { return nil }
