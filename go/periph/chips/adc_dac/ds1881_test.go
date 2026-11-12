package adcdac

import (
	"bytes"
	"errors"
	"testing"
)

// ds1881Mock adds NACKing reads to the shared mockConnection.
type ds1881Mock struct {
	*mockConnection
	failReads int
}

func (m *ds1881Mock) Read(n int) ([]byte, error) {
	if m.failReads > 0 {
		m.failReads--
		return nil, errors.New("nack")
	}
	return m.mockConnection.Read(n)
}

func newDS1881Mock() *ds1881Mock { return &ds1881Mock{mockConnection: newMockConnection()} }

func ds1881Last(t *testing.T, label string, m *ds1881Mock, want ...byte) {
	t.Helper()
	if len(m.writes) == 0 {
		t.Fatalf("%s: no writes", label)
	}
	got := m.writes[len(m.writes)-1]
	if !bytes.Equal(got, want) {
		t.Errorf("%s: got %v, want %v", label, got, want)
	}
}

func TestDS1881Init(t *testing.T) {
	m := newDS1881Mock()
	pot, _ := NewDS1881Minimal(m)
	if len(m.writes) != 0 {
		t.Fatalf("construction made bus traffic: %v", m.writes)
	}
	if err := pot.Init(); err != nil {
		t.Fatal(err)
	}
	if len(m.writes) != 1 {
		t.Fatalf("init writes = %v", m.writes)
	}
	ds1881Last(t, "init config", m, 0x86)

	m = newDS1881Mock()
	m.queueRead([]byte{10, 20, 0x86})
	pot, _ = NewDS1881Minimal(m)
	pot.Init()
	if len(m.writes) != 0 {
		t.Errorf("matching config rewrote: %v", m.writes)
	}
	m = newDS1881Mock()
	m.queueRead([]byte{0, 0, 0xB6})
	pot, _ = NewDS1881Minimal(m)
	pot.Init()
	if len(m.writes) != 0 {
		t.Errorf("stray upper bits rewrote: %v", m.writes)
	}
}

func TestDS1881MinimalAttenuation(t *testing.T) {
	m := newDS1881Mock()
	pot, _ := NewDS1881Minimal(m)
	pot.SetAttenuation(0, 20)
	ds1881Last(t, "ch0", m, 20)
	pot.SetAttenuation(1, 20.4)
	ds1881Last(t, "round down", m, 0x40|20)
	pot.SetAttenuation(1, 20.6)
	ds1881Last(t, "round up", m, 0x40|21)
	pot.SetAttenuation(0, 100)
	ds1881Last(t, "clamp 62", m, 62)
	pot.SetAttenuation(0, -5)
	ds1881Last(t, "clamp 0", m, 0)
	if err := pot.SetAttenuation(2, 0); err != ErrDS1881Channel {
		t.Errorf("bad channel err = %v", err)
	}
	if _, err := pot.GetPosition(-1); err != ErrDS1881Channel {
		t.Errorf("bad channel get err = %v", err)
	}
	pot.Mute(1)
	ds1881Last(t, "mute", m, 0x7F)
	pot.SetPosition(0, 99)
	ds1881Last(t, "position clamp", m, 63)
	pot.SetPosition(0, -3)
	ds1881Last(t, "position clamp lo", m, 0)
}

func TestDS1881MinimalReads(t *testing.T) {
	m := newDS1881Mock()
	for i := 0; i < 4; i++ {
		m.queueRead([]byte{0xC5, 0x3F, 0x86})
	}
	pot, _ := NewDS1881Minimal(m)
	if p, _ := pot.GetPosition(0); p != 5 {
		t.Errorf("ch0 = %d", p)
	}
	if p, _ := pot.GetPosition(1); p != 63 {
		t.Errorf("ch1 = %d", p)
	}
	if db, _ := pot.GetAttenuation(1); db != 80 {
		t.Errorf("mute db = %v", db)
	}
	if db, _ := pot.GetAttenuation(0); db != 5 {
		t.Errorf("db = %v", db)
	}
	if len(m.writes) != 0 {
		t.Errorf("reads wrote: %v", m.writes)
	}
}

