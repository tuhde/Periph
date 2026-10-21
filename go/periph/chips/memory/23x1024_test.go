package memory

import (
	"bytes"
	"errors"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// sram23x1024Model is a behavioural 23X1024 implementing connection.Connection:
// it parses the instruction frames the driver sends and models the array, the
// MODE register and the Byte/Page/Sequential address-increment rules. Every
// frame is recorded on frames.
type sram23x1024Model struct {
	mem    []byte
	frames [][]byte
	mode   byte
	dead   bool // RDMR returns zeros (floating MISO)
}

func newSram23x1024Model() *sram23x1024Model {
	return &sram23x1024Model{mem: make([]byte, 131072), mode: 0x40}
}

func (m *sram23x1024Model) next(addr int) int {
	if m.mode&0xC0 == 0x80 {
		return (addr &^ 31) | ((addr + 1) & 31)
	}
	return (addr + 1) & 0x1FFFF
}

func (m *sram23x1024Model) Write(data []byte) error {
	m.frames = append(m.frames, append([]byte(nil), data...))
	switch {
	case data[0] == 0x01 && len(data) == 2:
		m.mode = data[1]
	case data[0] == 0x02 && len(data) >= 4:
		addr := int(data[1])<<16 | int(data[2])<<8 | int(data[3])
		for _, b := range data[4:] {
			m.mem[addr] = b
			addr = m.next(addr)
			if m.mode&0xC0 == 0x00 {
				break
			}
		}
	}
	return nil
}

func (m *sram23x1024Model) Read(n int) ([]byte, error) { return make([]byte, n), nil }

func (m *sram23x1024Model) WriteRead(data []byte, n int) ([]byte, error) {
	m.frames = append(m.frames, append([]byte(nil), data...))
	out := make([]byte, n)
	switch {
	case data[0] == 0x05:
		if !m.dead {
			out[0] = m.mode
		}
	case data[0] == 0x03 && len(data) == 4:
		addr := int(data[1])<<16 | int(data[2])<<8 | int(data[3])
		for i := 0; i < n; i++ {
			out[i] = m.mem[addr]
			addr = m.next(addr)
			if m.mode&0xC0 == 0x00 {
				break
			}
		}
	}
	return out, nil
}

func (m *sram23x1024Model) Close() error                { return nil }
func (m *sram23x1024Model) Enable()                     {}
func (m *sram23x1024Model) Disable()                    {}
func (m *sram23x1024Model) IsEnabled() bool             { return true }
func (m *sram23x1024Model) IntPin() connection.InputPin { return nil }
func (m *sram23x1024Model) EnPin() connection.OutputPin { return nil }

func Test23X1024Init(t *testing.T) {
	conn := newSram23x1024Model()
	if _, err := NewSRAM23X1024Minimal(conn); err != nil {
		t.Fatal(err)
	}
	if len(conn.frames) != 2 || !bytes.Equal(conn.frames[0], []byte{0x01, 0x40}) || !bytes.Equal(conn.frames[1], []byte{0x05}) {
		t.Fatalf("init frames = %x", conn.frames)
	}
	dead := newSram23x1024Model()
	dead.dead = true
	if _, err := NewSRAM23X1024Minimal(dead); err == nil {
		t.Fatal("expected init error on MODE mismatch")
	}
}

func Test23X1024ByteAccess(t *testing.T) {
	conn := newSram23x1024Model()
	chip, _ := NewSRAM23X1024Minimal(conn)
	if err := chip.WriteByteAt(0x1ABCD, 0x5A); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(conn.frames[len(conn.frames)-1], []byte{0x02, 0x01, 0xAB, 0xCD, 0x5A}) || conn.mem[0x1ABCD] != 0x5A {
		t.Fatalf("write frame = %x", conn.frames[len(conn.frames)-1])
	}
	v, err := chip.ReadByteAt(0x1ABCD)
	if err != nil || v != 0x5A {
		t.Fatalf("ReadByteAt = %x, %v", v, err)
	}
	if !bytes.Equal(conn.frames[len(conn.frames)-1], []byte{0x03, 0x01, 0xAB, 0xCD}) {
		t.Fatalf("read frame = %x", conn.frames[len(conn.frames)-1])
	}
	if _, err := chip.ReadByteAt(0x20000); !errors.Is(err, ErrSRAM23X1024Range) {
		t.Fatalf("expected range error, got %v", err)
	}
}

func Test23X1024BlockChunking(t *testing.T) {
	conn := newSram23x1024Model()
	chip, _ := NewSRAM23X1024Full(conn)
	pattern := make([]byte, 600)
	for i := range pattern {
		pattern[i] = byte(i*7 + 3)
	}
	if err := chip.Write(0x100, pattern); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(conn.mem[0x100:0x100+600], pattern) {
		t.Fatal("block not stored")
	}
	got, err := chip.Read(0x100, 600)
	if err != nil || !bytes.Equal(got, pattern) {
		t.Fatalf("Read mismatch: %v", err)
	}
	writes := 0
	for _, f := range conn.frames {
		if f[0] == 0x02 {
			writes++
		}
	}
	if writes != 3 {
		t.Fatalf("expected 3 write frames (256+256+88), got %d", writes)
	}

	if err := chip.SetMaxChunk(100); err != nil || chip.MaxChunk() != 100 {
		t.Fatal("SetMaxChunk")
	}
	conn.frames = nil
	if err := chip.Write(0, pattern[:250]); err != nil {
		t.Fatal(err)
	}
	if len(conn.frames) != 3 || len(conn.frames[0]) != 104 || len(conn.frames[2]) != 54 {
		t.Fatalf("custom chunking frames = %d", len(conn.frames))
	}
	if chip.SetMaxChunk(257) == nil {
		t.Fatal("SetMaxChunk(257) should fail")
	}
}

func Test23X1024Fill(t *testing.T) {
	conn := newSram23x1024Model()
	chip, _ := NewSRAM23X1024Full(conn)
	if err := chip.Fill(0x2000, 100, 0xA5); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 100; i++ {
		if conn.mem[0x2000+i] != 0xA5 {
			t.Fatalf("fill byte %d", i)
		}
	}
	if conn.mem[0x2064] != 0 || conn.mem[0x1FFF] != 0 {
		t.Fatal("fill wrote outside the range")
	}
	if err := chip.Fill(0x3000, 5, 0); err != nil || conn.mem[0x3004] != 0 {
		t.Fatal("default fill")
	}
}

func Test23X1024RangeErrors(t *testing.T) {
	chip, _ := NewSRAM23X1024Full(newSram23x1024Model())
	if _, err := chip.Read(0x1FFFF, 2); !errors.Is(err, ErrSRAM23X1024Range) {
		t.Fatal("Read past end")
	}
	if err := chip.Write(0x1FFFF, []byte{1, 2}); !errors.Is(err, ErrSRAM23X1024Range) {
		t.Fatal("Write past end")
	}
	if err := chip.Fill(0x1FFFF, 2, 0); !errors.Is(err, ErrSRAM23X1024Range) {
		t.Fatal("Fill past end")
	}
}

func Test23X1024Modes(t *testing.T) {
	conn := newSram23x1024Model()
	chip, _ := NewSRAM23X1024Full(conn)

	if err := chip.SetMode(SRAM23X1024ModePage); err != nil {
		t.Fatal(err)
	}
	if m, _ := chip.GetMode(); m != SRAM23X1024ModePage || conn.mode != 0x80 {
		t.Fatal("page mode not set")
	}
	conn.frames = nil
	data := make([]byte, 64)
	for i := range data {
		data[i] = byte(0xC0 + i)
	}
	if err := chip.Write(0x4010, data); err != nil {
		t.Fatal(err)
	}
	if len(conn.frames) != 3 || !bytes.Equal(conn.mem[0x4010:0x4050], data) || conn.mem[0x4050] != 0 {
		t.Fatalf("page-mode write frames=%d", len(conn.frames))
	}
	got, _ := chip.Read(0x4010, 64)
	if !bytes.Equal(got, data) {
		t.Fatal("page-mode read not linear")
	}

	if err := chip.SetMode(SRAM23X1024ModeByte); err != nil {
		t.Fatal(err)
	}
	conn.frames = nil
	_ = chip.Write(0x500, data[:4])
	if len(conn.frames) != 4 {
		t.Fatalf("byte mode frames = %d", len(conn.frames))
	}
	got, _ = chip.Read(0x500, 4)
	if !bytes.Equal(got, data[:4]) {
		t.Fatal("byte-mode read")
	}

	_ = chip.SetMode(SRAM23X1024ModeSequential)
	if chip.SetMode(0xC0) == nil {
		t.Fatal("SetMode(0xC0) should fail")
	}

	conn.mode = 0x4F
	if m, _ := chip.GetMode(); m != SRAM23X1024ModeSequential {
		t.Fatal("GetMode should mask reserved bits")
	}
	conn.mode = 0x40
}

func Test23X1024ResetIO(t *testing.T) {
	conn := newSram23x1024Model()
	chip, _ := NewSRAM23X1024Full(conn)
	conn.frames = nil
	if err := chip.ResetIO(); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(conn.frames[0], []byte{0xFF}) {
		t.Fatalf("RSTIO frame = %x", conn.frames[0])
	}
	if chip.Size() != 131072 {
		t.Fatal("Size")
	}
}
