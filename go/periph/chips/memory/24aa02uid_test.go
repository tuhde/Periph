package memory

import (
	"bytes"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockConnection is an in-memory fake connection.Connection for unit tests —
// no hardware, no bus.
//
// Supports the two access patterns chip drivers in this repo use:
//   - Register-addressed reads (WriteRead([]byte{reg}, n)): backed by a
//     byte-addressable registers map. Preload it with setRegister.
//   - Plain streamed reads (Read(n), no register address): backed by a FIFO
//     queue. Preload responses with queueRead; each Read(n) call pops the
//     next one. Falls back to n zero bytes if the queue is empty.
//
// Every Write call (register writes and plain command writes alike) is
// appended to writes for assertions, and 2+ byte writes are also applied to
// registers so a later WriteRead sees them.
type mockConnection struct {
	registers map[byte]byte
	writes    [][]byte
	readQueue [][]byte
}

func newMockConnection() *mockConnection {
	return &mockConnection{registers: map[byte]byte{}}
}

func (m *mockConnection) setRegister(reg byte, values ...byte) {
	for i, v := range values {
		m.registers[reg+byte(i)] = v
	}
}

func (m *mockConnection) queueRead(data []byte) {
	m.readQueue = append(m.readQueue, data)
}

func (m *mockConnection) Write(data []byte) error {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	if len(data) >= 2 {
		reg := data[0]
		for i := 1; i < len(data); i++ {
			m.registers[reg+byte(i-1)] = data[i]
		}
	}
	return nil
}

func (m *mockConnection) Read(n int) ([]byte, error) {
	if len(m.readQueue) > 0 {
		front := m.readQueue[0]
		m.readQueue = m.readQueue[1:]
		out := make([]byte, n)
		copy(out, front)
		return out, nil
	}
	return make([]byte, n), nil
}

func (m *mockConnection) WriteRead(data []byte, n int) ([]byte, error) {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	reg := data[0]
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		out[i] = m.registers[reg+byte(i)]
	}
	return out, nil
}

func (m *mockConnection) Close() error                { return nil }
func (m *mockConnection) Enable()                     {}
func (m *mockConnection) Disable()                    {}
func (m *mockConnection) IsEnabled() bool             { return true }
func (m *mockConnection) IntPin() connection.InputPin { return nil }
func (m *mockConnection) EnPin() connection.OutputPin { return nil }

func Test24AA02UIDFullAPI(t *testing.T) {
	conn := newMockConnection()

	// UID (0xFC-0xFF), MSB first.
	conn.setRegister(eeprom24aa02UIDBase, 0xAA, 0xBB, 0xCC, 0xDD)

	eeprom, err := NewEEPROM24AA02UIDFull(conn)
	if err != nil {
		t.Fatalf("NewEEPROM24AA02UIDFull: %v", err)
	}

	uid, err := eeprom.ReadUID()
	if err != nil || uid != [4]byte{0xAA, 0xBB, 0xCC, 0xDD} {
		t.Errorf("ReadUID() = %v, %v, want [AA BB CC DD], nil", uid, err)
	}

	conn.setRegister(0x10, 0x42)
	if v, err := eeprom.ReadUserByte(0x10); err != nil || v != 0x42 {
		t.Errorf("ReadUserByte(0x10) = %v, %v, want 0x42, nil", v, err)
	}

	if err := eeprom.WriteUserByte(0x10, 0x99); err != nil {
		t.Fatalf("WriteUserByte: %v", err)
	}
	if conn.registers[0x10] != 0x99 {
		t.Errorf("WriteUserByte: register 0x10 = %v, want 0x99", conn.registers[0x10])
	}
	if last := conn.writes[len(conn.writes)-1]; len(last) != 2 || last[0] != 0x10 || last[1] != 0x99 {
		t.Errorf("WriteUserByte: expected write [0x10 0x99], got %v", last)
	}

	// Sequential read (0x05-0x08).
	conn.setRegister(0x05, 1, 2, 3, 4)
	data, err := eeprom.Read(0x05, 4)
	if err != nil || !bytes.Equal(data, []byte{1, 2, 3, 4}) {
		t.Errorf("Read(0x05, 4) = %v, %v, want [1 2 3 4], nil", data, err)
	}

	if err := eeprom.WritePage(0x08, []byte{10, 20, 30}); err != nil {
		t.Fatalf("WritePage: %v", err)
	}
	if conn.registers[0x08] != 10 || conn.registers[0x09] != 20 || conn.registers[0x0A] != 30 {
		t.Errorf("WritePage: registers wrong: %v", conn.registers)
	}

	// Write() spanning a page boundary: page 0 is 0x00-0x07, page 1 is
	// 0x08-0x0F. Starting at 0x05 with 10 bytes -> [0x05,0x06,0x07] (3
	// bytes, page 0) then [0x08..0x0E] (7 bytes, page 1). WritePage() issues
	// exactly one write per call (no ack-poll traffic in Go), so the two
	// page-chunk writes are the last two writes.
	data10 := make([]byte, 10)
	for i := range data10 {
		data10[i] = byte(100 + i)
	}
	if err := eeprom.Write(0x05, data10); err != nil {
		t.Fatalf("Write: %v", err)
	}
	page1Chunk := conn.writes[len(conn.writes)-1]
	page0Chunk := conn.writes[len(conn.writes)-2]
	if !bytes.Equal(page0Chunk, []byte{0x05, 100, 101, 102}) {
		t.Errorf("Write: page0 chunk = %v, want [5 100 101 102]", page0Chunk)
	}
	if !bytes.Equal(page1Chunk, []byte{0x08, 103, 104, 105, 106, 107, 108, 109}) {
		t.Errorf("Write: page1 chunk = %v, want [8 103 104 105 106 107 108 109]", page1Chunk)
	}
	if conn.registers[0x05] != 100 || conn.registers[0x06] != 101 || conn.registers[0x07] != 102 ||
		conn.registers[0x08] != 103 || conn.registers[0x0E] != 109 {
		t.Errorf("Write: registers wrong: %v", conn.registers)
	}

	conn.setRegister(eeprom24aa02UIDMfrCode, 0x29)
	if v, err := eeprom.ReadManufacturerCode(); err != nil || v != 0x29 {
		t.Errorf("ReadManufacturerCode() = %v, %v, want 0x29, nil", v, err)
	}

	conn.setRegister(eeprom24aa02UIDDevCode, 0x41)
	if v, err := eeprom.ReadDeviceCode(); err != nil || v != 0x41 {
		t.Errorf("ReadDeviceCode() = %v, %v, want 0x41, nil", v, err)
	}
}
