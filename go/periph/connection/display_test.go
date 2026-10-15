package connection

import (
	"bytes"
	"errors"
	"testing"
	"time"
)

type dispLog struct{ events []string }

type dispPin struct {
	name string
	log  *dispLog
}

func (p *dispPin) Set(high bool) error {
	s := p.name + "=0"
	if high {
		s = p.name + "=1"
	}
	p.log.events = append(p.log.events, s)
	return nil
}

type fakeDisplaySPI struct {
	writes [][]byte
	reads  []byte
}

func (f *fakeDisplaySPI) Write(d []byte) error {
	f.writes = append(f.writes, append([]byte(nil), d...))
	return nil
}

func (f *fakeDisplaySPI) Read(n int) ([]byte, error) {
	out := make([]byte, n)
	for i := range out {
		if len(f.reads) > 0 {
			out[i], f.reads = f.reads[0], f.reads[1:]
		}
	}
	return out, nil
}

type levelPin struct{ high bool }

func (p *levelPin) OnEdge(Trigger, func()) func() { return func() {} }
func (p *levelPin) IsHigh() (bool, error)         { return p.high, nil }

func newTestConn(t *testing.T, mode WireMode, busy InputPin) (*DisplayConnection, *fakeDisplaySPI, *dispLog) {
	t.Helper()
	log := &dispLog{}
	spi := &fakeDisplaySPI{}
	c, err := newDisplayConnection(spi, &dispPin{"dc", log}, &dispPin{"res", log}, busy, mode, nil)
	if err != nil {
		t.Fatal(err)
	}
	c.SetResetTiming(time.Microsecond, time.Millisecond)
	return c, spi, log
}

func TestDisplayCommandFraming(t *testing.T) {
	c, spi, log := newTestConn(t, WireModeSimplex, nil)
	log.events = nil
	if err := c.SendCommand(0x10, []byte{1, 2, 3}); err != nil {
		t.Fatal(err)
	}
	if len(spi.writes) != 2 || spi.writes[0][0] != 0x10 || !bytes.Equal(spi.writes[1], []byte{1, 2, 3}) {
		t.Fatalf("writes = %v", spi.writes)
	}
	if got := log.events; len(got) != 2 || got[0] != "dc=0" || got[1] != "dc=1" {
		t.Fatalf("dc sequence = %v", got)
	}
	_ = c.SendCommand(0x04, nil)
	if len(spi.writes) != 3 {
		t.Fatalf("command without data should be one transfer, got %d", len(spi.writes))
	}
	if _, err := c.ReadData(1); !errors.Is(err, ErrReadUnavailable) {
		t.Fatalf("simplex read err = %v", err)
	}
}

func TestDisplayReset(t *testing.T) {
	c, _, log := newTestConn(t, WireModeSimplex, nil)
	log.events = nil
	if err := c.Reset(); err != nil {
		t.Fatal(err)
	}
	if len(log.events) != 2 || log.events[0] != "res=0" || log.events[1] != "res=1" {
		t.Fatalf("reset sequence = %v", log.events)
	}
}

func TestDisplayWaitBusy(t *testing.T) {
	busy := &levelPin{high: true}
	c, _, _ := newTestConn(t, WireModeSimplex, busy)
	if ok, _ := c.WaitBusy(false, 20*time.Millisecond); !ok {
		t.Fatal("active-low with high level must be ready")
	}
	if ok, _ := c.WaitBusy(true, 20*time.Millisecond); ok {
		t.Fatal("active-high stuck high must time out")
	}
	go func() {
		time.Sleep(10 * time.Millisecond)
		busy.high = false
	}()
	if ok, _ := c.WaitBusy(true, time.Second); !ok {
		t.Fatal("active-high released in time must be ready")
	}
	cNo, _, _ := newTestConn(t, WireModeSimplex, nil)
	if ok, _ := cNo.WaitBusy(true, time.Millisecond); !ok {
		t.Fatal("no BUSY pin is always ready")
	}
	if lvl, _ := cNo.BusyLevel(); lvl {
		t.Fatal("no BUSY pin reads low")
	}
}

