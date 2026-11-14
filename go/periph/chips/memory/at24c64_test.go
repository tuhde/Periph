package memory

import (
	"bytes"
	"errors"
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// at24c64Mock is an in-memory fake RegisterConnection with a configurable
// register address width (big-endian), recording every write/read frame.
type at24c64Mock struct {
	regBytes  int
	registers map[uint32]byte
	writes    [][]byte
	nack      int // remaining writes to reject (NACK)
	nackAfter int // successful writes before NACKs start
}

func newAT24C64Mock(regBytes int) *at24c64Mock {
	return &at24c64Mock{regBytes: regBytes, registers: map[uint32]byte{}}
}

func (m *at24c64Mock) addr(b []byte) uint32 {
	var a uint32
	for i := 0; i < m.regBytes; i++ {
		a = a<<8 | uint32(b[i])
	}
	return a
}

func (m *at24c64Mock) frame(reg uint32) []byte {
	f := make([]byte, m.regBytes)
	for i := 0; i < m.regBytes; i++ {
		f[i] = byte(reg >> (8 * (m.regBytes - 1 - i)))
	}
	return f
}

func (m *at24c64Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	if m.nackAfter > 0 {
		m.nackAfter--
	} else if m.nack > 0 {
		m.nack--
		return errors.New("NACK")
	}
	if len(data) > m.regBytes {
		a := m.addr(data)
		for i, v := range data[m.regBytes:] {
			m.registers[a+uint32(i)] = v
		}
	}
	return nil
}

func (m *at24c64Mock) Read(n int) ([]byte, error) { return make([]byte, n), nil }

func (m *at24c64Mock) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	a := m.addr(data)
	out := make([]byte, n)
	for i := range out {
		out[i] = m.registers[a+uint32(i)]
	}
	return out, nil
}

func (m *at24c64Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	return m.WriteRead(m.frame(reg), length)
}

func (m *at24c64Mock) WriteReg(reg uint32, data []byte) error {
	return m.Write(append(m.frame(reg), data...))
}

func (m *at24c64Mock) Close() error                { return nil }
func (m *at24c64Mock) Enable()                     {}
func (m *at24c64Mock) Disable()                    {}
func (m *at24c64Mock) IsEnabled() bool             { return true }
func (m *at24c64Mock) IntPin() connection.InputPin { return nil }
func (m *at24c64Mock) EnPin() connection.OutputPin { return nil }

func newEE(t *testing.T) (*at24c64Mock, *AT24C64Full) {
	t.Helper()
	conn := newAT24C64Mock(2)
	ee, err := NewAT24C64Full(conn)
	if err != nil {
		t.Fatal(err)
	}
	return conn, ee
}

func TestAT24C64ByteAccess(t *testing.T) {
	conn, ee := newEE(t)
	if len(conn.writes) != 0 {
		t.Errorf("init generated bus traffic")
	}
	if ee.Size() != 8192 || ee.PageSize() != 32 {
		t.Errorf("Size/PageSize = %d/%d", ee.Size(), ee.PageSize())
	}
	conn.registers[0x1234] = 0x42
	if v, err := ee.ReadByteAt(0x1234); err != nil || v != 0x42 {
		t.Errorf("ReadByteAt = %v, %v", v, err)
	}
	if !bytes.Equal(conn.writes[len(conn.writes)-1], []byte{0x12, 0x34}) {
		t.Errorf("ReadByteAt frame = %v", conn.writes[len(conn.writes)-1])
	}
	conn.writes = nil
	if err := ee.WriteByteAt(0x0010, 0x99); err != nil || conn.registers[0x10] != 0x99 {
		t.Errorf("WriteByteAt failed: %v", err)
	}
	if !bytes.Equal(conn.writes[0], []byte{0x00, 0x10, 0x99}) || !bytes.Equal(conn.writes[1], []byte{0x00, 0x00}) {
		t.Errorf("WriteByteAt frames = %v", conn.writes)
	}
	if _, err := ee.ReadByteAt(0x2000); err != ErrAT24C64Range {
		t.Errorf("ReadByteAt(0x2000) err = %v", err)
	}
	if _, err := ee.ReadByteAt(-1); err != ErrAT24C64Range {
		t.Errorf("ReadByteAt(-1) err = %v", err)
	}
	if err := ee.WriteByteAt(0x2000, 1); err != ErrAT24C64Range {
		t.Errorf("WriteByteAt(0x2000) err = %v", err)
	}
	if v, err := ee.ReadByteAt(0x1FFF); err != nil || v != 0 {
		t.Errorf("ReadByteAt(0x1FFF) = %v, %v", v, err)
	}
}

