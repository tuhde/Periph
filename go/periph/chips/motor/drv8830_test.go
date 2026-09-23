package motor

import (
	"errors"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockConnection is an in-memory fake connection.Connection for unit tests —
// no hardware, no bus. Backed by a FIFO queue: preload responses with
// queueRead; each WriteRead/Read call pops the next one.
type mockConnection struct {
	writes    [][]byte
	readQueue [][]byte
}

func newMockConnection() *mockConnection {
	return &mockConnection{}
}

func (m *mockConnection) queueRead(data []byte) {
	m.readQueue = append(m.readQueue, data)
}

func (m *mockConnection) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}

func (m *mockConnection) Read(n int) ([]byte, error) {
	return m.pop(n), nil
}

func (m *mockConnection) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return m.pop(n), nil
}

func (m *mockConnection) pop(n int) []byte {
	if len(m.readQueue) > 0 {
		front := m.readQueue[0]
		m.readQueue = m.readQueue[1:]
		out := make([]byte, n)
		copy(out, front)
		return out
	}
	return make([]byte, n)
}

func (m *mockConnection) Close() error                { return nil }
func (m *mockConnection) Enable()                     {}
func (m *mockConnection) Disable()                    {}
func (m *mockConnection) IsEnabled() bool             { return true }
func (m *mockConnection) IntPin() connection.InputPin { return nil }
func (m *mockConnection) EnPin() connection.OutputPin { return nil }

func (m *mockConnection) lastWrite() []byte {
	if len(m.writes) == 0 {
		return nil
	}
	return m.writes[len(m.writes)-1]
}

func TestDRV8830MinimalInitMakesNoWrites(t *testing.T) {
	m := newMockConnection()
	if _, err := NewDRV8830Minimal(m); err != nil {
		t.Fatal(err)
	}
	if len(m.writes) != 1 || len(m.writes[0]) != 1 || m.writes[0][0] != drv8830RegControl {
		t.Fatalf("init: want a single CONTROL address byte, got %v", m.writes)
	}
}

func TestDRV8830Drive(t *testing.T) {
	m := newMockConnection()
	d, err := NewDRV8830Minimal(m)
	if err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		name    string
		voltage float32
		want    uint8
	}{
		{"forward_3v", 3.0, 37<<2 | 0x01},
		{"reverse_2v", -2.0, 25<<2 | 0x02},
		{"zero_coasts", 0, 0x00},
		{"below_floor_coasts", 0.4, 0x00},
		{"floor_vset6", 0.48, 6<<2 | 0x01},
		{"clamps_vset63", 9.0, 63<<2 | 0x01},
	}
	for _, c := range cases {
		if err := d.Drive(c.voltage); err != nil {
			t.Fatal(err)
		}
		w := m.lastWrite()
		if len(w) != 2 || w[0] != drv8830RegControl || w[1] != c.want {
			t.Errorf("%s: got %v, want [0x00 0x%02X]", c.name, w, c.want)
		}
	}
	if err := d.Brake(); err != nil || m.lastWrite()[1] != 0x03 {
		t.Errorf("brake: got %v, err %v", m.lastWrite(), err)
	}
	if err := d.Stop(); err != nil || m.lastWrite()[1] != 0x00 {
		t.Errorf("stop: got %v, err %v", m.lastWrite(), err)
	}
}

func TestDRV8830SetOutput(t *testing.T) {
	m := newMockConnection()
	d, err := NewDRV8830Full(m)
	if err != nil {
		t.Fatal(err)
	}
	if err := d.SetOutput(20, false, true); err != nil {
		t.Fatal(err)
	}
	if w := m.lastWrite(); w[0] != drv8830RegControl || w[1] != 20<<2|0x02 {
		t.Errorf("set_output: got %v", w)
	}
	n := len(m.writes)
	if err := d.SetOutput(5, true, false); !errors.Is(err, ErrDRV8830InvalidVSet) || len(m.writes) != n {
		t.Errorf("set_output reserved: err %v, writes %d -> %d", err, n, len(m.writes))
	}
}

func TestDRV8830ReadOutput(t *testing.T) {
	m := newMockConnection()
	d, err := NewDRV8830Full(m)
	if err != nil {
		t.Fatal(err)
	}
	m.queueRead([]byte{63<<2 | 0x01})
	m.queueRead([]byte{16<<2 | 0x02})
	m.queueRead([]byte{0x03})
	m.queueRead([]byte{0x00})

	v, dir, _ := d.ReadOutput()
	if dir != DRV8830Forward || v < 5.05 || v > 5.07 {
		t.Errorf("forward: got %v %v", v, dir)
	}
	v, dir, _ = d.ReadOutput()
	if dir != DRV8830Reverse || v < 1.284 || v > 1.286 {
		t.Errorf("reverse: got %v %v", v, dir)
	}
	v, dir, _ = d.ReadOutput()
	if dir != DRV8830Brake || v != 0 {
		t.Errorf("brake: got %v %v", v, dir)
	}
	v, dir, _ = d.ReadOutput()
	if dir != DRV8830Coast || v != 0 || dir.String() != "coast" {
		t.Errorf("coast: got %v %v", v, dir)
	}
}

func TestDRV8830Faults(t *testing.T) {
	m := newMockConnection()
	d, err := NewDRV8830Full(m)
	if err != nil {
		t.Fatal(err)
	}
	m.queueRead([]byte{0x11})
	m.queueRead([]byte{0x0F})
	f, _ := d.ReadFault()
	if f != (DRV8830Fault{Fault: true, ILimit: true}) {
		t.Errorf("read_fault: got %+v", f)
	}
	f, _ = d.PollInterrupt()
	if f != (DRV8830Fault{Fault: true, OCP: true, UVLO: true, OTS: true}) {
		t.Errorf("poll_interrupt: got %+v", f)
	}
	for _, w := range m.writes {
		if len(w) == 2 && w[0] == drv8830RegFault {
			t.Errorf("read_fault must not clear, saw write %v", w)
		}
	}
	if err := d.ClearFault(); err != nil {
		t.Fatal(err)
	}
	if w := m.lastWrite(); w[0] != drv8830RegFault || w[1] != 0x80 {
		t.Errorf("clear_fault: got %v", w)
	}
}
