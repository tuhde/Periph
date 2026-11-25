package memory

import (
	"bytes"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mb85rcMock is an in-memory fake RegisterConnection with a configurable
// register address width (big-endian), recording every write/read frame.
type mb85rcMock struct {
	regBytes  int
	registers map[uint32]byte
	writes    [][]byte
}

func newMB85RCMock(regBytes int) *mb85rcMock {
	return &mb85rcMock{regBytes: regBytes, registers: map[uint32]byte{}}
}

func (m *mb85rcMock) addr(b []byte) uint32 {
	var a uint32
	for i := 0; i < m.regBytes; i++ {
		a = a<<8 | uint32(b[i])
	}
	return a
}

func (m *mb85rcMock) frame(reg uint32) []byte {
	f := make([]byte, m.regBytes)
	for i := 0; i < m.regBytes; i++ {
		f[i] = byte(reg >> (8 * (m.regBytes - 1 - i)))
	}
	return f
}

func (m *mb85rcMock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	if len(data) > m.regBytes {
		a := m.addr(data)
		for i, v := range data[m.regBytes:] {
			m.registers[a+uint32(i)] = v
		}
	}
	return nil
}

func (m *mb85rcMock) Read(n int) ([]byte, error) { return make([]byte, n), nil }

func (m *mb85rcMock) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	a := m.addr(data)
	out := make([]byte, n)
	for i := range out {
		out[i] = m.registers[a+uint32(i)]
	}
	return out, nil
}

func (m *mb85rcMock) ReadReg(reg uint32, length int) ([]byte, error) {
	return m.WriteRead(m.frame(reg), length)
}

func (m *mb85rcMock) WriteReg(reg uint32, data []byte) error {
	return m.Write(append(m.frame(reg), data...))
}

func (m *mb85rcMock) Close() error                { return nil }
func (m *mb85rcMock) Enable()                     {}
func (m *mb85rcMock) Disable()                    {}
func (m *mb85rcMock) IsEnabled() bool             { return true }
func (m *mb85rcMock) IntPin() connection.InputPin { return nil }
func (m *mb85rcMock) EnPin() connection.OutputPin { return nil }

func TestMB85RCFullAPI(t *testing.T) {
	conn := newMB85RCMock(2)
	fram, err := NewMB85RCFull(conn)
	if err != nil {
		t.Fatal(err)
	}
	if len(conn.writes) != 0 {
		t.Errorf("init generated bus traffic")
	}
	if fram.Size() != 32768 {
		t.Errorf("Size = %d", fram.Size())
	}

	conn.registers[0x1234] = 0x42
	if v, err := fram.ReadUserByte(0x1234); err != nil || v != 0x42 {
		t.Errorf("ReadUserByte = %v, %v", v, err)
	}
	if !bytes.Equal(conn.writes[len(conn.writes)-1], []byte{0x12, 0x34}) {
		t.Errorf("ReadUserByte frame = %v", conn.writes[len(conn.writes)-1])
	}
	if err := fram.WriteUserByte(0x0010, 0x99); err != nil || conn.registers[0x10] != 0x99 {
		t.Errorf("WriteUserByte failed: %v", err)
	}
	if !bytes.Equal(conn.writes[len(conn.writes)-1], []byte{0x00, 0x10, 0x99}) {
		t.Errorf("WriteUserByte frame = %v", conn.writes[len(conn.writes)-1])
	}
	if _, err := fram.ReadUserByte(0x8000); err != ErrMB85RCRange {
		t.Errorf("ReadUserByte(0x8000) err = %v", err)
	}
	if _, err := fram.ReadUserByte(-1); err != ErrMB85RCRange {
		t.Errorf("ReadUserByte(-1) err = %v", err)
	}
	if err := fram.WriteUserByte(0x8000, 1); err != ErrMB85RCRange {
		t.Errorf("WriteUserByte(0x8000) err = %v", err)
	}

	if fram.MaxChunk() != 30 {
		t.Errorf("MaxChunk default = %d", fram.MaxChunk())
	}
	data := make([]byte, 70)
	for i := range data {
		data[i] = byte(i)
	}
	conn.writes = nil
	if err := fram.Write(0x0100, data); err != nil {
		t.Fatal(err)
	}
	if len(conn.writes) != 3 ||
		!bytes.Equal(conn.writes[0], append([]byte{0x01, 0x00}, data[:30]...)) ||
		!bytes.Equal(conn.writes[1], append([]byte{0x01, 30}, data[30:60]...)) ||
		!bytes.Equal(conn.writes[2], append([]byte{0x01, 60}, data[60:]...)) {
		t.Errorf("Write chunking wrong: %v", conn.writes)
	}
	conn.writes = nil
	got, err := fram.Read(0x0100, 70)
	if err != nil || !bytes.Equal(got, data) || len(conn.writes) != 3 {
		t.Errorf("Read round trip failed: %v, %d frames", err, len(conn.writes))
	}

	if err := fram.SetMaxChunk(8); err != nil {
		t.Fatal(err)
	}
	conn.writes = nil
	_ = fram.Write(0x0200, make([]byte, 20))
	if len(conn.writes) != 3 {
		t.Errorf("custom MaxChunk frames = %d", len(conn.writes))
	}
	if fram.SetMaxChunk(0) == nil {
		t.Errorf("SetMaxChunk(0) should fail")
	}
	_ = fram.SetMaxChunk(30)

	if err := fram.Fill(0x0300, 65, 0xAB); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 65; i++ {
		if conn.registers[0x0300+uint32(i)] != 0xAB {
			t.Fatalf("Fill byte %d wrong", i)
		}
	}
	if conn.registers[0x0300+65] != 0 {
		t.Errorf("Fill overran")
	}

	if err := fram.Write(0x7FFE, []byte{1, 2}); err != nil {
		t.Errorf("write to end failed: %v", err)
	}
	if fram.Write(0x7FFF, []byte{1, 2}) != ErrMB85RCRange {
		t.Errorf("write wrap not rejected")
	}
	if _, err := fram.Read(0x7FF0, 0x20); err != ErrMB85RCRange {
		t.Errorf("read wrap not rejected")
	}
	if fram.Fill(0x7FFF, 2, 0) != ErrMB85RCRange {
		t.Errorf("fill wrap not rejected")
	}
	if b, err := fram.Read(0, 0); err != nil || len(b) != 0 {
		t.Errorf("zero-length read = %v, %v", b, err)
	}

	idc := newMB85RCMock(1)
	idc.registers[0xA6] = 0x00
	idc.registers[0xA7] = 0xA5
	idc.registers[0xA8] = 0x10
	m, d, p, err := fram.ReadDeviceID(idc, 0x53)
	if err != nil || m != 0x00A || d != 0x5 || p != 0x10 {
		t.Errorf("ReadDeviceID = %x %x %x %v", m, d, p, err)
	}
	if !bytes.Equal(idc.writes[len(idc.writes)-1], []byte{0xA6}) {
		t.Errorf("device ID word = %v", idc.writes[len(idc.writes)-1])
	}
}
