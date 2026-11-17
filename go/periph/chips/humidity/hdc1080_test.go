package humidity

import (
	"bytes"
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// hdc1080Mock is an in-memory fake connection.Connection: it logs writes and
// serves plain reads from a FIFO queue, optionally NACKing reads first.
type hdc1080Mock struct {
	writes    [][]byte
	readQueue [][]byte
	nackReads int
}

func (m *hdc1080Mock) queue(b ...byte) { m.readQueue = append(m.readQueue, b) }

func (m *hdc1080Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}

func (m *hdc1080Mock) Read(n int) ([]byte, error) {
	if m.nackReads > 0 {
		m.nackReads--
		return nil, errors.New("NACK")
	}
	out := make([]byte, n)
	if len(m.readQueue) > 0 {
		copy(out, m.readQueue[0])
		m.readQueue = m.readQueue[1:]
	}
	return out, nil
}

func (m *hdc1080Mock) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return make([]byte, n), nil
}
func (m *hdc1080Mock) Close() error                { return nil }
func (m *hdc1080Mock) Enable()                     {}
func (m *hdc1080Mock) Disable()                    {}
func (m *hdc1080Mock) IsEnabled() bool             { return true }
func (m *hdc1080Mock) IntPin() connection.InputPin { return nil }
func (m *hdc1080Mock) EnPin() connection.OutputPin { return nil }

func (m *hdc1080Mock) last() []byte { return m.writes[len(m.writes)-1] }

func hdc1080Near(a, b, tol float64) bool { return math.Abs(a-b) <= tol }

func TestHDC1080InitWritesDefaultConfig(t *testing.T) {
	m := &hdc1080Mock{}
	d := NewHDC1080Full(m)
	if len(m.writes) != 0 {
		t.Fatal("constructor touched the bus")
	}
	if err := d.Init(); err != nil || !bytes.Equal(m.last(), []byte{0x02, 0x10, 0x00}) {
		t.Fatalf("init wrote %v, err %v", m.last(), err)
	}
}

func TestHDC1080ReadSequence(t *testing.T) {
	m := &hdc1080Mock{}
	m.queue(0x66, 0x66, 0x80, 0x00)
	temp, rh, err := NewHDC1080Minimal(m).Read()
	if err != nil || len(m.writes) != 1 || !bytes.Equal(m.last(), []byte{0x00}) {
		t.Fatalf("writes %v err %v", m.writes, err)
	}
	if !hdc1080Near(float64(temp), float64(0x6666)/65536*165-40, 1e-3) || !hdc1080Near(float64(rh), 50, 1e-3) {
		t.Fatalf("got %v %v", temp, rh)
	}
}

func TestHDC1080ReadPollsWhileNacked(t *testing.T) {
	m := &hdc1080Mock{nackReads: 3}
	m.queue(0x66, 0x66, 0x80, 0x00)
	_, rh, err := NewHDC1080Minimal(m).Read()
	if err != nil || !hdc1080Near(float64(rh), 50, 1e-3) {
		t.Fatalf("rh %v err %v", rh, err)
	}
}

func TestHDC1080Timeout(t *testing.T) {
	m := &hdc1080Mock{nackReads: 1000}
	if _, _, err := NewHDC1080Minimal(m).Read(); err != ErrHDC1080Timeout {
		t.Fatalf("err %v", err)
	}
}

func TestHDC1080SingleMeasurements(t *testing.T) {
	m := &hdc1080Mock{}
	d := NewHDC1080Full(m)
	m.queue(0x80, 0x00)
	temp, _ := d.ReadTemperature()
	if !hdc1080Near(float64(temp), 42.5, 1e-3) || !bytes.Equal(m.writes[0], []byte{0x02, 0x00, 0x00}) || len(m.writes) != 2 {
		t.Fatalf("temp %v writes %v", temp, m.writes)
	}
	m.queue(0x80, 0x00)
	rh, _ := d.ReadHumidity()
	if !hdc1080Near(float64(rh), 50, 1e-3) || !bytes.Equal(m.last(), []byte{0x01}) {
		t.Fatalf("rh %v writes %v", rh, m.writes)
	}
}

