package memory

import (
	"bytes"
	"errors"
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
	"github.com/tuhde/Periph/go/periph/storage"
)

// norModel is a behavioural MX25L12845E implementing connection.Connection: 16 MiB array
// programmable 1 -> 0 only, page wrap on PP, 4/32/64 KiB and chip erase, WEL/WIP handling
// (WIP reads high for a few RDSR polls after every program/erase), BP block protect with
// P_FAIL/E_FAIL, secured OTP, deep power-down, RES/REMS/RDID/SFDP. Every opcode is recorded.
type norModel struct {
	connection.Connection // unused methods (Read, Close, ...) — never called

	mem                     []byte
	otp                     [512]byte
	inOTP, wel, dp, otpLock bool
	sr, scur                byte
	id                      [3]byte
	busyPolls, busyAfterOp  int
	stuckBusy               bool
	cmds                    []byte
	pp, se, be32, be64, ce  int
}

func newNorModel() *norModel {
	m := &norModel{mem: bytes.Repeat([]byte{0xFF}, 16*1024*1024), scur: 0x01, id: [3]byte{0xC2, 0x20, 0x18}, busyAfterOp: 2}
	for i := range m.otp {
		m.otp[i] = 0xFF
	}
	for i := 0; i < 16; i++ {
		m.otp[i] = byte(0xA0 + i)
	}
	return m
}

func (m *norModel) protectedFrom() uint32 {
	bp := (m.sr >> 2) & 0xF
	switch {
	case bp == 0:
		return 16 * 1024 * 1024
	case bp >= 8:
		return 0
	}
	return 16*1024*1024 - (65536 << bp)
}

func (m *norModel) isProtected(a, n uint32) bool { return a+n > m.protectedFrom() }
func (m *norModel) startBusy()                   { m.busyPolls = m.busyAfterOp; m.wel = false }

func (m *norModel) Write(d []byte) error {
	m.frame(d, nil)
	return nil
}

func (m *norModel) WriteRead(d []byte, n int) ([]byte, error) {
	out := make([]byte, n)
	m.frame(d, out)
	return out, nil
}

func (m *norModel) frame(d, out []byte) {
	op := d[0]
	m.cmds = append(m.cmds, op)
	if m.dp && op != 0xAB {
		return
	}
	busy := m.stuckBusy || m.busyPolls > 0
	if busy && op != 0x05 && op != 0x2B && op != 0x9F && op != 0x30 {
		return
	}
	var a uint32
	if len(d) >= 4 {
		a = uint32(d[1])<<16 | uint32(d[2])<<8 | uint32(d[3])
	}
	switch op {
	case 0x06:
		m.wel = true
	case 0x04:
		m.wel = false
	case 0x9F:
		copy(out, m.id[:])
	case 0x05:
		v := m.sr & 0xFC
		if m.wel {
			v |= 2
		}
		if busy {
			v |= 1
		}
		out[0] = v
		if m.busyPolls > 0 {
			m.busyPolls--
		}
	case 0x01:
		if m.wel && len(d) >= 2 {
			m.sr = d[1] & 0xFC
			m.startBusy()
		}
	case 0x03, 0x0B, 0x5A:
		for i := range out {
			switch {
			case op == 0x5A:
				out[i] = byte(int(a) + i)
			case m.inOTP:
				out[i] = m.otp[(int(a)+i)&0x1FF]
			default:
				out[i] = m.mem[(int(a)+i)&0xFFFFFF]
			}
		}
	case 0x02:
		if m.wel {
			data := d[4:]
			m.pp++
			switch {
			case m.inOTP:
				if m.otpLock {
					m.scur |= 0x20
				} else {
					for i, b := range data {
						m.otp[(int(a)+i)&0x1FF] &= b
					}
				}
			case m.isProtected(a&^255, 256):
				m.scur |= 0x20
			default:
				page := int(a &^ 255)
				for i, b := range data {
					m.mem[page+((int(a)+i)&255)] &= b
				}
			}
			m.startBusy()
		}
	case 0x20, 0x52, 0xD8:
		if m.wel {
			size := uint32(65536)
			switch op {
			case 0x20:
				size = 4096
				m.se++
			case 0x52:
				size = 32768
				m.be32++
			default:
				m.be64++
			}
			base := a &^ (size - 1)
			if m.isProtected(base, size) {
				m.scur |= 0x40
			} else {
				copy(m.mem[base:base+size], bytes.Repeat([]byte{0xFF}, int(size)))
			}
			m.startBusy()
		}
	case 0xC7, 0x60:
		if m.wel {
			m.ce++
			if (m.sr>>2)&0xF != 0 {
				m.scur |= 0x40
			} else {
				copy(m.mem, bytes.Repeat([]byte{0xFF}, len(m.mem)))
			}
			m.startBusy()
		}
	case 0xB9:
		m.dp = true
	case 0xAB:
		m.dp = false
		if len(out) > 0 {
			out[0] = 0x17
		}
	case 0x90:
		if len(out) >= 2 {
			out[0], out[1] = 0xC2, 0x17
		}
	case 0xB1:
		m.inOTP = true
	case 0xC1:
		m.inOTP = false
	case 0x2B:
		out[0] = m.scur
	case 0x2F:
		if m.wel {
			m.scur |= 0x02
			m.otpLock = true
			m.startBusy()
		}
	case 0x30:
		m.scur &^= 0x60
	}
}

