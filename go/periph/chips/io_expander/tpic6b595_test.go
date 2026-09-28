package ioexpander

import (
	"errors"
	"testing"
)

// mockSiPo is an in-memory fake SiPo connection for unit tests — no
// hardware, no bus. SRCLR/G availability is configurable at construction,
// matching the real connection's error when the corresponding GPIO line
// wasn't wired.
type mockSiPo struct {
	writes            [][]byte
	clearCount        int
	outputEnableCalls []bool
	hasSrclr          bool
	hasG              bool
}

func newMockSiPo(hasSrclr, hasG bool) *mockSiPo {
	return &mockSiPo{hasSrclr: hasSrclr, hasG: hasG}
}

func (m *mockSiPo) Write(data []byte) error {
	cp := make([]byte, len(data))
	copy(cp, data)
	m.writes = append(m.writes, cp)
	return nil
}

func (m *mockSiPo) Clear() error {
	if !m.hasSrclr {
		return errors.New("SRCLR not configured")
	}
	m.clearCount++
	return nil
}

func (m *mockSiPo) SetOutputEnable(en bool) error {
	if !m.hasG {
		return errors.New("G not configured")
	}
	m.outputEnableCalls = append(m.outputEnableCalls, en)
	return nil
}

func (m *mockSiPo) Close() error { return nil }