func TestDisplayReadAndGate(t *testing.T) {
	c, spi, _ := newTestConn(t, WireModeHalfDuplexPin, nil)
	spi.reads = []byte{0xA1, 0xB2}
	got, err := c.ReadData(2)
	if err != nil || !bytes.Equal(got, []byte{0xA1, 0xB2}) {
		t.Fatalf("read = %v, %v", got, err)
	}
	c.Disable()
	_ = c.SendCommand(1, []byte{2})
	if len(spi.writes) != 0 {
		t.Fatal("disabled connection must not transfer")
	}
}

func TestDisplayHalfDuplexHWRejected(t *testing.T) {
	_, err := newDisplayConnection(&fakeDisplaySPI{}, &dispPin{"dc", &dispLog{}}, nil, nil, WireModeHalfDuplexHW, nil)
	if !errors.Is(err, ErrHalfDuplexHWUnsupported) {
		t.Fatalf("err = %v", err)
	}
}

func TestSoftSPIBitOrderAndRead(t *testing.T) {
	var clk []int
	var mosiLevel bool
	sck := &funcPin{set: func(h bool) {
		if h {
			v := 0
			if mosiLevel {
				v = 1
			}
			clk = append(clk, v)
		}
	}}
	mosi := &funcPin{set: func(h bool) { mosiLevel = h }}
	s, err := NewSoftSPI(sck, mosi, nil)
	if err != nil {
		t.Fatal(err)
	}
	_ = s.Write([]byte{0xA5})
	want := []int{1, 0, 1, 0, 0, 1, 0, 1}
	for i, v := range want {
		if clk[i] != v {
			t.Fatalf("bit %d = %d want %d", i, clk[i], v)
		}
	}
	if _, err := s.Read(1, 0); !errors.Is(err, ErrNoMISO) {
		t.Fatalf("read without MISO err = %v", err)
	}

	bits := []bool{false, false, true, true, true, true, false, false} // 0x3C
	k := 0
	miso := &seqPin{next: func() bool { b := bits[k%8]; k++; return b }}
	s2, _ := NewSoftSPI(&funcPin{set: func(bool) {}}, &funcPin{set: func(bool) {}}, miso)
	got, err := s2.Read(1, 0)
	if err != nil || got[0] != 0x3C {
		t.Fatalf("read = %v, %v", got, err)
	}
}

type funcPin struct{ set func(bool) }

func (p *funcPin) Set(h bool) error { p.set(h); return nil }

type seqPin struct{ next func() bool }

func (p *seqPin) OnEdge(Trigger, func()) func() { return func() {} }
func (p *seqPin) IsHigh() (bool, error)         { return p.next(), nil }

func TestDisplayMock(t *testing.T) {
	m := NewDisplayConnectionMock(WireModeHalfDuplexPin)
	m.QueueRead(7, 8)
	_ = m.Reset()
	_ = m.SendCommand(0x12, []byte{1})
	_, _ = m.WaitBusy(false, time.Second)
	if len(m.Commands()) != 1 || m.Commands()[0].Cmd != 0x12 {
		t.Fatalf("commands = %v", m.Commands())
	}
	got, _ := m.ReadData(2)
	if !bytes.Equal(got, []byte{7, 8}) {
		t.Fatalf("read = %v", got)
	}
	if _, err := NewDisplayConnectionMock(WireModeSimplex).ReadData(1); err == nil {
		t.Fatal("simplex mock read must fail")
	}
}

func TestPollingInputPinLevel(t *testing.T) {
	p := NewPollingInputPin(time.Hour)
	defer p.Close()
	if _, err := p.IsHigh(); err == nil {
		t.Fatal("no level source must error")
	}
	high := true
	q := NewPollingInputPinWithLevel(time.Hour, func() (bool, error) { return high, nil })
	defer q.Close()
	if v, _ := q.IsHigh(); !v {
		t.Fatal("level high")
	}
	high = false
	if v, _ := q.IsHigh(); v {
		t.Fatal("level low")
	}
}