func TestMX25InitChecksTheID(t *testing.T) {
	m := newNorModel()
	m.dp = true
	if _, err := NewMX25L12845EMinimal(m, MX25L12845EOptions{}); err != nil {
		t.Fatal(err)
	}
	if m.dp {
		t.Fatal("init must release deep power-down")
	}
	wrong := newNorModel()
	wrong.id[2] = 0x17
	if _, err := NewMX25L12845EMinimal(wrong, MX25L12845EOptions{}); !errors.Is(err, storage.ErrNotReady) {
		t.Fatalf("want ErrNotReady, got %v", err)
	}
	stuck := newNorModel()
	stuck.stuckBusy = true
	if _, err := NewMX25L12845EMinimal(stuck, MX25L12845EOptions{InitTimeout: 20 * time.Millisecond}); !errors.Is(err, storage.ErrTimeout) {
		t.Fatalf("want ErrTimeout, got %v", err)
	}
}

func TestMX25MinimalWriteIsEraseAware(t *testing.T) {
	m := newNorModel()
	f, _ := NewMX25L12845EMinimal(m, MX25L12845EOptions{})
	pattern := make([]byte, 600)
	for i := range pattern {
		pattern[i] = byte(i*7 + 3)
	}
	if err := f.WriteBytes(0x1F0, pattern); err != nil {
		t.Fatal(err)
	}
	back := make([]byte, 600)
	_ = f.ReadBytes(0x1F0, back)
	if !bytes.Equal(back, pattern) || m.se != 0 {
		t.Fatalf("blank flash write: se=%d", m.se)
	}
	if m.mem[0x1EF] != 0xFF || m.mem[0x1F0+600] != 0xFF {
		t.Fatal("neighbouring bytes touched")
	}
	pp := m.pp
	_ = f.WriteBytes(0x1F0, pattern)
	if m.pp != pp || m.se != 0 {
		t.Fatal("identical rewrite programmed or erased something")
	}
	more := make([]byte, 600)
	for i := range more {
		more[i] = pattern[i] & 0xF0
	}
	_ = f.WriteBytes(0x1F0, more)
	_ = f.ReadBytes(0x1F0, back)
	if !bytes.Equal(back, more) || m.se != 0 {
		t.Fatal("1->0 only rewrite needed an erase")
	}
	_ = f.WriteBytes(0x1F0, bytes.Repeat([]byte{0xFF}, 10))
	if m.se != 1 {
		t.Fatalf("0->1 must erase once, se=%d", m.se)
	}
	tail := make([]byte, 20)
	_ = f.ReadBytes(0x200, tail)
	if !bytes.Equal(tail, more[16:36]) {
		t.Fatal("rest of the sector lost in the erase")
	}
	big := make([]byte, 9000)
	for i := range big {
		big[i] = byte(i ^ (i >> 8))
	}
	_ = f.WriteBytes(0x2FF0, big)
	got := make([]byte, 9000)
	_ = f.ReadBytes(0x2FF0, got)
	if !bytes.Equal(got, big) {
		t.Fatal("multi-sector roundtrip")
	}
	if err := f.WriteBytes(MX25L12845ESize-2, big[:5]); !errors.Is(err, storage.ErrOutOfRange) {
		t.Fatalf("want ErrOutOfRange, got %v", err)
	}
}

