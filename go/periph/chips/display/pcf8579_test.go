package display

import (
	"bytes"
	"errors"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// pcf8579Mock is an in-memory fake connection.Connection for unit tests — no
// hardware, no bus. Every Write / WriteRead is logged to writes.
type pcf8579Mock struct {
	writes [][]byte
}

func (m *pcf8579Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}
func (m *pcf8579Mock) Read(n int) ([]byte, error) { return make([]byte, n), nil }
func (m *pcf8579Mock) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return make([]byte, n), nil
}
func (m *pcf8579Mock) Close() error                { return nil }
func (m *pcf8579Mock) Enable()                     {}
func (m *pcf8579Mock) Disable()                    {}
func (m *pcf8579Mock) IsEnabled() bool             { return true }
func (m *pcf8579Mock) IntPin() connection.InputPin { return nil }
func (m *pcf8579Mock) EnPin() connection.OutputPin { return nil }

func (m *pcf8579Mock) last() []byte { return m.writes[len(m.writes)-1] }

func pcf8579Bytes(head []byte, n int, fill byte) []byte {
	out := append([]byte(nil), head...)
	for i := 0; i < n; i++ {
		out = append(out, fill)
	}
	return out
}

func newPCF8579(t *testing.T) (*PCF8579Full, *pcf8579Mock) {
	t.Helper()
	conn := &pcf8579Mock{}
	lcd, err := NewPCF8579Full(conn, 0, 16, false)
	if err != nil {
		t.Fatalf("NewPCF8579Full: %v", err)
	}
	return lcd, conn
}

func pcf8579ExpectLast(t *testing.T, m *pcf8579Mock, want []byte, label string) {
	t.Helper()
	if !bytes.Equal(m.last(), want) {
		t.Errorf("%s = % X, want % X", label, m.last(), want)
	}
}

func TestPCF8579Init(t *testing.T) {
	_, conn := newPCF8579(t)
	if want := pcf8579Bytes([]byte{0xE0, 0xC2, 0xFC, 0xF8, 0x00}, 160, 0); !bytes.Equal(conn.writes[0], want) {
		t.Errorf("init clear = % X, want % X", conn.writes[0], want)
	}
	if !bytes.Equal(conn.writes[1], []byte{0x46}) {
		t.Errorf("init display on = % X, want [46]", conn.writes[1])
	}
	sub := &pcf8579Mock{}
	if _, err := NewPCF8579Minimal(sub, 3, 16, true); err != nil {
		t.Fatal(err)
	}
	if want := pcf8579Bytes([]byte{0xE3, 0xD2, 0xFC, 0xF8, 0x00}, 160, 0); !bytes.Equal(sub.writes[0], want) || sub.writes[1][0] != 0x56 {
		t.Errorf("subaddress/mixed init = % X / % X", sub.writes[0][:5], sub.writes[1])
	}
	for rows, want := range map[int]byte{8: 0x45, 24: 0x47, 32: 0x44} {
		c := &pcf8579Mock{}
		if _, err := NewPCF8579Minimal(c, 0, rows, false); err != nil {
			t.Fatal(err)
		}
		if c.writes[1][0] != want {
			t.Errorf("rows %d display on = %02X, want %02X", rows, c.writes[1][0], want)
		}
	}
	if _, err := NewPCF8579Minimal(&pcf8579Mock{}, 0, 12, false); !errors.Is(err, ErrPCF8579InvalidArgument) {
		t.Errorf("rows 12 err = %v, want invalid argument", err)
	}
	if _, err := NewPCF8579Minimal(&pcf8579Mock{}, 16, 16, false); !errors.Is(err, ErrPCF8579InvalidArgument) {
		t.Errorf("subaddress 16 err = %v, want invalid argument", err)
	}
}