func TestAT24C64AckPolling(t *testing.T) {
	conn, ee := newEE(t)
	conn.nack = 2
	conn.writes = nil
	if !ee.WaitReady(50 * time.Millisecond) {
		t.Fatal("WaitReady false after two NACKs")
	}
	if len(conn.writes) != 3 {
		t.Errorf("poll count = %d, want 3", len(conn.writes))
	}
	conn.nack = 1 << 30
	if ee.WaitReady(5 * time.Millisecond) {
		t.Error("WaitReady true while NACKing")
	}
	conn.nack = 1 << 30
	conn.nackAfter = 1
	if err := ee.WriteByteAt(0x21, 2); err != ErrAT24C64Timeout {
		t.Errorf("WriteByteAt timeout err = %v", err)
	}
}

func TestAT24C64PageSplitWrite(t *testing.T) {
	conn, ee := newEE(t)
	if ee.MaxChunk() != 30 {
		t.Errorf("MaxChunk default = %d", ee.MaxChunk())
	}
	data := make([]byte, 70)
	for i := range data {
		data[i] = byte(i)
	}
	if err := ee.Write(0x0070, data); err != nil {
		t.Fatal(err)
	}
	var frames [][]byte
	for _, w := range conn.writes {
		if len(w) > 2 {
			frames = append(frames, w)
		}
	}
	if len(frames) != 3 ||
		!bytes.Equal(frames[0], append([]byte{0x00, 0x70}, data[:16]...)) ||
		!bytes.Equal(frames[1], append([]byte{0x00, 0x80}, data[16:46]...)) ||
		!bytes.Equal(frames[2], append([]byte{0x00, 0x9E}, data[46:]...)) {
		t.Errorf("page-split frames wrong: %v", frames)
	}
	if len(conn.writes) != 6 {
		t.Errorf("writes incl. polls = %d, want 6", len(conn.writes))
	}
	got, err := ee.Read(0x0070, 70)
	if err != nil || !bytes.Equal(got, data) {
		t.Errorf("Read round trip failed: %v", err)
	}
	if err := ee.SetMaxChunk(8); err != nil {
		t.Fatal(err)
	}
	conn.writes = nil
	_ = ee.Write(0x0200, make([]byte, 20))
	n := 0
	for _, w := range conn.writes {
		if len(w) > 2 {
			n++
		}
	}
	if n != 3 {
		t.Errorf("custom MaxChunk data frames = %d", n)
	}
	if ee.SetMaxChunk(0) == nil {
		t.Errorf("SetMaxChunk(0) should fail")
	}
}

func TestAT24C64WritePageAndFill(t *testing.T) {
	conn, ee := newEE(t)
	page := make([]byte, 32)
	for i := range page {
		page[i] = byte(i)
	}
	if err := ee.WritePage(0x0300, page); err != nil {
		t.Fatal(err)
	}
	for i := range page {
		if conn.registers[0x0300+uint32(i)] != page[i] {
			t.Fatalf("WritePage byte %d wrong", i)
		}
	}
	if ee.WritePage(0x037C, make([]byte, 8)) != ErrAT24C64PageBoundary {
		t.Errorf("page crossing not rejected")
	}
	if ee.WritePage(0x0400, make([]byte, 33)) != ErrAT24C64PageBoundary {
		t.Errorf("oversize page not rejected")
	}
	if err := ee.Fill(0x0500, 200, 0xAB); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 200; i++ {
		if conn.registers[0x0500+uint32(i)] != 0xAB {
			t.Fatalf("Fill byte %d wrong", i)
		}
	}
	if conn.registers[0x0500+200] != 0 {
		t.Errorf("Fill overran")
	}
}

func TestAT24C64RangeRules(t *testing.T) {
	_, ee := newEE(t)
	if err := ee.Write(0x1FFE, []byte{1, 2}); err != nil {
		t.Errorf("write to end failed: %v", err)
	}
	if ee.Write(0x1FFF, []byte{1, 2}) != ErrAT24C64Range {
		t.Errorf("write wrap not rejected")
	}
	if _, err := ee.Read(0x1FF0, 0x20); err != ErrAT24C64Range {
		t.Errorf("read wrap not rejected")
	}
	if ee.Fill(0x1FFF, 2, 0) != ErrAT24C64Range {
		t.Errorf("fill wrap not rejected")
	}
	if b, err := ee.Read(0, 0); err != nil || len(b) != 0 {
		t.Errorf("zero-length read = %v, %v", b, err)
	}
}
