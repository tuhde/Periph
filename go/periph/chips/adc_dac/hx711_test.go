package adcdac

import (
	"errors"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockHX711Conn is an in-memory fake connection.HX711Conn for unit tests —
// no hardware, no GPIO. HX711 has no register map; each conversion is
// selected by the pulse count (25/26/27) passed to ReadRaw. Preload signed
// 24-bit conversion results with queueRead; each ReadRaw call pops the
// next one (0 if the queue is empty) and, matching the real connections'
// contract, rejects a pulse count that isn't 25/26/27. Every call's pulse
// count is appended to reads so tests can assert which channel/gain a
// driver call actually requested. PowerDown/PowerUp calls are logged to
// powerCalls.
type mockHX711Conn struct {
	queue      []int32
	reads      []int
	powerCalls []string
	ready      bool
}

func newMockHX711Conn() *mockHX711Conn {
	return &mockHX711Conn{ready: true}
}

func (m *mockHX711Conn) queueRead(v int32) {
	m.queue = append(m.queue, v)
}

func (m *mockHX711Conn) IsReady() (bool, error) {
	return m.ready, nil
}

func (m *mockHX711Conn) ReadRaw(numPulses int) (int32, error) {
	if numPulses != 25 && numPulses != 26 && numPulses != 27 {
		return 0, errors.New("hx711: numPulses must be 25, 26, or 27")
	}
	m.reads = append(m.reads, numPulses)
	if len(m.queue) > 0 {
		v := m.queue[0]
		m.queue = m.queue[1:]
		return v, nil
	}
	return 0, nil
}

func (m *mockHX711Conn) PowerDown() error {
	m.powerCalls = append(m.powerCalls, "down")
	return nil
}

func (m *mockHX711Conn) PowerUp() error {
	m.powerCalls = append(m.powerCalls, "up")
	return nil
}

var _ connection.HX711Conn = (*mockHX711Conn)(nil)

func lastRead(reads []int) int {
	if len(reads) == 0 {
		return 0
	}
	return reads[len(reads)-1]
}

func lastPowerCall(calls []string) string {
	if len(calls) == 0 {
		return ""
	}
	return calls[len(calls)-1]
}

func TestHX711MinimalInitDiscardsFirstReading(t *testing.T) {
	conn := newMockHX711Conn()
	conn.queueRead(0)
	if _, err := NewHX711Minimal(conn); err != nil {
		t.Fatalf("NewHX711Minimal: %v", err)
	}
	if len(conn.reads) != 1 || conn.reads[0] != 25 {
		t.Errorf("init reads = %v, want [25]", conn.reads)
	}
}

func TestHX711MinimalIsReady(t *testing.T) {
	conn := newMockHX711Conn()
	conn.queueRead(0)
	d, err := NewHX711Minimal(conn)
	if err != nil {
		t.Fatalf("NewHX711Minimal: %v", err)
	}
	conn.ready = false
	if ready, err := d.IsReady(); err != nil || ready {
		t.Errorf("IsReady() = %v, %v, want false, nil", ready, err)
	}
	conn.ready = true
	if ready, err := d.IsReady(); err != nil || !ready {
		t.Errorf("IsReady() = %v, %v, want true, nil", ready, err)
	}
}

func TestHX711MinimalReadRawGain128(t *testing.T) {
	conn := newMockHX711Conn()
	conn.queueRead(0)
	d, err := NewHX711Minimal(conn)
	if err != nil {
		t.Fatalf("NewHX711Minimal: %v", err)
	}
	conn.queueRead(12345)
	v, err := d.ReadRaw()
	if err != nil {
		t.Fatalf("ReadRaw: %v", err)
	}
	if v != 12345 {
		t.Errorf("ReadRaw() = %v, want 12345", v)
	}
	if lastRead(conn.reads) != 25 {
		t.Errorf("last pulse count = %v, want 25", lastRead(conn.reads))
	}
}

func TestHX711FullInitDiscardsFirstReading(t *testing.T) {
	conn := newMockHX711Conn()
	conn.queueRead(0)
	if _, err := NewHX711Full(conn); err != nil {
		t.Fatalf("NewHX711Full: %v", err)
	}
	if len(conn.reads) != 1 || conn.reads[0] != 25 {
		t.Errorf("init reads = %v, want [25]", conn.reads)
	}
}

func newFullSensor(t *testing.T) (*mockHX711Conn, *HX711Full) {
	t.Helper()
	conn := newMockHX711Conn()
	conn.queueRead(0)
	d, err := NewHX711Full(conn)
	if err != nil {
		t.Fatalf("NewHX711Full: %v", err)
	}
	return conn, d
}

func TestHX711FullReadRawDefaultGain128(t *testing.T) {
	conn, d := newFullSensor(t)
	conn.queueRead(1000)
	v, err := d.ReadRaw()
	if err != nil {
		t.Fatalf("ReadRaw: %v", err)
	}
	if v != 1000 {
		t.Errorf("ReadRaw() = %v, want 1000", v)
	}
	if lastRead(conn.reads) != 25 {
		t.Errorf("last pulse count = %v, want 25", lastRead(conn.reads))
	}
}

func TestHX711FullSetGain(t *testing.T) {
	conn, d := newFullSensor(t)

	conn.queueRead(0) // dummy read issued by SetGain(64)
	if err := d.SetGain(HX711Gain64); err != nil {
		t.Fatalf("SetGain(64): %v", err)
	}
	if lastRead(conn.reads) != 27 {
		t.Errorf("SetGain(64) dummy read pulses = %v, want 27", lastRead(conn.reads))
	}
	conn.queueRead(2000)
	if v, err := d.ReadRaw(); err != nil || v != 2000 || lastRead(conn.reads) != 27 {
		t.Errorf("ReadRaw after SetGain(64) = %v, %v, pulses=%v", v, err, lastRead(conn.reads))
	}

	conn.queueRead(0) // dummy read issued by SetGain(32)
	if err := d.SetGain(HX711Gain32); err != nil {
		t.Fatalf("SetGain(32): %v", err)
	}
	if lastRead(conn.reads) != 26 {
		t.Errorf("SetGain(32) dummy read pulses = %v, want 26", lastRead(conn.reads))
	}
	conn.queueRead(3000)
	if v, err := d.ReadRaw(); err != nil || v != 3000 || lastRead(conn.reads) != 26 {
		t.Errorf("ReadRaw after SetGain(32) = %v, %v, pulses=%v", v, err, lastRead(conn.reads))
	}

	conn.queueRead(0) // dummy read issued by SetGain(128)
	if err := d.SetGain(HX711Gain128); err != nil {
		t.Fatalf("SetGain(128): %v", err)
	}
	if lastRead(conn.reads) != 25 {
		t.Errorf("SetGain(128) dummy read pulses = %v, want 25", lastRead(conn.reads))
	}
}

func TestHX711FullSetGainInvalid(t *testing.T) {
	_, d := newFullSensor(t)
	if err := d.SetGain(99); err == nil {
		t.Error("SetGain(99) = nil error, want an error")
	}
}

func TestHX711FullReadAverage(t *testing.T) {
	conn, d := newFullSensor(t)
	conn.queueRead(10)
	conn.queueRead(20)
	conn.queueRead(33)
	avg, err := d.ReadAverage(3)
	if err != nil {
		t.Fatalf("ReadAverage: %v", err)
	}
	if want := int32((10 + 20 + 33) / 3); avg != want {
		t.Errorf("ReadAverage(3) = %v, want %v", avg, want)
	}
}

func TestHX711FullTare(t *testing.T) {
	conn, d := newFullSensor(t)
	conn.queueRead(100)
	conn.queueRead(100)
	if err := d.Tare(2); err != nil {
		t.Fatalf("Tare: %v", err)
	}
	if d.GetOffset() != 100 {
		t.Errorf("GetOffset() = %v, want 100", d.GetOffset())
	}
}

func TestHX711FullSetScale(t *testing.T) {
	_, d := newFullSensor(t)
	d.SetScale(2.5)
	if d.GetScale() != 2.5 {
		t.Errorf("GetScale() = %v, want 2.5", d.GetScale())
	}
}

func TestHX711FullReadWeight(t *testing.T) {
	conn, d := newFullSensor(t)
	conn.queueRead(100)
	conn.queueRead(100)
	if err := d.Tare(2); err != nil {
		t.Fatalf("Tare: %v", err)
	}
	d.SetScale(2.5)
	conn.queueRead(350)
	weight, err := d.ReadWeight(1)
	if err != nil {
		t.Fatalf("ReadWeight: %v", err)
	}
	want := float32(350-100) / 2.5
	if weight != want {
		t.Errorf("ReadWeight(1) = %v, want %v", weight, want)
	}
}

func TestHX711FullPowerDownPowerUp(t *testing.T) {
	conn, d := newFullSensor(t)

	conn.queueRead(0) // dummy read issued by SetGain(64)
	if err := d.SetGain(HX711Gain64); err != nil {
		t.Fatalf("SetGain(64): %v", err)
	}

	if err := d.PowerDown(); err != nil {
		t.Fatalf("PowerDown: %v", err)
	}
	if lastPowerCall(conn.powerCalls) != "down" {
		t.Errorf("last power call = %q, want \"down\"", lastPowerCall(conn.powerCalls))
	}

	conn.queueRead(0) // discarded by PowerUp
	if err := d.PowerUp(); err != nil {
		t.Fatalf("PowerUp: %v", err)
	}
	if lastPowerCall(conn.powerCalls) != "up" {
		t.Errorf("last power call = %q, want \"up\"", lastPowerCall(conn.powerCalls))
	}
	if lastRead(conn.reads) != 25 {
		t.Errorf("PowerUp discard-read pulses = %v, want 25 (gain reset)", lastRead(conn.reads))
	}

	conn.queueRead(4242)
	if v, err := d.ReadRaw(); err != nil || v != 4242 || lastRead(conn.reads) != 25 {
		t.Errorf("ReadRaw after PowerUp = %v, %v, pulses=%v", v, err, lastRead(conn.reads))
	}
}
