package power

import (
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

type mockConn struct {
	writes  [][]byte
	readSeq []byte
	readIdx int
}

func (m *mockConn) Write(data []byte) error {
	cp := make([]byte, len(data))
	copy(cp, data)
	m.writes = append(m.writes, cp)
	return nil
}

func (m *mockConn) Read(n int) ([]byte, error) {
	if m.readIdx >= len(m.readSeq) {
		out := make([]byte, n)
		return out, nil
	}
	end := m.readIdx + n
	if end > len(m.readSeq) {
		end = len(m.readSeq)
	}
	out := make([]byte, end-m.readIdx)
	copy(out, m.readSeq[m.readIdx:end])
	m.readIdx = end
	return out, nil
}

func (m *mockConn) WriteRead(data []byte, n int) ([]byte, error) {
	cp := make([]byte, len(data))
	copy(cp, data)
	m.writes = append(m.writes, cp)
	return m.Read(n)
}

func (m *mockConn) Enable()                            {}
func (m *mockConn) Disable()                           {}
func (m *mockConn) Close() error                       { return nil }
func (m *mockConn) IsEnabled() bool                    { return true }
func (m *mockConn) IntPin() connection.InputPin        { return nil }
func (m *mockConn) EnPin() connection.OutputPin        { return nil }

var _ connection.Connection = (*mockConn)(nil)

// queueRegRead queues the raw register payload returned by the next
// WriteRead call. mockConn.WriteRead ignores the address bytes it is passed
// and just pops the next n bytes off the shared FIFO, so the address is
// only a parameter for readability at call sites — it is not encoded here.
func queueRegRead(conn *mockConn, reg uint16, payload []byte) {
	conn.readSeq = append(conn.readSeq, payload...)
}

func TestADE7953FullAPI(t *testing.T) {
	conn := &mockConn{}
	// Preload VRMS = 9032007 (0x89D147) for the first read.
	queueRegRead(conn, ade7953RegVRMS, []byte{0x89, 0xD1, 0x47})

	d, err := NewADE7953Full(conn, 100.0, 10.0)
	if err != nil {
		t.Fatalf("init: %v", err)
	}

	// voltage full-scale: (1.0 / sqrt(2)) * 100.0 = ~70.71 → wait, that's not right.
	// Voltage = raw/FS_code * ADC_FS_volts * voltage_gain
	// raw=9032007, FS_code=9032007, ADC_FS_volts=0.5/sqrt(2), voltage_gain=100.
	// = 1.0 * 0.5/sqrt(2) * 100 = 35.3553
	v, err := d.Voltage()
	if err != nil {
		t.Fatalf("Voltage: %v", err)
	}
	if math.Abs(v-35.35533905932738) > 1e-3 {
		t.Errorf("voltage full-scale: got %.6f, want %.6f", v, 35.35533905932738)
	}

	// Preload IRMSA = 9032007 for the second read.
	queueRegRead(conn, 0x21A, []byte{0x89, 0xD1, 0x47})
	a, err := d.Current()
	if err != nil {
		t.Fatalf("Current: %v", err)
	}
	if math.Abs(a-3.53553390593) > 1e-3 {
		t.Errorf("current_a full-scale: got %.6f, want %.6f", a, 3.53553390593)
	}

	// Preload AWATT = 4862401 (0x4A31C1)
	queueRegRead(conn, ade7953RegAWatt, []byte{0x4A, 0x31, 0xC1})
	p, err := d.ActivePower()
	if err != nil {
		t.Fatalf("ActivePower: %v", err)
	}
	if math.Abs(p-125.0) > 1e-3 {
		t.Errorf("activePower full-scale: got %.6f, want %.6f", p, 125.0)
	}
}