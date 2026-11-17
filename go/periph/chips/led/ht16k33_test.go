package led

import (
	"bytes"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ht16k33Mock is an in-memory fake connection.Connection for unit tests —
// no hardware, no bus. Every Write is logged; reads pop from a FIFO queue.
type ht16k33Mock struct {
	connection.Connection // embedded nil: only the methods overridden below are used
	writes                [][]byte
	reads                 [][]byte
}

func (m *ht16k33Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}

func (m *ht16k33Mock) Read(n int) ([]byte, error) {
	if len(m.reads) == 0 {
		return make([]byte, n), nil
	}
	r := m.reads[0]
	m.reads = m.reads[1:]
	return r, nil
}

func (m *ht16k33Mock) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return m.Read(n)
}

func (m *ht16k33Mock) Close() error                { return nil }
func (m *ht16k33Mock) IntPin() connection.InputPin { return nil }

func (m *ht16k33Mock) last() []byte { return m.writes[len(m.writes)-1] }

func TestHT16K33(t *testing.T) {
	m := &ht16k33Mock{}
	d, err := NewHT16K33Full(m)
	if err != nil {
		t.Fatal(err)
	}
	eq := func(label string, got []byte, want ...byte) {
		t.Helper()
		if !bytes.Equal(got, want) {
			t.Errorf("%s: got % X, want % X", label, got, want)
		}
	}
	eq("init_osc", m.writes[0], 0x21)
	eq("init_row", m.writes[1], 0xA0)
	eq("init_dim", m.writes[2], 0xEF)
	eq("init_ram", m.writes[3], make([]byte, 17)...)
	eq("init_display", m.writes[4], 0x81)

	if err := d.SetPixel(1, 9, true); err != nil {
		t.Fatal(err)
	}
	_ = d.Show()
	want := make([]byte, 17)
	want[1+3] = 0x02
	eq("set_pixel_show", m.last(), want...)
	if on, _ := d.GetPixel(1, 9); !on {
		t.Error("get_pixel on")
	}
	if on, _ := d.GetPixel(1, 8); on {
		t.Error("get_pixel off")
	}
	_ = d.SetPixel(7, 15, true)
	if d.ram[15] != 0x80 {
		t.Error("set_pixel last")
	}
	_ = d.SetPixel(1, 9, false)
	if on, _ := d.GetPixel(1, 9); on {
		t.Error("clear pixel")
	}
	if d.SetPixel(8, 0, true) == nil || d.SetPixel(0, 16, true) == nil {
		t.Error("range")
	}

	_ = d.Clear()
	eq("clear", m.last(), make([]byte, 17)...)
	_ = d.WriteRaw([]byte{0xAB, 0xCD}, 4)
	eq("write_raw", m.last(), 0x04, 0xAB, 0xCD)
	n := len(m.writes)
	if d.WriteRaw([]byte{0, 0}, 15) == nil || d.WriteRaw(nil, 0) == nil || len(m.writes) != n {
		t.Error("write_raw rejects")
	}

	_ = d.SetBrightness(7)
	eq("brightness", m.last(), 0xE7)
	if d.SetBrightness(16) == nil {
		t.Error("brightness range")
	}

	_ = d.SetBlink(1)
	eq("blink_1hz", m.last(), 0x85)
	_ = d.SetBlink(0.5)
	eq("blink_0_5hz", m.last(), 0x87)
	_ = d.SetBlink(2)
	eq("blink_2hz", m.last(), 0x83)
	if d.SetBlink(3) == nil {
		t.Error("blink invalid")
	}
	_ = d.DisplayOff()
	eq("display_off", m.last(), 0x82)
	_ = d.DisplayOn()
	eq("display_on", m.last(), 0x83)
	_ = d.SetBlink(0)

	_ = d.SetIntOutput(true, false)
	eq("int_low", m.last(), 0xA1)
	_ = d.SetIntOutput(true, true)
	eq("int_high", m.last(), 0xA3)

	_ = d.Standby()
	eq("standby", m.last(), 0x20)
	n = len(m.writes)
	_ = d.Wake()
	eq("wake_osc", m.writes[n], 0x21)
	eq("wake_row", m.writes[n+1], 0xA3)
	eq("wake_dim", m.writes[n+2], 0xE7)
	if len(m.writes[n+3]) != 17 {
		t.Error("wake ram")
	}
	eq("wake_display", m.writes[n+4], 0x81)

	m.reads = append(m.reads, []byte{0x01, 0x00, 0x00, 0x10, 0x00, 0x00})
	keys, _ := d.ReadKeys()
	eq("read_keys_ptr", m.last(), 0x40)
	if keys != 1|(1<<25) {
		t.Errorf("read_keys mask %x", keys)
	}
	m.reads = append(m.reads, []byte{0x01})
	if p, _ := d.KeyPressed(); !p {
		t.Error("key_pressed")
	}
	eq("key_pressed_ptr", m.last(), 0x60)
	m.reads = append(m.reads, []byte{0x00})
	if k, _ := d.PollInterrupt(); k != 0 {
		t.Error("poll none")
	}
	m.reads = append(m.reads, []byte{0x01}, []byte{0, 0, 0, 0, 0x01, 0})
	if k, _ := d.PollInterrupt(); k != 1<<26 {
		t.Errorf("poll keys %x", k)
	}
}
