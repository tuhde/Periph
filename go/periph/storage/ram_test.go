package storage

import (
	"bytes"
	"errors"
	"testing"
)

func TestRamPlainRoundtripAndRange(t *testing.T) {
	d := NewRamBlockDevice(512, 16, 1, false)
	w := bytes.Repeat([]byte{0xA5}, 512)
	if err := d.Write(3, w); err != nil {
		t.Fatal(err)
	}
	r := make([]byte, 512)
	if err := d.Read(3, r); err != nil || !bytes.Equal(w, r) {
		t.Fatalf("roundtrip: %v", err)
	}
	if err := d.Read(16, r); !errors.Is(err, ErrOutOfRange) {
		t.Fatalf("want ErrOutOfRange, got %v", err)
	}
}

func TestRamNorSemantics(t *testing.T) {
	d := NewRamBlockDevice(512, 16, 8, false)
	r := make([]byte, 512)
	_ = d.Read(0, r)
	if !bytes.Equal(r, bytes.Repeat([]byte{0xFF}, 512)) {
		t.Fatal("erased block must read 0xFF")
	}
	if err := d.Write(0, make([]byte, 512)); err != nil {
		t.Fatal(err)
	}
	if err := d.Write(0, bytes.Repeat([]byte{1}, 512)); !errors.Is(err, ErrNotErased) {
		t.Fatalf("want ErrNotErased, got %v", err)
	}
	if err := d.Erase(0, 8); err != nil {
		t.Fatal(err)
	}
	if err := d.Write(0, bytes.Repeat([]byte{2}, 512)); err != nil {
		t.Fatal(err)
	}
	if err := d.Erase(1, 8); !errors.Is(err, ErrOutOfRange) {
		t.Fatalf("unaligned erase: %v", err)
	}
	a := NewRamBlockDevice(512, 16, 8, true)
	_ = a.Write(2, bytes.Repeat([]byte{1}, 512))
	if err := a.Write(2, bytes.Repeat([]byte{2}, 512)); err != nil {
		t.Fatalf("auto erase: %v", err)
	}
	_ = a.Read(2, r)
	if r[0] != 2 {
		t.Fatal("auto-erase rewrite lost")
	}
}
