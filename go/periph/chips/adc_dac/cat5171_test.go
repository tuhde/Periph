package adcdac

import (
	"math"
	"testing"
)

func cat5171Last(t *testing.T, label string, m *mockConnection, want ...byte) {
	t.Helper()
	got := m.writes[len(m.writes)-1]
	if len(got) != len(want) || got[0] != want[0] || got[1] != want[1] {
		t.Errorf("%s: got %v, want %v", label, got, want)
	}
}

func cat5171Near(a, b float32) bool { return math.Abs(float64(a-b)) < 0.01 }

func TestCAT5171MinimalWiper(t *testing.T) {
	m := newMockConnection()
	pot, _ := NewCAT5171Minimal(m, CAT5171DefaultRAB, CAT5171DefaultRWiper)
	if len(m.writes) != 0 {
		t.Fatalf("construction made bus traffic: %v", m.writes)
	}
	pot.SetWiper(100)
	cat5171Last(t, "set", m, 0x00, 100)
	pot.SetWiper(300)
	cat5171Last(t, "clamp hi", m, 0x00, 255)
	pot.SetWiper(-5)
	cat5171Last(t, "clamp lo", m, 0x00, 0)
	n := len(m.writes)
	m.queueRead([]byte{17})
	if v, _ := pot.GetWiper(); v != 17 {
		t.Errorf("get = %d", v)
	}
	if len(m.writes) != n {
		t.Errorf("get_wiper wrote to the bus: %v", m.writes[n:])
	}
}

func TestCAT5171MinimalResistance(t *testing.T) {
	m := newMockConnection()
	pot, _ := NewCAT5171Minimal(m, 100000, 50)
	pot.SetResistance(50050)
	cat5171Last(t, "R mid", m, 0x00, 128)
	pot.SetResistance(0)
	cat5171Last(t, "R clamp lo", m, 0x00, 0)
	pot.SetResistance(1e7)
	cat5171Last(t, "R clamp hi", m, 0x00, 255)
	m.queueRead([]byte{128})
	m.queueRead([]byte{0})
	if r, _ := pot.GetResistance(); !cat5171Near(r, 50050) {
		t.Errorf("R mid = %v", r)
	}
	if r, _ := pot.GetResistance(); !cat5171Near(r, 50) {
		t.Errorf("R zero = %v", r)
	}
}

func TestCAT5171FullHelpers(t *testing.T) {
	m := newMockConnection()
	pot, _ := NewCAT5171Full(m, 100000, 50)
	pot.SetRatio(0.25)
	cat5171Last(t, "ratio", m, 0x00, 64)
	pot.SetRatio(1.0)
	cat5171Last(t, "ratio clamp", m, 0x00, 255)
	pot.SetResistanceWA(50050)
	cat5171Last(t, "wa", m, 0x00, 128)

	m.queueRead([]byte{128})
	m.queueRead([]byte{64})
	if r, _ := pot.GetRatio(); r != 0.5 {
		t.Errorf("ratio = %v", r)
	}
	if r, _ := pot.GetResistanceWA(); !cat5171Near(r, 75050) {
		t.Errorf("R_WA = %v", r)
	}

	m.queueRead([]byte{250})
	m.queueRead([]byte{250})
	m.queueRead([]byte{128})
	if v, _ := pot.Increment(3); v != 253 {
		t.Errorf("inc = %d", v)
	}
	cat5171Last(t, "inc write", m, 0x00, 253)
	if v, _ := pot.Increment(100); v != 255 {
		t.Errorf("inc clamp = %d", v)
	}
	if v, _ := pot.Increment(-28); v != 100 {
		t.Errorf("dec = %d", v)
	}
	cat5171Last(t, "dec write", m, 0x00, 100)
}

func TestCAT5171ShutdownMidscaleReset(t *testing.T) {
	m := newMockConnection()
	pot, _ := NewCAT5171Full(m, 100000, 50)
	if pot.IsShutdown() {
		t.Error("shutdown at start")
	}
	pot.Shutdown()
	cat5171Last(t, "shutdown midscale", m, 0x20, 128)
	if !pot.IsShutdown() {
		t.Error("not shutdown")
	}
	pot.Resume()
	cat5171Last(t, "resume", m, 0x00, 128)
	pot.SetWiper(77)
	pot.Shutdown()
	cat5171Last(t, "shutdown 77", m, 0x20, 77)
	pot.SetWiper(50)
	cat5171Last(t, "set keeps SD", m, 0x20, 50)
	pot.Resume()
	cat5171Last(t, "resume tracked", m, 0x00, 50)
	pot.Midscale()
	cat5171Last(t, "midscale", m, 0x40, 0x80)
	pot.Shutdown()
	pot.Midscale()
	cat5171Last(t, "midscale keeps SD", m, 0x60, 0x80)
	if !pot.IsShutdown() {
		t.Error("midscale cleared shutdown")
	}
	pot.Reset()
	cat5171Last(t, "reset", m, 0x40, 0x80)
	if pot.IsShutdown() {
		t.Error("reset kept shutdown")
	}
}

func TestCAT5171SetRAB(t *testing.T) {
	m := newMockConnection()
	pot, _ := NewCAT5171Full(m, 100000, 50)
	pot.SetRAB(50000, 100)
	if len(m.writes) != 0 {
		t.Error("SetRAB made bus traffic")
	}
	m.queueRead([]byte{128})
	m.queueRead([]byte{128})
	if r, _ := pot.GetResistance(); !cat5171Near(r, 25100) {
		t.Errorf("R = %v", r)
	}
	pot.SetRAB(50000, -1)
	if r, _ := pot.GetResistance(); !cat5171Near(r, 25100) {
		t.Errorf("R keep RW = %v", r)
	}
}