func TestDS1881FullStereoAndTables(t *testing.T) {
	m := newDS1881Mock()
	full, _ := NewDS1881Full(m)
	full.SetBoth(10, 99)
	if len(m.writes) != 1 {
		t.Fatalf("set_both writes = %d", len(m.writes))
	}
	ds1881Last(t, "set_both", m, 10, 0x40|63)
	full.SetAttenuationBoth(30)
	ds1881Last(t, "both default", m, 30, 0x40|30)
	full.SetAttenuationBoth(5, 70)
	ds1881Last(t, "both mute", m, 5, 0x7F)
	if full.PositionToDB(0) != 0 || full.PositionToDB(62) != 62 || full.PositionToDB(63) != 80 {
		t.Error("option 1 table")
	}
	if full.MaxPosition() != 62 || full.DBToPosition(12.4) != 12 || full.DBToPosition(75) != 63 {
		t.Error("option 1 helpers")
	}

	full.Configure(2, true, false)
	ds1881Last(t, "option 2", m, 0x87)
	n := len(m.writes)
	full.Configure(2, true, false)
	if len(m.writes) != n {
		t.Error("unchanged configure wrote")
	}
	want := []float32{0, 12, 14, 36, 39, 60, 80}
	for i, p := range []int{0, 12, 13, 24, 25, 32, 33} {
		if full.PositionToDB(p) != want[i] {
			t.Errorf("option 2 position %d = %v", p, full.PositionToDB(p))
		}
	}
	if full.MaxPosition() != 32 {
		t.Error("option 2 max")
	}
	if full.DBToPosition(14) != 13 || full.DBToPosition(36) != 24 || full.DBToPosition(60) != 32 ||
		full.DBToPosition(37) != 24 || full.DBToPosition(38) != 25 || full.DBToPosition(70) != 33 {
		t.Error("option 2 db to position")
	}
	full.SetBoth(40, 40)
	ds1881Last(t, "clamped", m, 33, 0x40|33)
	full.Mute(0)
	ds1881Last(t, "option 2 mute", m, 33)
	if err := full.Configure(3, true, false); err != ErrDS1881Option {
		t.Errorf("bad option err = %v", err)
	}
}

func TestDS1881FullConfigBits(t *testing.T) {
	m := newDS1881Mock()
	full, _ := NewDS1881Full(m)
	full.SetZeroCrossing(false)
	ds1881Last(t, "zc off", m, 0x84)
	full.SetNonvolatile(true)
	ds1881Last(t, "nv on", m, 0x80)
	full.SetOption(2)
	ds1881Last(t, "option 2 keeps bits", m, 0x81)
	m.failReads = 1
	full.SetBoth(1, 2)
	ds1881Last(t, "nv write polls", m, 1, 0x42)
	if m.failReads != 0 {
		t.Error("nv write did not poll")
	}
}

func TestDS1881FullConfigReads(t *testing.T) {
	m := newDS1881Mock()
	m.queueRead([]byte{0, 0, 0x87})
	m.queueRead([]byte{1, 2, 0x82})
	m.queueRead([]byte{0x41, 0x02, 0x86})
	full, _ := NewDS1881Full(m)
	cfg, _ := full.GetConfig()
	if cfg.Option != 2 || !cfg.ZeroCrossing || cfg.Nonvolatile || cfg.Raw != 0x87 || full.MaxPosition() != 32 {
		t.Errorf("config = %+v", cfg)
	}
	cfg, _ = full.GetConfig()
	if !cfg.Nonvolatile || cfg.Option != 1 || !cfg.ZeroCrossing {
		t.Errorf("nv config = %+v", cfg)
	}
	p0, p1, c, _ := full.ReadAll()
	if p0 != 1 || p1 != 2 || c != 0x86 {
		t.Errorf("read_all = %d %d %x", p0, p1, c)
	}
}

func TestDS1881WaitReady(t *testing.T) {
	m := newDS1881Mock()
	full, _ := NewDS1881Full(m)
	m.failReads = 3
	if err := full.WaitReady(0.05); err != nil {
		t.Errorf("recover err = %v", err)
	}
	m.failReads = 1000
	if err := full.WaitReady(0.01); err != ErrDS1881Busy {
		t.Errorf("timeout err = %v", err)
	}
}

func TestDS1881StoreAndReset(t *testing.T) {
	m := newDS1881Mock()
	m.queueRead([]byte{12, 34, 0x86})
	full, _ := NewDS1881Full(m)
	if err := full.Store(); err != nil {
		t.Fatal(err)
	}
	want := [][]byte{{0x82}, {12, 0x40 | 34}, {0x86}}
	if len(m.writes) != 3 {
		t.Fatalf("store writes = %v", m.writes)
	}
	for i := range want {
		if !bytes.Equal(m.writes[i], want[i]) {
			t.Errorf("store write %d = %v, want %v", i, m.writes[i], want[i])
		}
	}

	m = newDS1881Mock()
	full, _ = NewDS1881Full(m)
	full.Configure(2, false, true)
	full.Reset()
	ds1881Last(t, "reset", m, 0x86, 0x3F, 0x7F)
	if full.MaxPosition() != 62 {
		t.Error("reset did not restore option 1")
	}
}
