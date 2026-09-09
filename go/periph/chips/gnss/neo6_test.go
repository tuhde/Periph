package gnss

import (
	"strings"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockNeo6Conn is an in-memory fake connection.Connection for unit tests —
// no hardware, no bus. NEO6Minimal/NEO6Full treat UART, I2C (DDC), and SPI
// as the same underlying NMEA/UBX byte stream (Read(1) for UART,
// WriteRead([]byte{0xFF},1) for I2C, WriteRead(nil,1) for SPI - see
// neo6.go's readByte doc comment), so this mock is transport-shape
// agnostic: preload the stream with queueBytes; Read and WriteRead both
// just pop the next byte(s) off the front of one shared FIFO, regardless
// of the prefix passed to WriteRead - matching the real module, where all
// three transports deliver the same bytes and only the framing differs.
// Write calls (e.g. SendUBX) are logged to writes for assertions.
type mockNeo6Conn struct {
	stream []byte
	writes [][]byte
}

func newMockNeo6Conn() *mockNeo6Conn {
	return &mockNeo6Conn{}
}

func (m *mockNeo6Conn) queueBytes(data []byte) {
	m.stream = append(m.stream, data...)
}

func (m *mockNeo6Conn) Read(n int) ([]byte, error) {
	if n > len(m.stream) {
		n = len(m.stream)
	}
	out := m.stream[:n]
	m.stream = m.stream[n:]
	return out, nil
}

func (m *mockNeo6Conn) WriteRead(data []byte, n int) ([]byte, error) {
	return m.Read(n)
}

func (m *mockNeo6Conn) Write(data []byte) error {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	return nil
}

func (m *mockNeo6Conn) Close() error                { return nil }
func (m *mockNeo6Conn) Enable()                     {}
func (m *mockNeo6Conn) Disable()                    {}
func (m *mockNeo6Conn) IsEnabled() bool             { return true }
func (m *mockNeo6Conn) IntPin() connection.InputPin { return nil }
func (m *mockNeo6Conn) EnPin() connection.OutputPin { return nil }

var _ connection.Connection = (*mockNeo6Conn)(nil)

func lastWrite(writes [][]byte) []byte {
	if len(writes) == 0 {
		return nil
	}
	return writes[len(writes)-1]
}

// nmeaSentence builds a $<body>*XX\r\n NMEA sentence with a correct XOR
// checksum, body given as pre-joined comma-separated fields (e.g.
// "GPGGA,092750.000,...").
func nmeaSentence(body string) []byte {
	var cs byte
	for i := 0; i < len(body); i++ {
		cs ^= body[i]
	}
	return []byte("$" + body + "*" + hexByte(cs) + "\r\n")
}

func hexByte(b byte) string {
	const hexDigits = "0123456789ABCDEF"
	return string([]byte{hexDigits[b>>4], hexDigits[b&0x0F]})
}

// ubxFrame builds a UBX frame with a correct Fletcher checksum, verified
// independently of neo6.go's own ubxChecksum.
func ubxFrame(msgClass, msgID byte, payload []byte) []byte {
	length := len(payload)
	body := []byte{msgClass, msgID, byte(length & 0xFF), byte((length >> 8) & 0xFF)}
	body = append(body, payload...)
	var ckA, ckB byte
	for _, b := range body {
		ckA += b
		ckB += ckA
	}
	frame := []byte{0xB5, 0x62}
	frame = append(frame, body...)
	frame = append(frame, ckA, ckB)
	return frame
}

// feed queues data and drives Update() enough times to consume it all,
// returning true if any call reported a parsed GGA fix.
func feed(t *testing.T, update func() (bool, error), conn *mockNeo6Conn, data []byte) bool {
	t.Helper()
	conn.queueBytes(data)
	gotFix := false
	for i := 0; i < len(data); i++ {
		ok, err := update()
		if err != nil {
			t.Fatalf("Update: %v", err)
		}
		if ok {
			gotFix = true
		}
	}
	return gotFix
}

func closeEnough(a, b, eps float64) bool {
	d := a - b
	if d < 0 {
		d = -d
	}
	return d < eps
}

var (
	ggaFix = strings.Join([]string{
		"GPGGA", "092750.000", "5321.6802", "N", "00630.3372", "W",
		"1", "08", "1.03", "61.7", "M", "55.2", "M", "", "",
	}, ",")
	ggaNoFix = strings.Join([]string{
		"GPGGA", "092750.000", "", "", "", "",
		"0", "00", "", "", "", "", "", "", "",
	}, ",")
	rmc = strings.Join([]string{
		"GPRMC", "092750.000", "A", "5321.6802", "N", "00630.3372", "W",
		"022.4", "084.4", "230394", "003.1", "W", "A",
	}, ",")
	vtg = strings.Join([]string{
		"GPVTG", "084.4", "T", "077.4", "M", "022.4", "N", "041.5", "K", "A",
	}, ",")
)

func TestNEO6MinimalGGADecodeAcrossBusTypes(t *testing.T) {
	for _, busType := range []string{"uart", "i2c", "spi"} {
		t.Run(busType, func(t *testing.T) {
			conn := newMockNeo6Conn()
			d := NewNEO6Minimal(conn, busType)
			if d.Fix() != 0 {
				t.Errorf("Fix() = %v, want 0", d.Fix())
			}
			if d.Latitude() != nil {
				t.Errorf("Latitude() = %v, want nil", d.Latitude())
			}

			gotFix := feed(t, d.Update, conn, nmeaSentence(ggaFix))
			if !gotFix {
				t.Fatal("Update() never reported a fix")
			}
			if d.Fix() != 1 {
				t.Errorf("Fix() = %v, want 1", d.Fix())
			}
			if d.Satellites() != 8 {
				t.Errorf("Satellites() = %v, want 8", d.Satellites())
			}
			if d.Latitude() == nil || !closeEnough(*d.Latitude(), 53.361336667, 1e-6) {
				t.Errorf("Latitude() = %v, want ~53.361336667", d.Latitude())
			}
			if d.Longitude() == nil || !closeEnough(*d.Longitude(), -6.505620, 1e-6) {
				t.Errorf("Longitude() = %v, want ~-6.505620", d.Longitude())
			}
			if d.Altitude() == nil || !closeEnough(*d.Altitude(), 61.7, 1e-6) {
				t.Errorf("Altitude() = %v, want ~61.7", d.Altitude())
			}
		})
	}
}

func TestNEO6MinimalNoFixKeepsLastPosition(t *testing.T) {
	conn := newMockNeo6Conn()
	d := NewNEO6Minimal(conn, "uart")
	feed(t, d.Update, conn, nmeaSentence(ggaFix))
	gotFix := feed(t, d.Update, conn, nmeaSentence(ggaNoFix))
	if gotFix {
		t.Error("Update() reported a fix for a no-fix sentence")
	}
	if d.Fix() != 0 {
		t.Errorf("Fix() = %v, want 0", d.Fix())
	}
	if d.Latitude() == nil || !closeEnough(*d.Latitude(), 53.361336667, 1e-6) {
		t.Errorf("Latitude() = %v, want the last fixed value to persist", d.Latitude())
	}
}

func TestNEO6MinimalBadChecksumDiscarded(t *testing.T) {
	conn := newMockNeo6Conn()
	d := NewNEO6Minimal(conn, "uart")
	bad := nmeaSentence(ggaFix)
	bad[len(bad)-4] ^= 0xFF // corrupt one checksum hex digit
	gotFix := feed(t, d.Update, conn, bad)
	if gotFix {
		t.Error("Update() reported a fix for a corrupted sentence")
	}
	if d.Fix() != 0 {
		t.Errorf("Fix() = %v, want 0", d.Fix())
	}
}

func TestNEO6MinimalLeadingGarbageIgnored(t *testing.T) {
	conn := newMockNeo6Conn()
	d := NewNEO6Minimal(conn, "uart")
	data := append([]byte{0xFF, 0xFF, 0xFF}, nmeaSentence(ggaFix)...)
	if !feed(t, d.Update, conn, data) {
		t.Error("Update() never reported a fix after leading garbage")
	}
}

func TestNEO6FullRMC(t *testing.T) {
	conn := newMockNeo6Conn()
	d := NewNEO6Full(conn, "uart")
	feed(t, d.Update, conn, nmeaSentence(rmc))
	if d.Speed() == nil || !closeEnough(*d.Speed(), 22.4*0.514444, 1e-4) {
		t.Errorf("Speed() = %v, want ~%v", d.Speed(), 22.4*0.514444)
	}
	if d.Course() == nil || !closeEnough(*d.Course(), 84.4, 1e-6) {
		t.Errorf("Course() = %v, want 84.4", d.Course())
	}
	if d.UTCTime() != "092750.000" {
		t.Errorf("UTCTime() = %q, want %q", d.UTCTime(), "092750.000")
	}
	if d.UTCDate() != "230394" {
		t.Errorf("UTCDate() = %q, want %q", d.UTCDate(), "230394")
	}
}

func TestNEO6FullVTG(t *testing.T) {
	conn := newMockNeo6Conn()
	d := NewNEO6Full(conn, "uart")
	feed(t, d.Update, conn, nmeaSentence(vtg))
	if d.Course() == nil || !closeEnough(*d.Course(), 84.4, 1e-6) {
		t.Errorf("Course() = %v, want 84.4", d.Course())
	}
	if d.Speed() == nil || !closeEnough(*d.Speed(), 41.5/3.6, 1e-4) {
		t.Errorf("Speed() = %v, want ~%v", d.Speed(), 41.5/3.6)
	}
}

func TestNEO6FullHDOP(t *testing.T) {
	conn := newMockNeo6Conn()
	d := NewNEO6Full(conn, "uart")
	feed(t, d.Update, conn, nmeaSentence(ggaFix))
	if d.HDOP() == nil || !closeEnough(*d.HDOP(), 1.03, 1e-6) {
		t.Errorf("HDOP() = %v, want 1.03", d.HDOP())
	}
}

func TestNEO6FullSendUBX(t *testing.T) {
	conn := newMockNeo6Conn()
	d := NewNEO6Full(conn, "uart")
	payload := []byte{1, 2, 3}
	if err := d.SendUBX(0x06, 0x08, payload); err != nil {
		t.Fatalf("SendUBX: %v", err)
	}
	want := ubxFrame(0x06, 0x08, payload)
	if string(lastWrite(conn.writes)) != string(want) {
		t.Errorf("SendUBX write = % X, want % X", lastWrite(conn.writes), want)
	}
}

func TestNEO6FullSetRate(t *testing.T) {
	conn := newMockNeo6Conn()
	d := NewNEO6Full(conn, "uart")
	if err := d.SetRate(5); err != nil {
		t.Fatalf("SetRate: %v", err)
	}
	measRate := uint16(1000 / 5)
	want := ubxFrame(0x06, 0x08, []byte{byte(measRate & 0xFF), byte((measRate >> 8) & 0xFF), 1, 0, 0, 0})
	if string(lastWrite(conn.writes)) != string(want) {
		t.Errorf("SetRate write = % X, want % X", lastWrite(conn.writes), want)
	}
}

func TestNEO6FullSetPlatform(t *testing.T) {
	conn := newMockNeo6Conn()
	d := NewNEO6Full(conn, "uart")
	if err := d.SetPlatform(4); err != nil {
		t.Fatalf("SetPlatform: %v", err)
	}
	payload := make([]byte, 36)
	payload[0] = 0x01
	payload[2] = 4
	want := ubxFrame(0x06, 0x24, payload)
	if string(lastWrite(conn.writes)) != string(want) {
		t.Errorf("SetPlatform write = % X, want % X", lastWrite(conn.writes), want)
	}
}

func TestNEO6FullColdStart(t *testing.T) {
	conn := newMockNeo6Conn()
	d := NewNEO6Full(conn, "uart")
	if err := d.ColdStart(); err != nil {
		t.Fatalf("ColdStart: %v", err)
	}
	want := ubxFrame(0x06, 0x04, []byte{0xFF, 0xFF, 0x02, 0x00})
	if string(lastWrite(conn.writes)) != string(want) {
		t.Errorf("ColdStart write = % X, want % X", lastWrite(conn.writes), want)
	}
}

func TestNEO6FullSaveConfig(t *testing.T) {
	conn := newMockNeo6Conn()
	d := NewNEO6Full(conn, "uart")
	if err := d.SaveConfig(); err != nil {
		t.Fatalf("SaveConfig: %v", err)
	}
	want := ubxFrame(0x06, 0x09, []byte{0, 0, 0, 0, 0xFF, 0xFF, 0xFF, 0xFF, 0, 0, 0, 0, 0x07})
	if string(lastWrite(conn.writes)) != string(want) {
		t.Errorf("SaveConfig write = % X, want % X", lastWrite(conn.writes), want)
	}
}

func TestNEO6FullPollUBXReturnsPayload(t *testing.T) {
	conn := newMockNeo6Conn()
	d := NewNEO6Full(conn, "uart")
	payload := make([]byte, 28)
	for i := range payload {
		payload[i] = byte(i)
	}
	conn.queueBytes(ubxFrame(0x01, 0x02, payload))
	got, err := d.PollUBX(0x01, 0x02)
	if err != nil {
		t.Fatalf("PollUBX: %v", err)
	}
	if string(got) != string(payload) {
		t.Errorf("PollUBX() = % X, want % X", got, payload)
	}
	wantPoll := ubxFrame(0x01, 0x02, nil)
	if string(conn.writes[0]) != string(wantPoll) {
		t.Errorf("PollUBX poll write = % X, want % X", conn.writes[0], wantPoll)
	}
}

func TestNEO6FullPollUBXNakReturnsError(t *testing.T) {
	conn := newMockNeo6Conn()
	d := NewNEO6Full(conn, "uart")
	conn.queueBytes(ubxFrame(0x05, 0x00, []byte{0x06, 0x08})) // ACK-NAK for CFG-RATE
	if _, err := d.PollUBX(0x06, 0x08); err == nil {
		t.Error("PollUBX() with ACK-NAK = nil error, want an error")
	}
}
