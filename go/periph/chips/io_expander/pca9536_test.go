package ioexpander

import (
	"sync"
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

func TestPCA9536FullAPI(t *testing.T) {
	conn := newMockConnection()
	chip, err := NewPCA9536Full(conn, 0x41)
	if err != nil {
		t.Fatalf("NewPCA9536Full: %v", err)
	}

	if conn.registers[pca9536RegOutput] != 0xFF || conn.registers[pca9536RegPolarity] != 0x00 ||
		conn.registers[pca9536RegConfig] != 0xFF {
		t.Errorf("init: registers = %v, want OUTPUT=FF POLARITY=00 CONFIG=FF", conn.registers)
	}
	if chip.shadow != 0xFF || chip.direction != 0xFF {
		t.Errorf("init: shadows = %#x/%#x, want 0xFF/0xFF", chip.shadow, chip.direction)
	}
	for _, b := range pcaDataWrites(conn, 0) {
		if b == pca9536RegInput {
			t.Errorf("init must not write INPUT")
		}
	}

	conn.setRegister(pca9536RegInput, 0xF5)
	if v, err := chip.ReadPort(0); err != nil || v != 0x05 {
		t.Errorf("ReadPort = %#x, %v, want 0x05 (bits 7:4 masked)", v, err)
	}
	if v, _ := chip.ReadPort(1); v != 0x05 {
		t.Errorf("ReadPort(1) should ignore the port argument, got %#x", v)
	}

	if err := chip.WritePort(0, 0x0C); err != nil {
		t.Fatal(err)
	}
	if conn.registers[pca9536RegOutput] != 0xFC || chip.shadow != 0xFC {
		t.Errorf("WritePort: OUTPUT=%#x shadow=%#x", conn.registers[pca9536RegOutput], chip.shadow)
	}

	// P3 is a normal bidirectional pin.
	_ = chip.Pin(3).Mode(PCA9536Output)
	if conn.registers[pca9536RegConfig] != 0xF7 {
		t.Errorf("P3 output: CONFIG = %#x, want 0xF7", conn.registers[pca9536RegConfig])
	}
	_ = chip.Pin(3).Mode(PCA9536Input)
	if conn.registers[pca9536RegConfig] != 0xFF {
		t.Errorf("P3 input: CONFIG = %#x, want 0xFF", conn.registers[pca9536RegConfig])
	}

	// OUTPUT is written before CONFIG when a pin becomes an output.
	_ = chip.WritePort(0, 0x00)
	before := len(conn.writes)
	_ = chip.Pin(1).Mode(PCA9536Output)
	if got := pcaDataWrites(conn, before); len(got) != 2 || got[0] != pca9536RegOutput || got[1] != pca9536RegConfig {
		t.Errorf("direction change order: %v", got)
	}

	_ = chip.WritePort(0, 0x00)
	conn.setRegister(pca9536RegInput, 0x04)
	if v, _ := chip.Pin(2).Read(); !v {
		t.Errorf("Pin(2).Read = false, want true")
	}
	_ = chip.Pin(0).Write(true)
	if conn.registers[pca9536RegOutput] != 0xF1 {
		t.Errorf("pin0 high: OUTPUT = %#x", conn.registers[pca9536RegOutput])
	}
	_ = chip.Pin(2).Write(true)
	if conn.registers[pca9536RegOutput] != 0xF5 {
		t.Errorf("pin2 high keeps pin0: OUTPUT = %#x", conn.registers[pca9536RegOutput])
	}
	_ = chip.Pin(0).Write(false)
	if conn.registers[pca9536RegOutput] != 0xF4 {
		t.Errorf("pin0 low keeps pin2: OUTPUT = %#x", conn.registers[pca9536RegOutput])
	}
	_ = chip.Pin(0).Toggle()
	if conn.registers[pca9536RegOutput] != 0xF5 {
		t.Errorf("toggle: OUTPUT = %#x", conn.registers[pca9536RegOutput])
	}

	// The weak pull-up is fixed: the request is rejected without a write.
	before = len(conn.writes)
	if err := chip.Pin(3).Mode(PCA9536InputPullup); err != ErrPCA9536NoPullup || len(conn.writes) != before {
		t.Errorf("INPUT_PULLUP must be rejected without a write, got err=%v", err)
	}

	_ = chip.ConfigurePolarity(0x0F)
	if conn.registers[pca9536RegPolarity] != 0x0F {
		t.Errorf("ConfigurePolarity: POLARITY = %#x", conn.registers[pca9536RegPolarity])
	}

	before = len(conn.writes)
	_ = chip.SetDirection(0x0C)
	if conn.registers[pca9536RegConfig] != 0xFC {
		t.Errorf("SetDirection: CONFIG = %#x", conn.registers[pca9536RegConfig])
	}
	if got := pcaDataWrites(conn, before); len(got) != 2 || got[0] != pca9536RegOutput || got[1] != pca9536RegConfig {
		t.Errorf("SetDirection order: %v", got)
	}
	if chip.GetDirection() != 0x0C {
		t.Errorf("GetDirection = %#x, want 0x0C", chip.GetDirection())
	}

	if v, _ := chip.ReadRegister(pca9536RegConfig); v != 0xFC {
		t.Errorf("ReadRegister = %#x, want 0xFC", v)
	}
	_ = chip.WriteRegister(pca9536RegOutput, 0xF1)
	if chip.shadow != 0xF1 {
		t.Errorf("WriteRegister(OUTPUT) shadow = %#x", chip.shadow)
	}
	_ = chip.WriteRegister(pca9536RegConfig, 0x0F)
	if chip.direction != 0x0F {
		t.Errorf("WriteRegister(CONFIG) shadow = %#x", chip.direction)
	}
	if err := chip.WriteRegister(pca9536RegInput, 0x00); err == nil {
		t.Errorf("WriteRegister(INPUT) should fail")
	}
	if _, err := chip.ReadRegister(4); err == nil {
		t.Errorf("ReadRegister(4) should fail")
	}

	// PollChanges: changed mask limited to inputs, cache updated.
	_ = chip.SetDirection(0x0C)
	conn.setRegister(pca9536RegInput, 0x00)
	_, _ = chip.PollChanges()
	conn.setRegister(pca9536RegInput, 0x0D)
	if v, _ := chip.PollChanges(); v != 0x0C {
		t.Errorf("PollChanges = %#x, want 0x0C", v)
	}
	if v, _ := chip.PollChanges(); v != 0x00 {
		t.Errorf("PollChanges (no change) = %#x, want 0x00", v)
	}

	// A polarity change refreshes the cache and is not reported.
	conn.setRegister(pca9536RegInput, 0x00)
	_ = chip.ConfigurePolarity(0x0C)
	if v, _ := chip.PollChanges(); v != 0x00 {
		t.Errorf("polarity change must not be reported, got %#x", v)
	}
	_ = chip.ConfigurePolarity(0x00)
	_, _ = chip.PollChanges()

	_ = chip.Reset()
	if conn.registers[pca9536RegOutput] != 0xFF || conn.registers[pca9536RegPolarity] != 0x00 ||
		conn.registers[pca9536RegConfig] != 0xFF {
		t.Errorf("Reset: registers = %v", conn.registers)
	}
	if chip.shadow != 0xFF || chip.direction != 0xFF {
		t.Errorf("Reset: shadows = %#x/%#x", chip.shadow, chip.direction)
	}
}

func TestPCA9536WatchOutputFails(t *testing.T) {
	chip, err := NewPCA9536Full(newMockConnection(), 0x41)
	if err != nil {
		t.Fatal(err)
	}
	_ = chip.Pin(0).Mode(PCA9536Output)
	if err := chip.Pin(0).Watch(connection.Change, func(PCA9536FullPin) {}); err == nil {
		t.Errorf("Watch on an output pin should fail")
	}
}

func TestPCA9536Minimal(t *testing.T) {
	chip, err := NewPCA9536Minimal(newMockConnection(), 0x41)
	if err != nil {
		t.Fatal(err)
	}
	if v, _ := chip.ReadPort(0); v != 0 {
		t.Errorf("ReadPort = %#x, want 0", v)
	}
}

// lockedConn serialises access to the mock so a polling goroutine and the test
// goroutine can use it concurrently.
type lockedConn struct {
	*mockConnection
	mu sync.Mutex
}

func (l *lockedConn) ReadReg(reg uint32, n int) ([]byte, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.mockConnection.ReadReg(reg, n)
}

func (l *lockedConn) WriteReg(reg uint32, data []byte) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.mockConnection.WriteReg(reg, data)
}