func TestHDC1080ReadRaw(t *testing.T) {
	m := &hdc1080Mock{}
	m.queue(0x12, 0x34, 0x56, 0x78)
	rt, rr, err := NewHDC1080Full(m).ReadRaw()
	if err != nil || rt != 0x1234 || rr != 0x5678 {
		t.Fatalf("raw %x %x err %v", rt, rr, err)
	}
}

func TestHDC1080Resolution(t *testing.T) {
	m := &hdc1080Mock{}
	d := NewHDC1080Full(m)
	_ = d.SetResolution(11, 8)
	if !bytes.Equal(m.last(), []byte{0x02, 0x16, 0x00}) {
		t.Fatalf("wrote %v", m.last())
	}
	if !hdc1080Near(float64(d.ConversionTime()), 0.00365+0.0025, 1e-6) {
		t.Fatalf("conversion time %v", d.ConversionTime())
	}
	_ = d.SetResolution(14, 14)
	if !bytes.Equal(m.last(), []byte{0x02, 0x10, 0x00}) || !hdc1080Near(float64(d.ConversionTime()), 0.01285, 1e-6) {
		t.Fatalf("wrote %v, time %v", m.last(), d.ConversionTime())
	}
	if d.SetResolution(8, 14) != ErrHDC1080Resolution || d.SetResolution(14, 12) != ErrHDC1080Resolution {
		t.Fatal("expected resolution error")
	}
}

func TestHDC1080HeaterBatteryConfig(t *testing.T) {
	m := &hdc1080Mock{}
	d := NewHDC1080Full(m)
	_ = d.SetHeater(true)
	if !bytes.Equal(m.last(), []byte{0x02, 0x30, 0x00}) {
		t.Fatalf("wrote %v", m.last())
	}
	_ = d.SetHeater(false)
	if !bytes.Equal(m.last(), []byte{0x02, 0x10, 0x00}) {
		t.Fatalf("wrote %v", m.last())
	}
	m.queue(0x30, 0x00)
	if on, _ := d.IsHeaterOn(); !on {
		t.Fatal("heater should be on")
	}
	m.queue(0x18, 0x00)
	if low, _ := d.BatteryLow(); !low {
		t.Fatal("battery should be low")
	}
	m.queue(0x10, 0x00)
	if c, _ := d.ReadConfig(); c != 0x1000 {
		t.Fatalf("config %x", c)
	}
}

func TestHDC1080SoftwareReset(t *testing.T) {
	m := &hdc1080Mock{}
	d := NewHDC1080Full(m)
	_ = d.SetHeater(true)
	_ = d.SoftwareReset()
	n := len(m.writes)
	if !bytes.Equal(m.writes[n-2], []byte{0x02, 0x80, 0x00}) || !bytes.Equal(m.last(), []byte{0x02, 0x30, 0x00}) {
		t.Fatalf("writes %v", m.writes)
	}
}

func TestHDC1080SerialID(t *testing.T) {
	m := &hdc1080Mock{}
	m.queue(0x00, 0x12)
	m.queue(0x34, 0x56)
	m.queue(0x80, 0x00)
	id, _ := NewHDC1080Full(m).SerialID()
	if id != 0x12<<24|0x3456<<8|0x100 {
		t.Fatalf("id %x", id)
	}
}

func TestHDC1080IDs(t *testing.T) {
	m := &hdc1080Mock{}
	m.queue(0x54, 0x49)
	m.queue(0x10, 0x50)
	d := NewHDC1080Full(m)
	if v, _ := d.ManufacturerID(); v != 0x5449 {
		t.Fatalf("manufacturer %x", v)
	}
	if v, _ := d.DeviceID(); v != 0x1050 {
		t.Fatalf("device %x", v)
	}
}
