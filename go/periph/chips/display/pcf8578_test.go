package display

import (
	"bytes"
	"errors"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// pcf8578Mock is an in-memory fake connection.Connection for unit tests — no
// hardware, no bus. Every Write / WriteRead is logged to writes.
type pcf8578Mock struct {
	writes [][]byte
}

func (m *pcf8578Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}
func (m *pcf8578Mock) Read(n int) ([]byte, error) { return make([]byte, n), nil }
func (m *pcf8578Mock) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return make([]byte, n), nil
}
func (m *pcf8578Mock) Close() error                { return nil }
func (m *pcf8578Mock) Enable()                     {}
func (m *pcf8578Mock) Disable()                    {}
func (m *pcf8578Mock) IsEnabled() bool             { return true }
func (m *pcf8578Mock) IntPin() connection.InputPin { return nil }
func (m *pcf8578Mock) EnPin() connection.OutputPin { return nil }

func (m *pcf8578Mock) last() []byte { return m.writes[len(m.writes)-1] }

func pcf8578Bytes(head []byte, n int, fill byte) []byte {
	out := append([]byte(nil), head...)
	for i := 0; i < n; i++ {
		out = append(out, fill)
	}
	return out
}

func newPCF8578(t *testing.T) (*PCF8578Full, *pcf8578Mock) {
	t.Helper()
	conn := &pcf8578Mock{}
	lcd, err := NewPCF8578Full(conn, 16)
	if err != nil {
		t.Fatalf("NewPCF8578Full: %v", err)
	}
	return lcd, conn
}

func expectLast(t *testing.T, m *pcf8578Mock, want []byte, label string) {
	t.Helper()
	if !bytes.Equal(m.last(), want) {
		t.Errorf("%s = % X, want % X", label, m.last(), want)
	}
}

func TestPCF8578Init(t *testing.T) {
	_, conn := newPCF8578(t)
	if want := pcf8578Bytes([]byte{0xE0, 0xD2, 0xFC, 0xF8, 0x00}, 160, 0); !bytes.Equal(conn.writes[0], want) {
		t.Errorf("init clear = % X, want % X", conn.writes[0], want)
	}
	if !bytes.Equal(conn.writes[1], []byte{0x56}) {
		t.Errorf("init display on = % X, want [56]", conn.writes[1])
	}
	for rows, want := range map[int]byte{8: 0x55, 24: 0x57, 32: 0x54} {
		c := &pcf8578Mock{}
		if _, err := NewPCF8578Minimal(c, rows); err != nil {
			t.Fatal(err)
		}
		if c.writes[1][0] != want {
			t.Errorf("rows %d display on = %02X, want %02X", rows, c.writes[1][0], want)
		}
	}
	if _, err := NewPCF8578Minimal(&pcf8578Mock{}, 12); !errors.Is(err, ErrPCF8578InvalidArgument) {
		t.Errorf("rows 12 err = %v, want invalid argument", err)
	}
}

func TestPCF8578Pixels(t *testing.T) {
	lcd, conn := newPCF8578(t)
	if err := lcd.Clear(); err != nil {
		t.Fatal(err)
	}
	expectLast(t, conn, pcf8578Bytes([]byte{0xE0, 0xF8, 0x00}, 160, 0), "clear")

	lcd.SetPixel(0, 0, true)
	expectLast(t, conn, []byte{0xE0, 0xF0, 16, 0x01}, "set_pixel")
	lcd.SetPixel(3, 9, true)
	expectLast(t, conn, []byte{0xE0, 0xF1, 19, 0x02}, "set_pixel bank 1")
	lcd.SetPixel(3, 10, true)
	expectLast(t, conn, []byte{0xE0, 0xF1, 19, 0x06}, "set_pixel shadow OR")
	lcd.SetPixel(3, 9, false)
	expectLast(t, conn, []byte{0xE0, 0xF1, 19, 0x04}, "set_pixel clear")

	n := len(conn.writes)
	lcd.SetPixel(24, 0, true)
	lcd.SetPixel(0, 16, true)
	lcd.SetPixel(-1, 0, true)
	if len(conn.writes) != n {
		t.Error("out-of-range pixels must not touch the bus")
	}
	if !lcd.GetPixel(0, 0) || lcd.GetPixel(3, 9) || len(conn.writes) != n {
		t.Error("GetPixel must read the shadow without bus traffic")
	}

	lcd.WriteColumns(22, 0, []byte{1, 2, 3, 4})
	expectLast(t, conn, []byte{0xE0, 0xF0, 38, 1, 2}, "write_columns truncated")
	if err := lcd.WriteColumns(0, 2, []byte{1}); !errors.Is(err, ErrPCF8578InvalidArgument) {
		t.Errorf("bad bank err = %v", err)
	}
}

