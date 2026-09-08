package ioexpander

import "testing"

// TestPCF8575FullAPI uses the mockConnection defined in mcp23017_test.go
// (shared per Go package convention). PCF8575 has no sub-registers: every
// transaction is a plain 2-byte Read()/Write() (Port 0 first, Port 1
// second; no register pointer), so mockConnection's register map is never
// consulted — reads must be preloaded via queueRead() in the exact order
// the driver will issue them.
func TestPCF8575FullAPI(t *testing.T) {
	conn := newMockConnection()
	// NewPCF8575Full's constructor issues one extra 2-byte read to seed prev.
	conn.queueRead([]byte{0xFF, 0xFF})
	chip, err := NewPCF8575Full(conn, 0x20)
	if err != nil {
		t.Fatalf("NewPCF8575Full: %v", err)
	}

	if len(conn.writes) == 0 || len(conn.writes[0]) != 2 || conn.writes[0][0] != 0xFF || conn.writes[0][1] != 0xFF {
		t.Fatalf("init: expected first write [0xFF 0xFF], got %v", conn.writes)
	}
	if chip.shadow[0] != 0xFF || chip.shadow[1] != 0xFF {
		t.Errorf("init: shadow = %v, want [0xFF 0xFF]", chip.shadow)
	}

	// ReadPort(0)/(1): both derived from one 2-byte read.
	conn.queueRead([]byte{0x5A, 0xA5})
	if v, err := chip.ReadPort(0); err != nil || v != 0x5A {
		t.Errorf("ReadPort(0) = %#x, %v, want 0x5A, nil", v, err)
	}
	conn.queueRead([]byte{0x5A, 0xA5})
	if v, err := chip.ReadPort(1); err != nil || v != 0xA5 {
		t.Errorf("ReadPort(1) = %#x, %v, want 0xA5, nil", v, err)
	}

	// WritePort(): writes both shadow bytes, preserving the untouched port.
	if err := chip.WritePort(0, 0x3C); err != nil {
		t.Fatalf("WritePort(0, 0x3C): %v", err)
	}
	last := conn.writes[len(conn.writes)-1]
	if last[0] != 0x3C || last[1] != 0xFF {
		t.Errorf("WritePort(0, 0x3C): write=%v, want [0x3C 0xFF]", last)
	}
	if err := chip.WritePort(1, 0x0F); err != nil {
		t.Fatalf("WritePort(1, 0x0F): %v", err)
	}
	last = conn.writes[len(conn.writes)-1]
	if last[0] != 0x3C || last[1] != 0x0F {
		t.Errorf("WritePort(1, 0x0F): write=%v, want [0x3C 0x0F]", last)
	}

	// Pin.Get() on Port 0 and Port 1.
	pin3 := chip.Pin(3) // Port 0, bit 3
	conn.queueRead([]byte{0x08, 0x00})
	if high, err := pin3.Get(); err != nil || !high {
		t.Errorf("pin3.Get() = %v, %v, want true, nil", high, err)
	}
	pin11 := chip.Pin(11) // Port 1, bit 3
	conn.queueRead([]byte{0x00, 0x08})
	if high, err := pin11.Get(); err != nil || !high {
		t.Errorf("pin11.Get() = %v, %v, want true, nil", high, err)
	}

	// Pin.Set preserves other shadow bits within the same port.
	if err := chip.WritePort(0, 0xFF); err != nil {
		t.Fatalf("WritePort(0, 0xFF) reset: %v", err)
	}
	if err := chip.WritePort(1, 0xFF); err != nil {
		t.Fatalf("WritePort(1, 0xFF) reset: %v", err)
	}
	if err := pin3.Set(false); err != nil {
		t.Fatalf("pin3.Set(false): %v", err)
	}
	last = conn.writes[len(conn.writes)-1]
	if last[0] != byte(0xFF&^0x08) || last[1] != 0xFF {
		t.Errorf("pin3 off: write=%v, want [%#x 0xFF]", last, byte(0xFF&^0x08))
	}
	pin5 := chip.Pin(5)
	if err := pin5.Set(false); err != nil {
		t.Fatalf("pin5.Set(false): %v", err)
	}
	last = conn.writes[len(conn.writes)-1]
	want0 := byte(0xFF &^ 0x08 &^ 0x20)
	if last[0] != want0 || last[1] != 0xFF {
		t.Errorf("pin5 off preserves pin3: write=%v, want [%#x 0xFF]", last, want0)
	}
	if err := pin11.Set(false); err != nil {
		t.Fatalf("pin11.Set(false): %v", err)
	}
	last = conn.writes[len(conn.writes)-1]
	want1 := byte(0xFF &^ 0x08)
	if last[0] != want0 || last[1] != want1 {
		t.Errorf("pin11 off only touches port1: write=%v, want [%#x %#x]", last, want0, want1)
	}

	// Toggle: PCF8575Pin.Toggle() inverts the shadow bit (not a bus read).
	if err := pin3.Toggle(); err != nil {
		t.Fatalf("pin3.Toggle(): %v", err)
	}
	last = conn.writes[len(conn.writes)-1]
	if last[0] != want0|0x08 {
		t.Errorf("pin3 toggle on: write=%v, want port0=%#x", last, want0|0x08)
	}
	if err := pin3.Toggle(); err != nil {
		t.Fatalf("pin3.Toggle(): %v", err)
	}
	last = conn.writes[len(conn.writes)-1]
	if last[0] != want0 {
		t.Errorf("pin3 toggle off: write=%v, want port0=%#x", last, want0)
	}

	// Full: PollInterrupt() compares to the previous 2-byte read and
	// returns the 16-bit changed-pin bitmask (bits 0-7 = Port 0, bits 8-15
	// = Port 1).
	conn.queueRead([]byte{0xFF, 0xFF})
	if _, err := chip.PollInterrupt(); err != nil { // resync prev to a known value
		t.Fatalf("PollInterrupt resync: %v", err)
	}
	conn.queueRead([]byte{0xF7, 0xFE}) // Port0 bit3 low, Port1 bit0 low
	if v, err := chip.PollInterrupt(); err != nil || v != (0x08|(0x01<<8)) {
		t.Errorf("PollInterrupt() = %#x, %v, want %#x, nil", v, err, 0x08|(0x01<<8))
	}
	conn.queueRead([]byte{0xF7, 0xFE}) // no further change
	if v, err := chip.PollInterrupt(); err != nil || v != 0x00 {
		t.Errorf("PollInterrupt() (no change) = %#x, %v, want 0x00, nil", v, err)
	}
}
