package ioexpander

import "testing"

// TestPCF8574FullAPI uses the mockConnection defined in mcp23017_test.go
// (shared per Go package convention). PCF8574 has no sub-registers: every
// transaction is a single plain byte Read()/Write() (no register pointer),
// so mockConnection's register map is never consulted — reads must be
// preloaded via queueRead() in the exact order the driver will issue them.
func TestPCF8574FullAPI(t *testing.T) {
	conn := newMockConnection()
	// NewPCF8574Full's constructor issues one extra read (to seed prev for
	// interrupt comparison) right after NewPCF8574Minimal's init write.
	conn.queueRead([]byte{0xFF})
	chip, err := NewPCF8574Full(conn, 0x20)
	if err != nil {
		t.Fatalf("NewPCF8574Full: %v", err)
	}

	// Construction writes 0xFF (all pins to quasi-bidirectional input mode).
	if len(conn.writes) == 0 || len(conn.writes[0]) != 1 || conn.writes[0][0] != 0xFF {
		t.Fatalf("init: expected first write [0xFF], got %v", conn.writes)
	}
	if chip.shadow != 0xFF {
		t.Errorf("init: shadow = %#x, want 0xFF", chip.shadow)
	}

	// ReadPort(): plain single-byte read.
	conn.queueRead([]byte{0x5A})
	if v, err := chip.ReadPort0(); err != nil || v != 0x5A {
		t.Errorf("ReadPort0() = %#x, %v, want 0x5A, nil", v, err)
	}

	// WritePort(): plain single-byte write; updates shadow.
	if err := chip.WritePort0(0x3C); err != nil {
		t.Fatalf("WritePort0: %v", err)
	}
	last := conn.writes[len(conn.writes)-1]
	if last[0] != 0x3C || chip.shadow != 0x3C {
		t.Errorf("WritePort0(0x3C): write=%#x shadow=%#x, want 0x3C both", last[0], chip.shadow)
	}

	// Pin.Get() reads the live bus level (not the shadow).
	pin3 := chip.Pin(3)
	conn.queueRead([]byte{0x08}) // bit 3 high
	if high, err := pin3.Get(); err != nil || !high {
		t.Errorf("pin3.Get() = %v, %v, want true, nil", high, err)
	}

	// Pin.Set preserves other shadow bits (read-modify-write).
	if err := chip.WritePort0(0xFF); err != nil {
		t.Fatalf("WritePort0 reset: %v", err)
	}
	if err := pin3.Set(false); err != nil {
		t.Fatalf("pin3.Set(false): %v", err)
	}
	last = conn.writes[len(conn.writes)-1]
	if last[0] != byte(0xFF&^0x08) {
		t.Errorf("pin3 off: write=%#x, want %#x", last[0], byte(0xFF&^0x08))
	}
	pin5 := chip.Pin(5)
	if err := pin5.Set(false); err != nil {
		t.Fatalf("pin5.Set(false): %v", err)
	}
	last = conn.writes[len(conn.writes)-1]
	want := byte(0xFF &^ 0x08 &^ 0x20)
	if last[0] != want {
		t.Errorf("pin5 off preserves pin3: write=%#x, want %#x", last[0], want)
	}

	// Toggle: PCF8574Pin.Toggle() inverts the shadow bit (not a bus read).
	if err := pin3.Toggle(); err != nil {
		t.Fatalf("pin3.Toggle(): %v", err)
	}
	last = conn.writes[len(conn.writes)-1]
	want = byte(0xFF &^ 0x20)
	if last[0] != want {
		t.Errorf("pin3 toggle on: write=%#x, want %#x", last[0], want)
	}
	if err := pin3.Toggle(); err != nil {
		t.Fatalf("pin3.Toggle(): %v", err)
	}
	last = conn.writes[len(conn.writes)-1]
	want = byte(0xFF &^ 0x08 &^ 0x20)
	if last[0] != want {
		t.Errorf("pin3 toggle off: write=%#x, want %#x", last[0], want)
	}

	// Full: PollInterrupt() compares to the previous read and returns the
	// changed-pin bitmask, also updating the stored previous value.
	conn.queueRead([]byte{0xFF})
	if _, err := chip.PollInterrupt(); err != nil { // resync prev to a known value (0xFF)
		t.Fatalf("PollInterrupt resync: %v", err)
	}
	conn.queueRead([]byte{0xF7}) // bit 3 now low
	if v, err := chip.PollInterrupt(); err != nil || v != 0x08 {
		t.Errorf("PollInterrupt() = %#x, %v, want 0x08, nil", v, err)
	}
	conn.queueRead([]byte{0xF7}) // no further change
	if v, err := chip.PollInterrupt(); err != nil || v != 0x00 {
		t.Errorf("PollInterrupt() (no change) = %#x, %v, want 0x00, nil", v, err)
	}
}