func TestMX25Protection(t *testing.T) {
	m := newNorModel()
	f, _ := NewMX25L12845EFull(m, MX25L12845EOptions{})
	if bp, _ := f.Protection(); bp != 0 {
		t.Fatal("BP != 0 at power-up")
	}
	if err := f.SetProtection(8); err != nil {
		t.Fatal(err)
	}
	if err := f.PageProgram(0x100, []byte{0}); !errors.Is(err, storage.ErrWriteProtected) {
		t.Fatalf("want ErrWriteProtected, got %v", err)
	}
	if s, _ := f.ReadSecurity(); s&0x60 != 0 {
		t.Fatal("fail flags not cleared")
	}
	if err := f.EraseSector(0); !errors.Is(err, storage.ErrWriteProtected) {
		t.Fatalf("erase: %v", err)
	}
	if !f.Info().ReadOnly {
		t.Fatal("ReadOnly for BP >= 8")
	}
	if err := f.SetProtection(16); !errors.Is(err, storage.ErrOutOfRange) {
		t.Fatalf("BP 16: %v", err)
	}
	_ = f.SetProtection(0)
	if err := f.PageProgram(0x100, []byte{0}); err != nil || m.mem[0x100] != 0 {
		t.Fatalf("program after unprotect: %v", err)
	}
}

func TestMX25RawOperations(t *testing.T) {
	m := newNorModel()
	f, _ := NewMX25L12845EFull(m, MX25L12845EOptions{})
	data := make([]byte, 300)
	for i := range data {
		data[i] = byte(i)
	}
	if err := f.PageProgram(0x1000, data[:256]); err != nil {
		t.Fatal(err)
	}
	if err := f.PageProgram(0x10C0, data[:100]); !errors.Is(err, storage.ErrOutOfRange) {
		t.Fatal("page-crossing program must be refused")
	}
	if err := f.PageProgram(0, data[:257]); !errors.Is(err, storage.ErrOutOfRange) {
		t.Fatal("> 256 bytes must be refused")
	}
	a, b := make([]byte, 256), make([]byte, 256)
	_ = f.ReadBytes(0x1000, a)
	_ = f.FastRead(0x1000, b)
	if !bytes.Equal(a, data[:256]) || !bytes.Equal(b, a) {
		t.Fatal("READ / FAST_READ")
	}
	_ = f.EraseBlock32K(0x8000)
	_ = f.EraseBlock64K(0x10000)
	if err := f.ChipErase(); err != nil {
		t.Fatal(err)
	}
	if id, _ := f.ElectronicID(); id != 0x17 {
		t.Fatalf("RES %02X", id)
	}
	if man, dev, _ := f.DeviceIDs(); man != 0xC2 || dev != 0x17 {
		t.Fatalf("REMS %02X %02X", man, dev)
	}
	_ = f.DeepPowerDown()
	if !m.dp {
		t.Fatal("DP")
	}
	_ = f.ReleaseDeepPowerDown()
	sfdp := make([]byte, 8)
	_ = f.ReadSFDP(0, sfdp)
	if sfdp[1] != 1 {
		t.Fatal("SFDP")
	}
	if busy, _ := f.IsBusy(); busy {
		t.Fatal("busy after operations")
	}
	_ = f.WriteStatus(0x04)
	_ = f.WriteStatus(0)
	if m.be32 != 1 || m.be64 != 1 || m.ce != 1 || m.dp || m.mem[0x1000] != 0xFF {
		t.Fatalf("be32=%d be64=%d ce=%d dp=%v", m.be32, m.be64, m.ce, m.dp)
	}
}

