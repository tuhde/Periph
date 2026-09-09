package humidity

import (
	"errors"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockDHTxxConn is an in-memory fake connection.DHTxxConn for unit tests —
// no hardware, no GPIO. DHTxx has no register map: the chip driver only
// ever calls Read() and gets back a raw 5-byte frame, or an error
// propagating straight through (e.g. a real connection's timeout/framing
// error). Preload frames with queueRead; preload an error to return
// instead with queueError. Each Read call pops the next queued item
// (falling back to 5 zero bytes if the queue is empty).
type mockDHTxxConn struct {
	frames []([]byte)
	errs   []error
}

func newMockDHTxxConn() *mockDHTxxConn {
	return &mockDHTxxConn{}
}

func (m *mockDHTxxConn) queueRead(frame []byte) {
	m.frames = append(m.frames, frame)
	m.errs = append(m.errs, nil)
}

func (m *mockDHTxxConn) queueError(err error) {
	m.frames = append(m.frames, nil)
	m.errs = append(m.errs, err)
}

func (m *mockDHTxxConn) Read() ([]byte, error) {
	if len(m.frames) == 0 {
		return make([]byte, 5), nil
	}
	frame, err := m.frames[0], m.errs[0]
	m.frames = m.frames[1:]
	m.errs = m.errs[1:]
	return frame, err
}

var _ connection.DHTxxConn = (*mockDHTxxConn)(nil)

func closeEnoughF32(a, b, eps float32) bool {
	d := a - b
	if d < 0 {
		d = -d
	}
	return d < eps
}

func TestDHT11MinimalDecodeDatasheetExample(t *testing.T) {
	conn := newMockDHTxxConn()
	conn.queueRead([]byte{0x35, 0x00, 0x18, 0x04, 0x51})
	d, err := NewDHT11Minimal(conn)
	if err != nil {
		t.Fatalf("NewDHT11Minimal: %v", err)
	}
	temp, hum, err := d.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if !closeEnoughF32(temp, 24.4, 0.001) || !closeEnoughF32(hum, 53.0, 0.001) {
		t.Errorf("Read() = %v, %v, want 24.4, 53.0", temp, hum)
	}
}

func TestDHT11MinimalDecodeNegativeTemperature(t *testing.T) {
	conn := newMockDHTxxConn()
	conn.queueRead([]byte{0x20, 0x00, 0x0A, 0x81, 0xAB})
	d, err := NewDHT11Minimal(conn)
	if err != nil {
		t.Fatalf("NewDHT11Minimal: %v", err)
	}
	temp, hum, err := d.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if !closeEnoughF32(temp, -10.1, 0.001) || !closeEnoughF32(hum, 32.0, 0.001) {
		t.Errorf("Read() = %v, %v, want -10.1, 32.0", temp, hum)
	}
}

func TestDHT11MinimalChecksumErrorReturnsError(t *testing.T) {
	conn := newMockDHTxxConn()
	conn.queueRead([]byte{0x35, 0x00, 0x18, 0x04, 0x00}) // bad checksum
	d, err := NewDHT11Minimal(conn)
	if err != nil {
		t.Fatalf("NewDHT11Minimal: %v", err)
	}
	if _, _, err := d.Read(); err == nil {
		t.Error("Read() with bad checksum = nil error, want an error")
	}
}

func TestDHT11MinimalShortFrameReturnsError(t *testing.T) {
	conn := newMockDHTxxConn()
	conn.queueRead([]byte{0x35, 0x00, 0x18}) // too short
	d, err := NewDHT11Minimal(conn)
	if err != nil {
		t.Fatalf("NewDHT11Minimal: %v", err)
	}
	if _, _, err := d.Read(); err == nil {
		t.Error("Read() with short frame = nil error, want an error")
	}
}

func TestDHT11MinimalIsReadyAlwaysTrue(t *testing.T) {
	conn := newMockDHTxxConn()
	d, err := NewDHT11Minimal(conn)
	if err != nil {
		t.Fatalf("NewDHT11Minimal: %v", err)
	}
	if !d.IsReady() {
		t.Error("IsReady() = false, want true")
	}
}

func newFullDHT11(t *testing.T) (*mockDHTxxConn, *DHT11Full) {
	t.Helper()
	conn := newMockDHTxxConn()
	d, err := NewDHT11Full(conn)
	if err != nil {
		t.Fatalf("NewDHT11Full: %v", err)
	}
	return conn, d
}

func TestDHT11FullReadTemperatureAndHumidity(t *testing.T) {
	conn, d := newFullDHT11(t)
	conn.queueRead([]byte{0x35, 0x00, 0x18, 0x04, 0x51})
	temp, err := d.ReadTemperature()
	if err != nil {
		t.Fatalf("ReadTemperature: %v", err)
	}
	if !closeEnoughF32(temp, 24.4, 0.001) {
		t.Errorf("ReadTemperature() = %v, want 24.4", temp)
	}

	conn.queueRead([]byte{0x35, 0x00, 0x18, 0x04, 0x51})
	hum, err := d.ReadHumidity()
	if err != nil {
		t.Fatalf("ReadHumidity: %v", err)
	}
	if !closeEnoughF32(hum, 53.0, 0.001) {
		t.Errorf("ReadHumidity() = %v, want 53.0", hum)
	}
}

func TestDHT11FullReadRaw(t *testing.T) {
	conn, d := newFullDHT11(t)
	conn.queueRead([]byte{0x35, 0x00, 0x18, 0x04, 0x51})
	raw, err := d.ReadRaw()
	if err != nil {
		t.Fatalf("ReadRaw: %v", err)
	}
	want := []byte{0x35, 0x00, 0x18, 0x04, 0x51}
	if string(raw) != string(want) {
		t.Errorf("ReadRaw() = % X, want % X", raw, want)
	}

	conn.queueRead([]byte{0x35, 0x00, 0x18, 0x04, 0x00}) // bad checksum
	if _, err := d.ReadRaw(); err == nil {
		t.Error("ReadRaw() with bad checksum = nil error, want an error")
	}
}

func TestDHT11FullReadRetrySucceeds(t *testing.T) {
	conn, d := newFullDHT11(t)
	conn.queueRead([]byte{0x35, 0x00, 0x18, 0x04, 0x00}) // bad checksum, attempt 1
	conn.queueRead([]byte{0x35, 0x00, 0x18, 0x04, 0x51}) // good, attempt 2
	temp, hum, err := d.ReadRetry(3)
	if err != nil {
		t.Fatalf("ReadRetry: %v", err)
	}
	if !closeEnoughF32(temp, 24.4, 0.001) || !closeEnoughF32(hum, 53.0, 0.001) {
		t.Errorf("ReadRetry() = %v, %v, want 24.4, 53.0", temp, hum)
	}
}

func TestDHT11FullReadRetryExhausted(t *testing.T) {
	conn, d := newFullDHT11(t)
	conn.queueRead([]byte{0x35, 0x00, 0x18, 0x04, 0x00})
	conn.queueRead([]byte{0x35, 0x00, 0x18, 0x04, 0x00})
	if _, _, err := d.ReadRetry(2); err == nil {
		t.Error("ReadRetry() exhausted = nil error, want an error")
	}
}

func TestDHT11FullReadRetryZeroUsesDefault(t *testing.T) {
	conn, d := newFullDHT11(t)
	conn.queueRead([]byte{0x35, 0x00, 0x18, 0x04, 0x00})
	conn.queueRead([]byte{0x35, 0x00, 0x18, 0x04, 0x00})
	conn.queueRead([]byte{0x35, 0x00, 0x18, 0x04, 0x51}) // 3rd attempt succeeds
	temp, _, err := d.ReadRetry(0)                       // 0 -> constructor default (3)
	if err != nil {
		t.Fatalf("ReadRetry(0): %v", err)
	}
	if !closeEnoughF32(temp, 24.4, 0.001) {
		t.Errorf("ReadRetry(0) = %v, want 24.4", temp)
	}
}

func TestDHT11FullReadRetryRecoversFromTransportError(t *testing.T) {
	// Unlike Python's read_retry (which only catches its checksum-specific
	// DHT11Error and lets a transport-level error propagate immediately -
	// see specs/humidity/dht11.md's "Retry ... on checksum error" wording),
	// Go's ReadRetry loop retries on ANY non-nil error from Read(), checksum
	// or transport alike, since it has no equivalent narrower error type to
	// distinguish them. A transient transport error should still be
	// recovered by a later successful attempt within maxRetries.
	conn, d := newFullDHT11(t)
	conn.queueError(errors.New("sensor timeout"))
	conn.queueRead([]byte{0x35, 0x00, 0x18, 0x04, 0x51})
	temp, hum, err := d.ReadRetry(3)
	if err != nil {
		t.Fatalf("ReadRetry: %v", err)
	}
	if !closeEnoughF32(temp, 24.4, 0.001) || !closeEnoughF32(hum, 53.0, 0.001) {
		t.Errorf("ReadRetry() = %v, %v, want 24.4, 53.0", temp, hum)
	}
}

func TestDHT11FullReadRetryExhaustedOnPersistentTransportError(t *testing.T) {
	conn, d := newFullDHT11(t)
	sentinel := errors.New("sensor timeout")
	conn.queueError(sentinel)
	conn.queueError(sentinel)
	if _, _, err := d.ReadRetry(2); err == nil {
		t.Error("ReadRetry() with persistent transport error = nil, want an error")
	}
}