func TestPCF8579Pixels(t *testing.T) {
	lcd, conn := newPCF8579(t)
	if err := lcd.Clear(); err != nil {
		t.Fatal(err)
	}
	pcf8579ExpectLast(t, conn, pcf8579Bytes([]byte{0xE0, 0xF8, 0x00}, 160, 0), "clear")

	lcd.SetPixel(0, 0, true)
	pcf8579ExpectLast(t, conn, []byte{0xE0, 0xF0, 0, 0x01}, "set_pixel")
	lcd.SetPixel(3, 9, true)
	pcf8579ExpectLast(t, conn, []byte{0xE0, 0xF1, 3, 0x02}, "set_pixel bank 1")
	lcd.SetPixel(3, 10, true)
	pcf8579ExpectLast(t, conn, []byte{0xE0, 0xF1, 3, 0x06}, "set_pixel shadow OR")
	lcd.SetPixel(3, 9, false)
	pcf8579ExpectLast(t, conn, []byte{0xE0, 0xF1, 3, 0x04}, "set_pixel clear")

	lcd.SetPixel(39, 15, true)
	pcf8579ExpectLast(t, conn, []byte{0xE0, 0xF1, 39, 0x80}, "set_pixel last column")

	n := len(conn.writes)
	lcd.SetPixel(40, 0, true)
	lcd.SetPixel(0, 16, true)
	lcd.SetPixel(-1, 0, true)
	if len(conn.writes) != n {
		t.Error("out-of-range pixels must not touch the bus")
	}
	if !lcd.GetPixel(0, 0) || lcd.GetPixel(3, 9) || len(conn.writes) != n {
		t.Error("GetPixel must read the shadow without bus traffic")
	}

	lcd.WriteColumns(38, 0, []byte{1, 2, 3, 4})
	pcf8579ExpectLast(t, conn, []byte{0xE0, 0xF0, 38, 1, 2}, "write_columns truncated")
	if err := lcd.WriteColumns(0, 2, []byte{1}); !errors.Is(err, ErrPCF8579InvalidArgument) {
		t.Errorf("bad bank err = %v", err)
	}
}

