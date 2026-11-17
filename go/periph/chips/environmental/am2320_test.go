package environmental

import (
	"bytes"
	"errors"
	"math"
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// am2320Mock is an in-memory fake connection.Connection: it logs writes, can NACK the next
// writes and reads, and serves plain reads from a FIFO queue.
type am2320Mock struct {
	writes     [][]byte
	readQueue  [][]byte
	failWrites int
	failReads  int
}

func (m *am2320Mock) queue(b []byte) { m.readQueue = append(m.readQueue, b) }

func (m *am2320Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	if m.failWrites > 0 {
		m.failWrites--
		return errors.New("NACK")
	}
	return nil
}

func (m *am2320Mock) Read(n int) ([]byte, error) {
	if m.failReads > 0 {
		m.failReads--
		return nil, errors.New("NACK")
	}
	out := make([]byte, n)
	if len(m.readQueue) > 0 {
		copy(out, m.readQueue[0])
		m.readQueue = m.readQueue[1:]
	}
	return out, nil
}

func (m *am2320Mock) WriteRead(data []byte, n int) ([]byte, error) { return make([]byte, n), nil }
func (m *am2320Mock) Close() error                                 { return nil }
func (m *am2320Mock) Enable()                                      {}
func (m *am2320Mock) Disable()                                     {}
func (m *am2320Mock) IsEnabled() bool                              { return true }
func (m *am2320Mock) IntPin() connection.InputPin                  { return nil }
func (m *am2320Mock) EnPin() connection.OutputPin                  { return nil }

func am2320Frame(body ...byte) []byte {
	crc := AM2320CRC16(body)
	return append(append([]byte(nil), body...), byte(crc), byte(crc>>8))
}

func am2320ReadResp(data ...byte) []byte {
	return am2320Frame(append([]byte{0x03, byte(len(data))}, data...)...)
}

func newAM2320() (*am2320Mock, *AM2320Full) {
	m := &am2320Mock{}
	d := NewAM2320Full(m)
	d.minInterval = 0 // tests do not wait the 2 s between transactions
	return m, d
}

func am2320Close(a, b float32) bool { return math.Abs(float64(a-b)) < 1e-4 }

func TestAM2320CRCReference(t *testing.T) {
	if AM2320CRC16([]byte{0x03, 0x04, 0x01, 0xF4, 0x00, 0xFA}) != 0xA531 ||
		AM2320CRC16([]byte{0x03, 0x04, 0x03, 0x39, 0x01, 0x15}) != 0xFEE1 ||
		AM2320CRC16([]byte{0x10, 0x10, 0x02, 0x01, 0x02}) != 0x92C0 {
		t.Fatal("CRC-16 reference values")
	}
}

func TestAM2320ConstructorNoBusAccess(t *testing.T) {
	m, _ := newAM2320()
	if len(m.writes) != 0 {
		t.Fatal("constructor touched the bus")
	}
}

func TestAM2320Read(t *testing.T) {
	m, d := newAM2320()
	m.queue(am2320ReadResp(0x01, 0xF4, 0x00, 0xFA))
	temp, rh, err := d.Read()
	if err != nil || !am2320Close(rh, 50.0) || !am2320Close(temp, 25.0) {
		t.Fatalf("got %v %v %v", temp, rh, err)
	}
	if len(m.writes) != 2 || !bytes.Equal(m.writes[0], []byte{0x00}) || !bytes.Equal(m.writes[1], []byte{0x03, 0x00, 0x04}) {
		t.Fatalf("writes %v", m.writes)
	}
}

func TestAM2320DatasheetFrame(t *testing.T) {
	m, d := newAM2320()
	m.queue([]byte{0x03, 0x04, 0x03, 0x39, 0x01, 0x15, 0xE1, 0xFE})
	temp, rh, err := d.Read()
	if err != nil || !am2320Close(rh, 82.5) || !am2320Close(temp, 27.7) {
		t.Fatalf("got %v %v %v", temp, rh, err)
	}
}

func TestAM2320NegativeTemperature(t *testing.T) {
	m, d := newAM2320()
	m.queue(am2320ReadResp(0x01, 0xF4, 0x80, 0x65))
	temp, _, _ := d.Read()
	if !am2320Close(temp, -10.1) {
		t.Fatalf("got %v", temp)
	}
}

func TestAM2320WakeNackIgnored(t *testing.T) {
	m, d := newAM2320()
	m.failWrites = 1
	m.queue(am2320ReadResp(0x01, 0xF4, 0x00, 0xFA))
	if _, rh, err := d.Read(); err != nil || !am2320Close(rh, 50.0) {
		t.Fatalf("got %v %v", rh, err)
	}
}

func TestAM2320CRCRetry(t *testing.T) {
	bad := am2320ReadResp(0x01, 0xF4, 0x00, 0xFA)
	bad[len(bad)-1] ^= 0xFF
	m, d := newAM2320()
	m.queue(bad)
	m.queue(am2320ReadResp(0x01, 0xF4, 0x00, 0xFA))
	if _, rh, err := d.Read(); err != nil || !am2320Close(rh, 50.0) {
		t.Fatalf("retry: %v %v", rh, err)
	}
	if len(m.writes) != 4 {
		t.Fatalf("expected a second wake, writes %d", len(m.writes))
	}
	m, d = newAM2320()
	m.queue(bad)
	m.queue(bad)
	if _, _, err := d.Read(); !errors.Is(err, ErrAM2320CRC) {
		t.Fatalf("want CRC error, got %v", err)
	}
}

func TestAM2320BusErrorRetry(t *testing.T) {
	m, d := newAM2320()
	m.failReads = 1
	m.queue(am2320ReadResp(0x01, 0xF4, 0x00, 0xFA))
	if temp, _, err := d.Read(); err != nil || !am2320Close(temp, 25.0) {
		t.Fatalf("retry: %v %v", temp, err)
	}
	m, d = newAM2320()
	m.failReads = 2
	if _, _, err := d.Read(); err == nil {
		t.Fatal("expected a bus error")
	}
}

func TestAM2320ErrorResponse(t *testing.T) {
	m, d := newAM2320()
	m.queue([]byte{0x83, 0, 0, 0})
	_, _, err := d.Read()
	var se *AM2320Error
	if !errors.As(err, &se) || se.Code != 0x83 {
		t.Fatalf("want error response 0x83, got %v", err)
	}
	if len(m.writes) != 2 {
		t.Fatal("an error response must not be retried")
	}
}

func TestAM2320IntervalEnforced(t *testing.T) {
	m := &am2320Mock{}
	d := NewAM2320Minimal(m)
	d.minInterval = 50 * time.Millisecond
	m.queue(am2320ReadResp(0x01, 0xF4, 0x00, 0xFA))
	start := time.Now()
	if _, _, err := d.Read(); err != nil {
		t.Fatal(err)
	}
	if time.Since(start) < 40*time.Millisecond {
		t.Fatal("interval not enforced")
	}
}

func TestAM2320SingleValuesAndRaw(t *testing.T) {
	m, d := newAM2320()
	m.queue(am2320ReadResp(0x00, 0x7B))
	if rh, err := d.ReadHumidity(); err != nil || !am2320Close(rh, 12.3) {
		t.Fatalf("humidity %v %v", rh, err)
	}
	if !bytes.Equal(m.writes[len(m.writes)-1], []byte{0x03, 0x00, 0x02}) {
		t.Fatal("humidity command")
	}
	m.queue(am2320ReadResp(0x80, 0x65))
	if temp, err := d.ReadTemperature(); err != nil || !am2320Close(temp, -10.1) {
		t.Fatalf("temperature %v %v", temp, err)
	}
	if !bytes.Equal(m.writes[len(m.writes)-1], []byte{0x03, 0x02, 0x02}) {
		t.Fatal("temperature command")
	}
	m.queue(am2320ReadResp(0x01, 0xF4, 0x80, 0x65))
	if rh, rt, err := d.ReadRaw(); err != nil || rh != 0x01F4 || rt != 0x8065 {
		t.Fatalf("raw %v %v %v", rh, rt, err)
	}
}

func TestAM2320ReadFresh(t *testing.T) {
	m, d := newAM2320()
	m.queue(am2320ReadResp(0x01, 0xF4, 0x00, 0xFA))
	m.queue(am2320ReadResp(0x01, 0xF9, 0x01, 0x00))
	temp, rh, err := d.ReadFresh()
	if err != nil || !am2320Close(rh, 50.5) || !am2320Close(temp, 25.6) {
		t.Fatalf("got %v %v %v", temp, rh, err)
	}
}

func TestAM2320Registers(t *testing.T) {
	m, d := newAM2320()
	m.queue(am2320ReadResp(1, 2, 3))
	r, err := d.ReadRegisters(0x08, 3)
	if err != nil || !bytes.Equal(r, []byte{1, 2, 3}) {
		t.Fatalf("got %v %v", r, err)
	}
	if _, err := d.ReadRegisters(0, 0); !errors.Is(err, ErrAM2320Argument) {
		t.Fatal("count 0 must be rejected")
	}
	if _, err := d.ReadRegisters(0, 11); !errors.Is(err, ErrAM2320Argument) {
		t.Fatal("count 11 must be rejected")
	}
}

func TestAM2320DeviceInfo(t *testing.T) {
	m, d := newAM2320()
	m.queue(am2320ReadResp(0x09, 0x0F, 0x10, 0x12, 0x34, 0x56, 0x78))
	model, version, id, err := d.ReadDeviceInfo()
	if err != nil || model != 0x090F || version != 0x10 || id != 0x12345678 {
		t.Fatalf("got %v %v %v %v", model, version, id, err)
	}
}

func TestAM2320UserRegisters(t *testing.T) {
	m, d := newAM2320()
	m.queue(am2320ReadResp(0x12, 0x34, 0xAB, 0xCD))
	u1, u2, err := d.ReadUserRegisters()
	if err != nil || u1 != 0x1234 || u2 != 0xABCD {
		t.Fatalf("got %v %v %v", u1, u2, err)
	}
	m.queue(am2320Frame(0x10, 0x10, 0x04))
	if err := d.WriteUserRegisters(0x1234, 0xABCD); err != nil {
		t.Fatal(err)
	}
	want := am2320Frame(0x10, 0x10, 0x04, 0x12, 0x34, 0xAB, 0xCD)
	if !bytes.Equal(m.writes[len(m.writes)-1], want) {
		t.Fatalf("frame %v want %v", m.writes[len(m.writes)-1], want)
	}
}

func TestAM2320WriteRegisters(t *testing.T) {
	m, d := newAM2320()
	m.queue(am2320Frame(0x10, 0x10, 0x02))
	if err := d.WriteRegisters(0x10, []byte{1, 2}); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(m.writes[len(m.writes)-1], []byte{0x10, 0x10, 0x02, 1, 2, 0xC0, 0x92}) {
		t.Fatal("write frame")
	}
	for _, c := range []struct {
		start byte
		data  []byte
	}{{0x0F, []byte{1}}, {0x13, []byte{1, 2}}, {0x10, nil}} {
		if err := d.WriteRegisters(c.start, c.data); !errors.Is(err, ErrAM2320Argument) {
			t.Fatalf("start %#x len %d must be rejected", c.start, len(c.data))
		}
	}
	m.queue([]byte{0x82, 0, 0, 0, 0})
	var se *AM2320Error
	if err := d.WriteRegisters(0x10, []byte{1}); !errors.As(err, &se) || se.Code != 0x82 {
		t.Fatalf("want error response, got %v", err)
	}
}

func TestAM2320Wake(t *testing.T) {
	m, d := newAM2320()
	d.Wake()
	if len(m.writes) != 1 || !bytes.Equal(m.writes[0], []byte{0x00}) {
		t.Fatalf("writes %v", m.writes)
	}
}
