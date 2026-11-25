package ioexpander

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

func TestPCA9539FullAPI(t *testing.T) {
	conn := newMockConnection()
	chip, err := NewPCA9539Full(conn, 0x74)
	if err != nil {
		t.Fatalf("NewPCA9539Full: %v", err)
	}
	reg := func(r uint8) byte { return conn.registers[r] }

	// Init writes the reset state of both ports as two-byte pair writes.
	if reg(pca9539RegOutput) != 0xFF || reg(pca9539RegOutput+1) != 0xFF ||
		reg(pca9539RegPolarity) != 0x00 || reg(pca9539RegPolarity+1) != 0x00 ||
		reg(pca9539RegConfig) != 0xFF || reg(pca9539RegConfig+1) != 0xFF {
		t.Errorf("init: registers = %v, want OUTPUT=FFFF POLARITY=0000 CONFIG=FFFF", conn.registers)
	}
	if chip.shadow != 0xFFFF || chip.direction != 0xFFFF {
		t.Errorf("init: shadows = %#x/%#x, want 0xFFFF/0xFFFF", chip.shadow, chip.direction)
	}
	if got := pcaDataWrites(conn, 0); len(got) != 3 || got[0] != pca9539RegOutput ||
		got[1] != pca9539RegPolarity || got[2] != pca9539RegConfig {
		t.Errorf("init must be three pair writes, got %v", got)
	}

	conn.setRegister(pca9539RegInput, 0xA5, 0x5A)
	if v, err := chip.ReadPort(0); err != nil || v != 0xA5 {
		t.Errorf("ReadPort(0) = %#x, %v, want 0xA5", v, err)
	}
	if v, _ := chip.ReadPort(1); v != 0x5A {
		t.Errorf("ReadPort(1) = %#x, want 0x5A", v)
	}
	if v, _ := chip.ReadAll(); v != 0x5AA5 {
		t.Errorf("ReadAll = %#x, want 0x5AA5", v)
	}

	_ = chip.WritePort(0, 0x3C)
	_ = chip.WritePort(1, 0xC3)
	if reg(pca9539RegOutput) != 0x3C || reg(pca9539RegOutput+1) != 0xC3 || chip.shadow != 0xC33C {
		t.Errorf("WritePort: OUTPUT=%#x/%#x shadow=%#x", reg(pca9539RegOutput), reg(pca9539RegOutput+1), chip.shadow)
	}

	before := len(conn.writes)
	_ = chip.WriteAll(0x1234)
	if reg(pca9539RegOutput) != 0x34 || reg(pca9539RegOutput+1) != 0x12 || chip.shadow != 0x1234 {
		t.Errorf("WriteAll: OUTPUT=%#x/%#x shadow=%#x", reg(pca9539RegOutput), reg(pca9539RegOutput+1), chip.shadow)
	}
	if got := pcaDataWrites(conn, before); len(got) != 1 {
		t.Errorf("WriteAll must be a single two-byte transfer, got %v", got)
	}

	// Direction: output clears the CONFIG bit of its port.
	_ = chip.WriteAll(0xFFFF)
	_ = chip.Pin(7).Mode(PCA9539Output)
	if reg(pca9539RegConfig) != 0x7F || reg(pca9539RegConfig+1) != 0xFF {
		t.Errorf("P07 output: CONFIG = %#x/%#x", reg(pca9539RegConfig), reg(pca9539RegConfig+1))
	}
	_ = chip.Pin(7).Mode(PCA9539Input)
	_ = chip.Pin(12).Mode(PCA9539Output)
	if reg(pca9539RegConfig+1) != 0xEF || reg(pca9539RegConfig) != 0xFF {
		t.Errorf("P14 output: CONFIG = %#x/%#x", reg(pca9539RegConfig), reg(pca9539RegConfig+1))
	}
	_ = chip.Pin(12).Mode(PCA9539Input)

	// OUTPUT is written before CONFIG when a pin becomes an output.
	before = len(conn.writes)
	_ = chip.Pin(9).Mode(PCA9539Output)
	if got := pcaDataWrites(conn, before); len(got) != 2 || got[0] != pca9539RegOutput+1 || got[1] != pca9539RegConfig+1 {
		t.Errorf("direction change order: %v", got)
	}
	_ = chip.Pin(9).Mode(PCA9539Input)

	_ = chip.WriteAll(0x0000)
	conn.setRegister(pca9539RegInput, 0x04, 0x80)
	if v, _ := chip.Pin(2).Read(); !v {
		t.Errorf("Pin(2).Read = false, want true")
	}
	if v, _ := chip.Pin(15).Read(); !v {
		t.Errorf("Pin(15).Read = false, want true")
	}
	if v, _ := chip.Pin(3).Read(); v {
		t.Errorf("Pin(3).Read = true, want false")
	}
	_ = chip.Pin(0).Write(true)
	_ = chip.Pin(2).Write(true)
	_ = chip.Pin(0).Write(false)
	if reg(pca9539RegOutput) != 0x04 {
		t.Errorf("pin writes keep neighbours: OUTPUT0 = %#x", reg(pca9539RegOutput))
	}
	_ = chip.Pin(8).Write(true)
	if reg(pca9539RegOutput+1) != 0x01 || reg(pca9539RegOutput) != 0x04 {
		t.Errorf("pin 8 high: OUTPUT = %#x/%#x", reg(pca9539RegOutput), reg(pca9539RegOutput+1))
	}
	_ = chip.Pin(8).Toggle()
	if reg(pca9539RegOutput+1) != 0x00 {
		t.Errorf("toggle: OUTPUT1 = %#x", reg(pca9539RegOutput+1))
	}

	// No pull-ups: the request is rejected without a write.
	before = len(conn.writes)
	if err := chip.Pin(3).Mode(PCA9539InputPullup); err != ErrPCA9539NoPullup || len(conn.writes) != before {
		t.Errorf("INPUT_PULLUP must be rejected without a write, got err=%v", err)
	}

	before = len(conn.writes)
	_ = chip.ConfigurePolarity(0xF00F)
	if reg(pca9539RegPolarity) != 0x0F || reg(pca9539RegPolarity+1) != 0xF0 {
		t.Errorf("ConfigurePolarity: POLARITY = %#x/%#x", reg(pca9539RegPolarity), reg(pca9539RegPolarity+1))
	}
	if got := pcaDataWrites(conn, before); len(got) != 1 {
		t.Errorf("ConfigurePolarity must be a single two-byte transfer, got %v", got)
	}

	before = len(conn.writes)
	_ = chip.SetDirection(0xFF00)
	if reg(pca9539RegConfig) != 0x00 || reg(pca9539RegConfig+1) != 0xFF {
		t.Errorf("SetDirection: CONFIG = %#x/%#x", reg(pca9539RegConfig), reg(pca9539RegConfig+1))
	}
	if got := pcaDataWrites(conn, before); len(got) != 2 || got[0] != pca9539RegOutput || got[1] != pca9539RegConfig {
		t.Errorf("SetDirection order: %v", got)
	}
	if chip.GetDirection() != 0xFF00 {
		t.Errorf("GetDirection = %#x, want 0xFF00", chip.GetDirection())
	}

	if v, _ := chip.ReadRegister(pca9539RegConfig + 1); v != 0xFF {
		t.Errorf("ReadRegister = %#x, want 0xFF", v)
	}
	_ = chip.WriteRegister(pca9539RegOutput+1, 0x81)
	if chip.shadow>>8 != 0x81 {
		t.Errorf("WriteRegister(OUTPUT1) shadow = %#x", chip.shadow)
	}
	_ = chip.WriteRegister(pca9539RegConfig, 0x0F)
	if chip.direction != 0xFF0F {
		t.Errorf("WriteRegister(CONFIG0) shadow = %#x", chip.direction)
	}
	_ = chip.WriteRegister(pca9539RegPolarity+1, 0x3C)
	if chip.polarity != 0x3C0F {
		t.Errorf("WriteRegister(POLARITY1) shadow = %#x", chip.polarity)
	}
	for _, bad := range []uint8{0, 1, 8} {
		if err := chip.WriteRegister(bad, 0x00); err == nil {
			t.Errorf("WriteRegister(%d) should fail", bad)
		}
	}
	if _, err := chip.ReadRegister(8); err == nil {
		t.Errorf("ReadRegister(8) should fail")
	}

	// PollInterrupt: 16-bit changed mask limited to inputs, cache updated.
	_ = chip.SetDirection(0xF0F0)
	conn.setRegister(pca9539RegInput, 0x00, 0x00)
	_, _ = chip.PollInterrupt()
	conn.setRegister(pca9539RegInput, 0x35, 0x9A)
	if v, _ := chip.PollInterrupt(); v != 0x9030 {
		t.Errorf("PollInterrupt = %#x, want 0x9030", v)
	}
	if v, _ := chip.PollInterrupt(); v != 0x0000 {
		t.Errorf("PollInterrupt (no change) = %#x, want 0", v)
	}

	// Interrupt errata (datasheet 8.2.4.1): with a subscription active PollInterrupt
	// rewrites OUTPUT0 after the INPUT read; without one it does not.
	before = len(conn.writes)
	_, _ = chip.PollInterrupt()
	hasDataWrite := func(from int) bool {
		for _, w := range conn.writes[from:] {
			if len(w) > 1 {
				return true
			}
		}
		return false
	}
	if hasDataWrite(before) {
		t.Errorf("PollInterrupt without subscription must not write")
	}
	chip.callback = func(uint16) {}
	before = len(conn.writes)
	_, _ = chip.PollInterrupt()
	if !hasDataWrite(before) {
		t.Errorf("PollInterrupt with subscription must rewrite OUTPUT0")
	}
	chip.callback = nil

	_ = chip.Reset()
	if reg(pca9539RegOutput) != 0xFF || reg(pca9539RegOutput+1) != 0xFF ||
		reg(pca9539RegPolarity) != 0x00 || reg(pca9539RegPolarity+1) != 0x00 ||
		reg(pca9539RegConfig) != 0xFF || reg(pca9539RegConfig+1) != 0xFF {
		t.Errorf("Reset: registers = %v", conn.registers)
	}
	if chip.shadow != 0xFFFF || chip.direction != 0xFFFF {
		t.Errorf("Reset: shadows = %#x/%#x", chip.shadow, chip.direction)
	}
}

func TestPCA9539WatchOutputFails(t *testing.T) {
	chip, err := NewPCA9539Full(newMockConnection(), 0x74)
	if err != nil {
		t.Fatal(err)
	}
	_ = chip.Pin(10).Mode(PCA9539Output)
	if err := chip.Pin(10).Watch(connection.Change, func(PCA9539FullPin) {}); err == nil {
		t.Errorf("Watch on an output pin should fail")
	}
}

func TestPCA9539Minimal(t *testing.T) {
	chip, err := NewPCA9539Minimal(newMockConnection(), 0x74)
	if err != nil {
		t.Fatal(err)
	}
	if v, _ := chip.ReadPort(0); v != 0 {
		t.Errorf("ReadPort = %#x, want 0", v)
	}
}