func TestMX25SecuredOTP(t *testing.T) {
	m := newNorModel()
	f, _ := NewMX25L12845EFull(m, MX25L12845EOptions{})
	esn, err := f.SerialNumber()
	if err != nil || esn[0] != 0xA0 || esn[15] != 0xAF {
		t.Fatalf("ESN %v %v", esn, err)
	}
	if err := f.ProgramOTP(16, []byte{1, 2, 3, 4}); err != nil {
		t.Fatal(err)
	}
	back := make([]byte, 4)
	_ = f.ReadOTP(16, back)
	if !bytes.Equal(back, []byte{1, 2, 3, 4}) {
		t.Fatal("OTP readback")
	}
	if err := f.ProgramOTP(8, []byte{1}); !errors.Is(err, storage.ErrOutOfRange) {
		t.Fatal("ESN area must be refused")
	}
	if err := f.ProgramOTP(510, []byte{1, 2, 3, 4}); !errors.Is(err, storage.ErrOutOfRange) {
		t.Fatal("past the end of the OTP must be refused")
	}
	_ = f.LockOTP()
	if !m.otpLock || m.inOTP {
		t.Fatal("lock / mode")
	}
}

func TestMX25BlockDevice(t *testing.T) {
	m := newNorModel()
	f, _ := NewMX25L12845EFull(m, MX25L12845EOptions{})
	if f.BlockSize() != 512 || f.BlockCount() != 32768 || f.EraseSize() != 4096 || !f.NeedsErase() {
		t.Fatal("geometry")
	}
	w := make([]byte, 1024)
	for k := range w {
		w[k] = byte(k*5 + 1)
	}
	if err := f.Write(8, w); err != nil {
		t.Fatal(err)
	}
	r := make([]byte, 1024)
	_ = f.Read(8, r)
	if !bytes.Equal(r, w) {
		t.Fatal("roundtrip")
	}
	if err := f.Write(8, w); err != nil {
		t.Fatal("identical rewrite must succeed")
	}
	if err := f.Write(8, bytes.Repeat([]byte{0xFF}, 512)); !errors.Is(err, storage.ErrNotErased) {
		t.Fatalf("want ErrNotErased, got %v", err)
	}
	if m.se != 0 {
		t.Fatal("NotErased must not erase")
	}
	if err := f.Erase(8, 8); err != nil || m.mem[8*512] != 0xFF {
		t.Fatalf("erase: %v", err)
	}
	if err := f.Erase(9, 8); !errors.Is(err, storage.ErrOutOfRange) {
		t.Fatal("unaligned erase")
	}
	if err := f.Erase(32768-8, 16); !errors.Is(err, storage.ErrOutOfRange) {
		t.Fatal("erase past the end")
	}
	if err := f.Read(32768, r[:512]); !errors.Is(err, storage.ErrOutOfRange) {
		t.Fatal("read past the end")
	}
	be64, be32, se := m.be64, m.be32, m.se
	_ = f.Erase(0, 128+64+8)
	if m.be64-be64 != 1 || m.be32-be32 != 1 || m.se-se != 1 {
		t.Fatal("erase must pick 64K, then 32K, then 4K")
	}

	m2 := newNorModel()
	g, _ := NewMX25L12845EFull(m2, MX25L12845EOptions{AutoErase: true})
	_ = g.Write(0, w)
	if err := g.Write(0, bytes.Repeat([]byte{0xFF}, 512)); err != nil || m2.se != 1 {
		t.Fatalf("auto erase: %v se=%d", err, m2.se)
	}
	one := make([]byte, 512)
	_ = g.Read(1, one)
	if !bytes.Equal(one, w[512:]) {
		t.Fatal("neighbouring block lost")
	}

	for name, dev := range map[string]storage.BlockDevice{"NOR": g, "RAM": storage.NewRamBlockDevice(512, 64, 8, false)} {
		_ = dev.Erase(0, 8)
		x := bytes.Repeat([]byte{0x5A}, 512)
		y := make([]byte, 512)
		if err := dev.Write(0, x); err != nil || dev.Read(0, y) != nil || !bytes.Equal(x, y) {
			t.Fatalf("%s contract", name)
		}
		if err := dev.Read(dev.BlockCount(), y); !errors.Is(err, storage.ErrOutOfRange) {
			t.Fatalf("%s out of range: %v", name, err)
		}
	}
}

func TestMX25WaitReadyTimesOut(t *testing.T) {
	m := newNorModel()
	f, _ := NewMX25L12845EFull(m, MX25L12845EOptions{})
	m.stuckBusy = true
	if err := f.WaitReady(5 * time.Millisecond); !errors.Is(err, storage.ErrTimeout) {
		t.Fatalf("want ErrTimeout, got %v", err)
	}
	if busy, _ := f.IsBusy(); !busy {
		t.Fatal("IsBusy while stuck")
	}
}