func TestPCF8579Drawing(t *testing.T) {
	lcd, conn := newPCF8579(t)
	lcd.Fill(true)
	if !bytes.Equal(conn.writes[len(conn.writes)-2], pcf8579Bytes([]byte{0xE0, 0xF0, 0}, 40, 0xFF)) ||
		!bytes.Equal(conn.last(), pcf8579Bytes([]byte{0xE0, 0xF1, 0}, 40, 0xFF)) {
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
	pcf8579ExpectLast(t, conn, []byte{0xE0, 0xF0, 0, 1, 1, 1, 1}, "draw_line")

	lcd.Clear()
	lcd.DrawRect(0, 0, 3, 3, true, false)
	if !lcd.GetPixel(0, 0) || !lcd.GetPixel(2, 2) || lcd.GetPixel(1, 1) {
		t.Error("outline rectangle wrong")
	}
	lcd.DrawRect(0, 0, 3, 3, true, true)
	if !lcd.GetPixel(1, 1) {
		t.Error("filled rectangle wrong")
	}

	lcd.ShowBitmap(2, 8, []byte{0xAA, 0xBB})
	pcf8579ExpectLast(t, conn, []byte{0xE0, 0xF1, 2, 0xAA, 0xBB}, "show_bitmap")
	if err := lcd.ShowBitmap(0, 3, []byte{1}); !errors.Is(err, ErrPCF8579InvalidArgument) {
		t.Errorf("unaligned bitmap err = %v", err)
	}
}

func TestPCF8579Configuration(t *testing.T) {
	lcd, conn := newPCF8579(t)
	for status, want := range map[uint8]byte{PCF8579DisplayBlank: 0x42, PCF8579DisplayInverse: 0x4E, PCF8579DisplayAllOn: 0x4A, PCF8579DisplayNormal: 0x46} {
		lcd.SetDisplay(status)
		pcf8579ExpectLast(t, conn, []byte{want}, "set_display")
	}
	lcd.SetStartBank(1)
	pcf8579ExpectLast(t, conn, []byte{0x7D}, "set_start_bank")
	lcd.Scroll(1)
	pcf8579ExpectLast(t, conn, []byte{0x7C}, "scroll wraps")
	lcd.Scroll(-1)
	pcf8579ExpectLast(t, conn, []byte{0x7D}, "scroll negative")
	if err := lcd.SetStartBank(4); !errors.Is(err, ErrPCF8579InvalidArgument) {
		t.Errorf("start bank 4 err = %v", err)
	}

	lcd.SetDisplay(PCF8579DisplayNormal)
	lcd.SetMode(24, true)
	pcf8579ExpectLast(t, conn, []byte{0x57}, "set_mode 24 mixed")
	lcd.SetMode(16, false)
	pcf8579ExpectLast(t, conn, []byte{0x46}, "set_mode row driver")
	if err := lcd.SetMode(12, false); !errors.Is(err, ErrPCF8579InvalidArgument) {
		t.Errorf("set_mode 12 err = %v", err)
	}

	sub := &pcf8579Mock{}
	other, err := NewPCF8579Full(sub, 5, 16, false)
	if err != nil {
		t.Fatal(err)
	}
	other.SetPixel(0, 0, true)
	if sub.last()[0] != 0xE5 {
		t.Error("pixel access must re-send the subaddress")
	}

	lcd.Reset()
	if !bytes.Equal(conn.writes[len(conn.writes)-2], pcf8579Bytes([]byte{0xE0, 0xC2, 0xFC, 0xF8, 0x00}, 160, 0)) ||
		!bytes.Equal(conn.last(), []byte{0x46}) {
		t.Error("reset must re-run init")
	}
}

func TestPCF8579RAM(t *testing.T) {
	lcd, conn := newPCF8579(t)
	lcd.WriteRAM(10, 0, []byte{1, 2}, PCF8579AccessCharacter)
	pcf8579ExpectLast(t, conn, []byte{0xE0, 0xF0, 10, 1, 2}, "write_ram character")
	lcd.WriteRAM(10, 2, []byte{1, 2, 3, 4}, PCF8579AccessHalfGraphic)
	pcf8579ExpectLast(t, conn, []byte{0xE0, 0xF6, 10, 1, 2, 3, 4}, "write_ram half")
	lcd.WriteRAM(10, 0, []byte{1, 2, 3, 4, 5, 6, 7, 8}, PCF8579AccessFullGraphic)
	pcf8579ExpectLast(t, conn, []byte{0xE0, 0xF8, 10, 1, 2, 3, 4, 5, 6, 7, 8}, "write_ram full")
	if lcd.shadow[3][10] != 4 || lcd.shadow[0][11] != 5 {
		t.Error("write_ram shadow order wrong")
	}
	for _, c := range []struct {
		x, bank int
		data    []byte
		mode    uint8
	}{{39, 0, []byte{0, 0}, 0}, {0, 4, []byte{1}, 0}, {0, 1, []byte{1}, 1}} {
		if err := lcd.WriteRAM(c.x, c.bank, c.data, c.mode); !errors.Is(err, ErrPCF8579InvalidArgument) {
			t.Errorf("WriteRAM(%d,%d) err = %v", c.x, c.bank, err)
		}
	}

	if err := lcd.WriteRAMCascade(pcf8579Bytes(nil, 200, 7), PCF8579AccessFullGraphic); err != nil {
		t.Fatal(err)
	}
	pcf8579ExpectLast(t, conn, pcf8579Bytes([]byte{0xE0, 0xF8, 0}, 200, 7), "write_ram_cascade")
	if lcd.shadow[0][39] != 7 {
		t.Error("cascade shadow must track this device's columns")
	}

	data, err := lcd.ReadRAM(16, 0, 4, PCF8579AccessCharacter)
	if err != nil || len(data) != 4 {
		t.Fatalf("ReadRAM: %v len %d", err, len(data))
	}
	pcf8579ExpectLast(t, conn, []byte{0xE0, 0xF0, 16}, "read_ram commands")
	lcd.ReadRAM(5, 2, 3, PCF8579AccessHalfGraphic)
	pcf8579ExpectLast(t, conn, []byte{0xE0, 0xF6, 5}, "read_ram half commands")
}