func TestPCF8578Drawing(t *testing.T) {
	lcd, conn := newPCF8578(t)
	lcd.Fill(true)
	if !bytes.Equal(conn.writes[len(conn.writes)-2], pcf8578Bytes([]byte{0xE0, 0xF0, 16}, 24, 0xFF)) ||
		!bytes.Equal(conn.last(), pcf8578Bytes([]byte{0xE0, 0xF1, 16}, 24, 0xFF)) {
		t.Error("fill writes every displayed column of each bank in use")
	}
	lcd.Fill(false)
	if lcd.GetPixel(5, 5) {
		t.Error("fill(false) must clear the shadow")
	}

	lcd.Clear()
	n := len(conn.writes)
	lcd.DrawLine(0, 0, 3, 0, true)
	if len(conn.writes) != n+1 {
		t.Errorf("draw_line wrote %d transactions, want 1", len(conn.writes)-n)
	}
	expectLast(t, conn, []byte{0xE0, 0xF0, 16, 1, 1, 1, 1}, "draw_line")

	lcd.Clear()
	lcd.DrawRect(0, 0, 3, 3, true, false)
	if !lcd.GetPixel(0, 0) || !lcd.GetPixel(2, 2) || lcd.GetPixel(1, 1) {
		t.Error("outline rectangle wrong")
	}
	lcd.DrawRect(0, 0, 3, 3, true, true)
	if !lcd.GetPixel(1, 1) {
		t.Error("filled rectangle wrong")
	}

	lcd.ShowBitmap(2, 8, [][]byte{{0xAA, 0xBB}})
	expectLast(t, conn, []byte{0xE0, 0xF1, 18, 0xAA, 0xBB}, "show_bitmap")
	if err := lcd.ShowBitmap(0, 3, [][]byte{{1}}); !errors.Is(err, ErrPCF8578InvalidArgument) {
		t.Errorf("unaligned bitmap err = %v", err)
	}
}

func TestPCF8578Configuration(t *testing.T) {
	lcd, conn := newPCF8578(t)
	for status, want := range map[uint8]byte{PCF8578DisplayBlank: 0x52, PCF8578DisplayInverse: 0x5E, PCF8578DisplayAllOn: 0x5A, PCF8578DisplayNormal: 0x56} {
		lcd.SetDisplay(status)
		expectLast(t, conn, []byte{want}, "set_display")
	}
	lcd.SetStartBank(1)
	expectLast(t, conn, []byte{0x7D}, "set_start_bank")
	lcd.Scroll(1)
	expectLast(t, conn, []byte{0x7C}, "scroll wraps")
	lcd.Scroll(-1)
	expectLast(t, conn, []byte{0x7D}, "scroll negative")
	if err := lcd.SetStartBank(4); !errors.Is(err, ErrPCF8578InvalidArgument) {
		t.Errorf("start bank 4 err = %v", err)
	}

	lcd.DeviceSelect(5)
	expectLast(t, conn, []byte{0x65}, "device_select")
	if err := lcd.DeviceSelect(16); !errors.Is(err, ErrPCF8578InvalidArgument) {
		t.Errorf("device_select 16 err = %v", err)
	}
	lcd.SetPixel(0, 0, true)
	if conn.last()[0] != 0xE0 {
		t.Error("pixel access must restore subaddress 0")
	}

	lcd.SetMode(24, true)
	expectLast(t, conn, []byte{0x57}, "set_mode 24")
	lcd.SetMode(16, false)
	expectLast(t, conn, []byte{0x46}, "set_mode row")
	if err := lcd.SetPixel(0, 0, true); !errors.Is(err, ErrPCF8578RowMode) {
		t.Errorf("row mode pixel err = %v", err)
	}
	if err := lcd.WriteRAM(0, 0, []byte{1}, PCF8578AccessCharacter); !errors.Is(err, ErrPCF8578RowMode) {
		t.Errorf("row mode write_ram err = %v", err)
	}
	lcd.SetMode(16, true)

	lcd.Reset()
	if !bytes.Equal(conn.writes[len(conn.writes)-2], pcf8578Bytes([]byte{0xE0, 0xD2, 0xFC, 0xF8, 0x00}, 160, 0)) ||
		!bytes.Equal(conn.last(), []byte{0x56}) {
		t.Error("reset must re-run init")
	}
}

func TestPCF8578RAM(t *testing.T) {
	lcd, conn := newPCF8578(t)
	lcd.WriteRAM(10, 0, []byte{1, 2}, PCF8578AccessCharacter)
	expectLast(t, conn, []byte{0xE0, 0xF0, 10, 1, 2}, "write_ram character")
	lcd.WriteRAM(10, 2, []byte{1, 2, 3, 4}, PCF8578AccessHalfGraphic)
	expectLast(t, conn, []byte{0xE0, 0xF6, 10, 1, 2, 3, 4}, "write_ram half")
	lcd.WriteRAM(10, 0, []byte{1, 2, 3, 4, 5, 6, 7, 8}, PCF8578AccessFullGraphic)
	expectLast(t, conn, []byte{0xE0, 0xF8, 10, 1, 2, 3, 4, 5, 6, 7, 8}, "write_ram full")
	if lcd.shadow[3][10] != 4 || lcd.shadow[0][11] != 5 {
		t.Error("write_ram shadow order wrong")
	}
	for _, c := range []struct {
		x, bank int
		data    []byte
		mode    uint8
	}{{39, 0, []byte{0, 0}, 0}, {0, 4, []byte{1}, 0}, {0, 1, []byte{1}, 1}} {
		if err := lcd.WriteRAM(c.x, c.bank, c.data, c.mode); !errors.Is(err, ErrPCF8578InvalidArgument) {
			t.Errorf("WriteRAM(%d,%d) err = %v", c.x, c.bank, err)
		}
	}

	data, err := lcd.ReadRAM(16, 0, 4, PCF8578AccessCharacter)
	if err != nil || len(data) != 4 {
		t.Fatalf("ReadRAM: %v len %d", err, len(data))
	}
	expectLast(t, conn, []byte{0xE0, 0xF0, 16}, "read_ram commands")
	lcd.ReadRAM(5, 2, 3, PCF8578AccessHalfGraphic)
	expectLast(t, conn, []byte{0xE0, 0xF6, 5}, "read_ram half commands")
}