func bytesEqualTPIC(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestTPIC6B595MinimalConstruction(t *testing.T) {
	conn := newMockSiPo(true, true)
	chip, err := NewTPIC6B595Minimal(conn, 1)
	if err != nil {
		t.Fatalf("NewTPIC6B595Minimal: %v", err)
	}
	if conn.clearCount != 1 {
		t.Errorf("clearCount = %d, want 1", conn.clearCount)
	}
	last := conn.writes[len(conn.writes)-1]
	if !bytesEqualTPIC(last, []byte{0x00}) {
		t.Errorf("init write = %v, want [0x00]", last)
	}
	if chip.Shadow(0) != 0x00 {
		t.Errorf("shadow = %#x, want 0x00", chip.Shadow(0))
	}
}

func TestTPIC6B595MinimalConstructionWithoutSRCLR(t *testing.T) {
	// Regression: missing SRCLR must not fail construction -- Clear()'s
	// error is discarded (best-effort) in NewTPIC6B595Minimal.
	conn := newMockSiPo(false, true)
	chip, err := NewTPIC6B595Minimal(conn, 1)
	if err != nil {
		t.Fatalf("NewTPIC6B595Minimal without SRCLR: %v", err)
	}
	if conn.clearCount != 0 {
		t.Errorf("clearCount = %d, want 0 (SRCLR unconfigured)", conn.clearCount)
	}
	last := conn.writes[len(conn.writes)-1]
	if !bytesEqualTPIC(last, []byte{0x00}) {
		t.Errorf("init write = %v, want [0x00]", last)
	}
	if chip.Shadow(0) != 0x00 {
		t.Errorf("shadow = %#x, want 0x00", chip.Shadow(0))
	}
}

func TestTPIC6B595MinimalNumDevicesValidation(t *testing.T) {
	conn := newMockSiPo(true, true)
	if _, err := NewTPIC6B595Minimal(conn, 0); err == nil {
		t.Error("NewTPIC6B595Minimal(0): expected error, got nil")
	}
	if _, err := NewTPIC6B595Minimal(conn, MaxTPIC6B595Devices+1); err == nil {
		t.Error("NewTPIC6B595Minimal(MaxTPIC6B595Devices+1): expected error, got nil")
	}
}

func TestTPIC6B595Pin(t *testing.T) {
	conn := newMockSiPo(true, true)
	chip, _ := NewTPIC6B595Minimal(conn, 1)

	pin3 := chip.Pin(3)
	if err := pin3.Set(true); err != nil {
		t.Fatalf("pin3.Set(true): %v", err)
	}
	if chip.Shadow(0) != 0x08 {
		t.Errorf("shadow after pin3 on = %#x, want 0x08", chip.Shadow(0))
	}
	if v, err := pin3.Get(); err != nil || !v {
		t.Errorf("pin3.Get() = %v, %v, want true, nil", v, err)
	}

	if err := pin3.Set(false); err != nil {
		t.Fatalf("pin3.Set(false): %v", err)
	}
	if v, _ := pin3.Get(); v {
		t.Error("pin3.Get() after Set(false) = true, want false")
	}

	if err := pin3.Toggle(); err != nil {
		t.Fatalf("pin3.Toggle(): %v", err)
	}
	if v, _ := pin3.Get(); !v {
		t.Error("pin3.Get() after Toggle() = false, want true")
	}

	pin5 := chip.Pin(5)
	if err := pin5.Set(true); err != nil {
		t.Fatalf("pin5.Set(true): %v", err)
	}
	if chip.Shadow(0) != 0x28 { // 0x08 (pin3) | 0x20 (pin5)
		t.Errorf("shadow after pin5 on = %#x, want 0x28 (pin3 preserved)", chip.Shadow(0))
	}
}

func TestTPIC6B595WritePortFillOff(t *testing.T) {
	conn := newMockSiPo(true, true)
	chip, _ := NewTPIC6B595Minimal(conn, 1)

	if err := chip.WritePort(0, 0x3C); err != nil {
		t.Fatalf("WritePort: %v", err)
	}
	last := conn.writes[len(conn.writes)-1]
	if !bytesEqualTPIC(last, []byte{0x3C}) {
		t.Errorf("WritePort write = %v, want [0x3C]", last)
	}

	if err := chip.Fill(true); err != nil {
		t.Fatalf("Fill(true): %v", err)
	}
	last = conn.writes[len(conn.writes)-1]
	if !bytesEqualTPIC(last, []byte{0xFF}) {
		t.Errorf("Fill(true) write = %v, want [0xFF]", last)
	}

	if err := chip.Off(); err != nil {
		t.Fatalf("Off: %v", err)
	}
	last = conn.writes[len(conn.writes)-1]
	if !bytesEqualTPIC(last, []byte{0x00}) {
		t.Errorf("Off write = %v, want [0x00]", last)
	}
}

func TestTPIC6B595CascadeWireOrderReversed(t *testing.T) {
	conn := newMockSiPo(true, true)
	chip, _ := NewTPIC6B595Minimal(conn, 3)

	if err := chip.WritePort(0, 0xAA); err != nil {
		t.Fatalf("WritePort(0): %v", err)
	}
	if err := chip.WritePort(1, 0xBB); err != nil {
		t.Fatalf("WritePort(1): %v", err)
	}
	if err := chip.WritePort(2, 0xCC); err != nil {
		t.Fatalf("WritePort(2): %v", err)
	}
	last := conn.writes[len(conn.writes)-1]
	if !bytesEqualTPIC(last, []byte{0xCC, 0xBB, 0xAA}) {
		t.Errorf("cascade write = %v, want [0xCC 0xBB 0xAA] (wire-order reversed)", last)
	}

	pinFar := chip.Pin(16) // device 2, bit 0
	if err := pinFar.Set(true); err != nil {
		t.Fatalf("pinFar.Set(true): %v", err)
	}
	if chip.Shadow(2) != 0xCD {
		t.Errorf("shadow[2] after far pin on = %#x, want 0xCD", chip.Shadow(2))
	}
	last = conn.writes[len(conn.writes)-1]
	if !bytesEqualTPIC(last, []byte{0xCD, 0xBB, 0xAA}) {
		t.Errorf("cascade write after far pin = %v, want [0xCD 0xBB 0xAA]", last)
	}
}

func TestTPIC6B595FullClearAndSetOutputEnable(t *testing.T) {
	conn := newMockSiPo(true, true)
	full, err := NewTPIC6B595Full(conn, 1)
	if err != nil {
		t.Fatalf("NewTPIC6B595Full: %v", err)
	}
	if err := full.Clear(); err != nil {
		t.Fatalf("Clear: %v", err)
	}
	if conn.clearCount != 2 { // +1 from construction
		t.Errorf("clearCount = %d, want 2", conn.clearCount)
	}

	if err := full.SetOutputEnable(true); err != nil {
		t.Fatalf("SetOutputEnable(true): %v", err)
	}
	if err := full.SetOutputEnable(false); err != nil {
		t.Fatalf("SetOutputEnable(false): %v", err)
	}
	want := []bool{true, false}
	if len(conn.outputEnableCalls) != len(want) {
		t.Fatalf("outputEnableCalls = %v, want %v", conn.outputEnableCalls, want)
	}
	for i := range want {
		if conn.outputEnableCalls[i] != want[i] {
			t.Errorf("outputEnableCalls[%d] = %v, want %v", i, conn.outputEnableCalls[i], want[i])
		}
	}
}

func TestTPIC6B595FullClearAndSetOutputEnableErrorsWhenUnwired(t *testing.T) {
	conn := newMockSiPo(false, false)
	full, err := NewTPIC6B595Full(conn, 1)
	if err != nil {
		t.Fatalf("NewTPIC6B595Full: %v", err)
	}
	if err := full.Clear(); err == nil {
		t.Error("Clear() with unwired SRCLR: expected error, got nil")
	}
	if err := full.SetOutputEnable(true); err == nil {
		t.Error("SetOutputEnable() with unwired G: expected error, got nil")
	}
}

func TestTPIC6B595FullWriteAll(t *testing.T) {
	conn := newMockSiPo(true, true)
	full, _ := NewTPIC6B595Full(conn, 3)

	if err := full.WriteAll([]uint8{0x11, 0x22}); err != nil { // shorter -> zero-extend
		t.Fatalf("WriteAll (short): %v", err)
	}
	if chip := full.TPIC6B595Minimal; chip.Shadow(0) != 0x11 || chip.Shadow(1) != 0x22 || chip.Shadow(2) != 0x00 {
		t.Errorf("shadow after short WriteAll = [%#x %#x %#x], want [0x11 0x22 0x00]",
			chip.Shadow(0), chip.Shadow(1), chip.Shadow(2))
	}
	last := conn.writes[len(conn.writes)-1]
	if !bytesEqualTPIC(last, []byte{0x00, 0x22, 0x11}) {
		t.Errorf("WriteAll (short) write = %v, want [0x00 0x22 0x11]", last)
	}

	if err := full.WriteAll([]uint8{0x44, 0x55, 0x66, 0x77}); err != nil { // longer -> truncate
		t.Fatalf("WriteAll (long): %v", err)
	}
	if chip := full.TPIC6B595Minimal; chip.Shadow(0) != 0x44 || chip.Shadow(1) != 0x55 || chip.Shadow(2) != 0x66 {
		t.Errorf("shadow after long WriteAll = [%#x %#x %#x], want [0x44 0x55 0x66]",
			chip.Shadow(0), chip.Shadow(1), chip.Shadow(2))
	}
	last = conn.writes[len(conn.writes)-1]
	if !bytesEqualTPIC(last, []byte{0x66, 0x55, 0x44}) {
		t.Errorf("WriteAll (long) write = %v, want [0x66 0x55 0x44]", last)
	}
}