func (l *lockedConn) setRegister(reg uint8, v uint8) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.mockConnection.setRegister(reg, v)
}

func TestPCA9536WatchChangesAndPinWatch(t *testing.T) {
	conn := &lockedConn{mockConnection: newMockConnection()}
	chip, err := NewPCA9536Full(conn, 0x41)
	if err != nil {
		t.Fatal(err)
	}
	changes := make(chan uint8, 4)
	_ = chip.WatchChanges(func(c uint8) { changes <- c }, 2*time.Millisecond)
	conn.setRegister(pca9536RegInput, 0x04)
	select {
	case c := <-changes:
		if c != 0x04 {
			t.Errorf("WatchChanges mask = %#x, want 0x04", c)
		}
	case <-time.After(time.Second):
		t.Fatal("WatchChanges callback not invoked")
	}
	chip.UnwatchChanges()

	hits := make(chan uint8, 4)
	if err := chip.Pin(3).Watch(connection.Rising, func(p PCA9536FullPin) { hits <- p.n }); err != nil {
		t.Fatal(err)
	}
	conn.setRegister(pca9536RegInput, 0x0C)
	select {
	case n := <-hits:
		if n != 3 {
			t.Errorf("pin watch fired for P%d, want P3", n)
		}
	case <-time.After(time.Second):
		t.Fatal("pin watch not invoked")
	}
	chip.UnwatchChanges()
}
