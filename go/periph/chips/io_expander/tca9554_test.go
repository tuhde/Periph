package ioexpander

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

func TestTCA9554FullAPI(t *testing.T) {
	conn := newMockConnection()
	chip, err := NewTCA9554Full(conn, 0x20)
	if err != nil {
		t.Fatalf("NewTCA9554Full: %v", err)
	}

	if conn.registers[tca9554RegOutput] != 0xFF || conn.registers[tca9554RegPolarity] != 0x00 ||
		conn.registers[tca9554RegConfig] != 0xFF {
		t.Errorf("init: registers = %v, want OUTPUT=FF POLARITY=00 CONFIG=FF", conn.registers)
	}
	if chip.shadow != 0xFF || chip.direction != 0xFF {
		t.Errorf("init: shadows = %#x/%#x, want 0xFF/0xFF", chip.shadow, chip.direction)
	}
	for _, b := range pcaDataWrites(conn, 0) {
		if b == tca9554RegInput {
			t.Errorf("init must not write INPUT")
		}
	}

	conn.setRegister(tca9554RegInput, 0xA5)
	if v, err := chip.ReadPort(0); err != nil || v != 0xA5 {
		t.Errorf("ReadPort = %#x, %v, want 0xA5", v, err)
	}
	if v, _ := chip.ReadPort(1); v != 0xA5 {
		t.Errorf("ReadPort(1) should ignore the port argument, got %#x", v)
	}

	if err := chip.WritePort(0, 0x3C); err != nil {
		t.Fatal(err)
	}
	if conn.registers[tca9554RegOutput] != 0x3C || chip.shadow != 0x3C {
		t.Errorf("WritePort: OUTPUT=%#x shadow=%#x", conn.registers[tca9554RegOutput], chip.shadow)
	}

	// P7 is a normal bidirectional pin.
	_ = chip.Pin(7).Mode(TCA9554Output)
	if conn.registers[tca9554RegConfig] != 0x7F {
		t.Errorf("P7 output: CONFIG = %#x, want 0x7F", conn.registers[tca9554RegConfig])
	}
	_ = chip.Pin(7).Mode(TCA9554Input)
	if conn.registers[tca9554RegConfig] != 0xFF {
		t.Errorf("P7 input: CONFIG = %#x, want 0xFF", conn.registers[tca9554RegConfig])
	}

	// OUTPUT is written before CONFIG when a pin becomes an output.
	_ = chip.WritePort(0, 0x00)
	before := len(conn.writes)
	_ = chip.Pin(1).Mode(TCA9554Output)
	if got := pcaDataWrites(conn, before); len(got) != 2 || got[0] != tca9554RegOutput || got[1] != tca9554RegConfig {
		t.Errorf("direction change order: %v", got)
	}

	_ = chip.WritePort(0, 0x00)
	conn.setRegister(tca9554RegInput, 0x04)
	if v, _ := chip.Pin(2).Read(); !v {
		t.Errorf("Pin(2).Read = false, want true")
	}
	_ = chip.Pin(0).Write(true)
	if conn.registers[tca9554RegOutput] != 0x01 {
		t.Errorf("pin0 high: OUTPUT = %#x", conn.registers[tca9554RegOutput])
	}
	_ = chip.Pin(2).Write(true)
	if conn.registers[tca9554RegOutput] != 0x05 {
		t.Errorf("pin2 high keeps pin0: OUTPUT = %#x", conn.registers[tca9554RegOutput])
	}
	_ = chip.Pin(0).Write(false)
	if conn.registers[tca9554RegOutput] != 0x04 {
		t.Errorf("pin0 low keeps pin2: OUTPUT = %#x", conn.registers[tca9554RegOutput])
	}
	_ = chip.Pin(0).Toggle()
	if conn.registers[tca9554RegOutput] != 0x05 {
		t.Errorf("toggle: OUTPUT = %#x", conn.registers[tca9554RegOutput])
	}

	// Fixed pull-up: the request is accepted as a plain input.
	if err := chip.Pin(3).Mode(TCA9554InputPullup); err != nil {
		t.Errorf("INPUT_PULLUP must be accepted, got err=%v", err)
	}

	_ = chip.ConfigurePolarity(0x0F)
	if conn.registers[tca9554RegPolarity] != 0x0F {
		t.Errorf("ConfigurePolarity: POLARITY = %#x", conn.registers[tca9554RegPolarity])
	}

	before = len(conn.writes)
	_ = chip.SetDirection(0xF0)
	if conn.registers[tca9554RegConfig] != 0xF0 {
		t.Errorf("SetDirection: CONFIG = %#x", conn.registers[tca9554RegConfig])
	}
	if got := pcaDataWrites(conn, before); len(got) != 2 || got[0] != tca9554RegOutput || got[1] != tca9554RegConfig {
		t.Errorf("SetDirection order: %v", got)
	}
	if chip.GetDirection() != 0xF0 {
		t.Errorf("GetDirection = %#x, want 0xF0", chip.GetDirection())
	}

	if v, _ := chip.ReadRegister(tca9554RegConfig); v != 0xF0 {
		t.Errorf("ReadRegister = %#x, want 0xF0", v)
	}
	_ = chip.WriteRegister(tca9554RegOutput, 0x81)
	if chip.shadow != 0x81 {
		t.Errorf("WriteRegister(OUTPUT) shadow = %#x", chip.shadow)
	}
	_ = chip.WriteRegister(tca9554RegConfig, 0x0F)
	if chip.direction != 0x0F {
		t.Errorf("WriteRegister(CONFIG) shadow = %#x", chip.direction)
	}
	if err := chip.WriteRegister(tca9554RegInput, 0x00); err == nil {
		t.Errorf("WriteRegister(INPUT) should fail")
	}
	if _, err := chip.ReadRegister(4); err == nil {
		t.Errorf("ReadRegister(4) should fail")
	}

	// PollInterrupt: changed mask limited to inputs, cache updated.
	_ = chip.SetDirection(0xF0)
	conn.setRegister(tca9554RegInput, 0x00)
	_, _ = chip.PollInterrupt()
	conn.setRegister(tca9554RegInput, 0x35)
	if v, _ := chip.PollInterrupt(); v != 0x30 {
		t.Errorf("PollInterrupt = %#x, want 0x30", v)
	}
	if v, _ := chip.PollInterrupt(); v != 0x00 {
		t.Errorf("PollInterrupt (no change) = %#x, want 0x00", v)
	}

	_ = chip.Reset()
	if conn.registers[tca9554RegOutput] != 0xFF || conn.registers[tca9554RegPolarity] != 0x00 ||
		conn.registers[tca9554RegConfig] != 0xFF {
		t.Errorf("Reset: registers = %v", conn.registers)
	}
	if chip.shadow != 0xFF || chip.direction != 0xFF {
		t.Errorf("Reset: shadows = %#x/%#x", chip.shadow, chip.direction)
	}
}

func TestTCA9554WatchOutputFails(t *testing.T) {
	chip, err := NewTCA9554Full(newMockConnection(), 0x20)
	if err != nil {
		t.Fatal(err)
	}
	_ = chip.Pin(0).Mode(TCA9554Output)
	if err := chip.Pin(0).Watch(connection.Change, func(TCA9554FullPin) {}); err == nil {
		t.Errorf("Watch on an output pin should fail")
	}
}

func TestTCA9554Minimal(t *testing.T) {
	chip, err := NewTCA9554Minimal(newMockConnection(), 0x20)
	if err != nil {
		t.Fatal(err)
	}
	if v, _ := chip.ReadPort(0); v != 0 {
		t.Errorf("ReadPort = %#x, want 0", v)
	}
}
