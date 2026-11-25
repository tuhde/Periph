package ioexpander

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// pcaDataWrites returns the register bytes of the data writes (length > 1)
// from index `from` on; read-pointer writes are skipped.
func pcaDataWrites(m *mockConnection, from int) []byte {
	var out []byte
	for _, w := range m.writes[from:] {
		if len(w) > 1 {
			out = append(out, w[0])
		}
	}
	return out
}

func TestPCA6408AFullAPI(t *testing.T) {
	conn := newMockConnection()
	chip, err := NewPCA6408AFull(conn, 0x20)
	if err != nil {
		t.Fatalf("NewPCA6408AFull: %v", err)
	}

	if conn.registers[pca6408aRegOutput] != 0xFF || conn.registers[pca6408aRegPolarity] != 0x00 ||
		conn.registers[pca6408aRegConfig] != 0xFF {
		t.Errorf("init: registers = %v, want OUTPUT=FF POLARITY=00 CONFIG=FF", conn.registers)
	}
	if chip.shadow != 0xFF || chip.direction != 0xFF {
		t.Errorf("init: shadows = %#x/%#x, want 0xFF/0xFF", chip.shadow, chip.direction)
	}
	for _, b := range pcaDataWrites(conn, 0) {
		if b == pca6408aRegInput {
			t.Errorf("init must not write INPUT")
		}
	}

	conn.setRegister(pca6408aRegInput, 0xA5)
	if v, err := chip.ReadPort(0); err != nil || v != 0xA5 {
		t.Errorf("ReadPort = %#x, %v, want 0xA5", v, err)
	}
	if v, _ := chip.ReadPort(1); v != 0xA5 {
		t.Errorf("ReadPort(1) should ignore the port argument, got %#x", v)
	}

	if err := chip.WritePort(0, 0x3C); err != nil {
		t.Fatal(err)
	}
	if conn.registers[pca6408aRegOutput] != 0x3C || chip.shadow != 0x3C {
		t.Errorf("WritePort: OUTPUT=%#x shadow=%#x", conn.registers[pca6408aRegOutput], chip.shadow)
	}

	// P7 is a normal bidirectional pin.
	_ = chip.Pin(7).Mode(PCA6408AOutput)
	if conn.registers[pca6408aRegConfig] != 0x7F {
		t.Errorf("P7 output: CONFIG = %#x, want 0x7F", conn.registers[pca6408aRegConfig])
	}
	_ = chip.Pin(7).Mode(PCA6408AInput)
	if conn.registers[pca6408aRegConfig] != 0xFF {
		t.Errorf("P7 input: CONFIG = %#x, want 0xFF", conn.registers[pca6408aRegConfig])
	}

	// OUTPUT is written before CONFIG when a pin becomes an output.
	_ = chip.WritePort(0, 0x00)
	before := len(conn.writes)
	_ = chip.Pin(1).Mode(PCA6408AOutput)
	if got := pcaDataWrites(conn, before); len(got) != 2 || got[0] != pca6408aRegOutput || got[1] != pca6408aRegConfig {
		t.Errorf("direction change order: %v", got)
	}

	_ = chip.WritePort(0, 0x00)
	conn.setRegister(pca6408aRegInput, 0x04)
	if v, _ := chip.Pin(2).Read(); !v {
		t.Errorf("Pin(2).Read = false, want true")
	}
	_ = chip.Pin(0).Write(true)
	if conn.registers[pca6408aRegOutput] != 0x01 {
		t.Errorf("pin0 high: OUTPUT = %#x", conn.registers[pca6408aRegOutput])
	}
	_ = chip.Pin(2).Write(true)
	if conn.registers[pca6408aRegOutput] != 0x05 {
		t.Errorf("pin2 high keeps pin0: OUTPUT = %#x", conn.registers[pca6408aRegOutput])
	}
	_ = chip.Pin(0).Write(false)
	if conn.registers[pca6408aRegOutput] != 0x04 {
		t.Errorf("pin0 low keeps pin2: OUTPUT = %#x", conn.registers[pca6408aRegOutput])
	}
	_ = chip.Pin(0).Toggle()
	if conn.registers[pca6408aRegOutput] != 0x05 {
		t.Errorf("toggle: OUTPUT = %#x", conn.registers[pca6408aRegOutput])
	}

	// No pull-ups: the request is rejected without a write.
	before = len(conn.writes)
	if err := chip.Pin(3).Mode(PCA6408AInputPullup); err != ErrPCA6408ANoPullup || len(conn.writes) != before {
		t.Errorf("INPUT_PULLUP must be rejected without a write, got err=%v", err)
	}

	_ = chip.ConfigurePolarity(0x0F)
	if conn.registers[pca6408aRegPolarity] != 0x0F {
		t.Errorf("ConfigurePolarity: POLARITY = %#x", conn.registers[pca6408aRegPolarity])
	}

	before = len(conn.writes)
	_ = chip.SetDirection(0xF0)
	if conn.registers[pca6408aRegConfig] != 0xF0 {
		t.Errorf("SetDirection: CONFIG = %#x", conn.registers[pca6408aRegConfig])
	}
	if got := pcaDataWrites(conn, before); len(got) != 2 || got[0] != pca6408aRegOutput || got[1] != pca6408aRegConfig {
		t.Errorf("SetDirection order: %v", got)
	}
	if chip.GetDirection() != 0xF0 {
		t.Errorf("GetDirection = %#x, want 0xF0", chip.GetDirection())
	}

	if v, _ := chip.ReadRegister(pca6408aRegConfig); v != 0xF0 {
		t.Errorf("ReadRegister = %#x, want 0xF0", v)
	}
	_ = chip.WriteRegister(pca6408aRegOutput, 0x81)
	if chip.shadow != 0x81 {
		t.Errorf("WriteRegister(OUTPUT) shadow = %#x", chip.shadow)
	}
	_ = chip.WriteRegister(pca6408aRegConfig, 0x0F)
	if chip.direction != 0x0F {
		t.Errorf("WriteRegister(CONFIG) shadow = %#x", chip.direction)
	}
	if err := chip.WriteRegister(pca6408aRegInput, 0x00); err == nil {
		t.Errorf("WriteRegister(INPUT) should fail")
	}
	if _, err := chip.ReadRegister(4); err == nil {
		t.Errorf("ReadRegister(4) should fail")
	}

	// PollInterrupt: changed mask limited to inputs, cache updated.
	_ = chip.SetDirection(0xF0)
	conn.setRegister(pca6408aRegInput, 0x00)
	_, _ = chip.PollInterrupt()
	conn.setRegister(pca6408aRegInput, 0x35)
	if v, _ := chip.PollInterrupt(); v != 0x30 {
		t.Errorf("PollInterrupt = %#x, want 0x30", v)
	}
	if v, _ := chip.PollInterrupt(); v != 0x00 {
		t.Errorf("PollInterrupt (no change) = %#x, want 0x00", v)
	}

	_ = chip.Reset()
	if conn.registers[pca6408aRegOutput] != 0xFF || conn.registers[pca6408aRegPolarity] != 0x00 ||
		conn.registers[pca6408aRegConfig] != 0xFF {
		t.Errorf("Reset: registers = %v", conn.registers)
	}
	if chip.shadow != 0xFF || chip.direction != 0xFF {
		t.Errorf("Reset: shadows = %#x/%#x", chip.shadow, chip.direction)
	}
}

func TestPCA6408AWatchOutputFails(t *testing.T) {
	chip, err := NewPCA6408AFull(newMockConnection(), 0x20)
	if err != nil {
		t.Fatal(err)
	}
	_ = chip.Pin(0).Mode(PCA6408AOutput)
	if err := chip.Pin(0).Watch(connection.Change, func(PCA6408AFullPin) {}); err == nil {
		t.Errorf("Watch on an output pin should fail")
	}
}

func TestPCA6408AMinimal(t *testing.T) {
	chip, err := NewPCA6408AMinimal(newMockConnection(), 0x20)
	if err != nil {
		t.Fatal(err)
	}
	if v, _ := chip.ReadPort(0); v != 0 {
		t.Errorf("ReadPort = %#x, want 0", v)
	}
}
