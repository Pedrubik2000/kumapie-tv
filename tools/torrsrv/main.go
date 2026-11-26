package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"strings"
	"sync/atomic"
	"time"

	"github.com/anacrolix/missinggo/v2/filecache"
	"github.com/anacrolix/torrent"
	"github.com/anacrolix/torrent/metainfo"
	"github.com/anacrolix/torrent/storage"
	"golang.org/x/time/rate"
)

type fileInfo struct {
	Idx  int    `json:"idx"`
	Name string `json:"name"`
	Size int64  `json:"size"`
}

type status struct {
	HasInfo   bool       `json:"hasInfo"`
	Peers     int        `json:"peers"`
	Seeds     int        `json:"seeds"`
	Rate      int64      `json:"rate"`
	PieceLen  int64      `json:"pieceLen"`
	Cached    int64      `json:"cached"`
	Ahead     int64      `json:"ahead"`
	Window    int64      `json:"window"`
	Files     []fileInfo `json:"files"`
	CacheSize int64      `json:"cacheSize"`
}

func main() {
	dir := flag.String("dir", "", "cache dir")
	capacity := flag.Int64("cap", 200<<20, "window bytes")
	upload := flag.Int("upload", 50000, "upload bytes/s")
	conns := flag.Int("conns", 50, "connections")
	hash := flag.String("hash", "", "info hash")
	token := flag.String("token", "", "token")
	trackers := flag.String("tr", "", "trackers, comma separated")
	flag.Parse()
	if *dir == "" || *hash == "" || *token == "" {
		fmt.Fprintln(os.Stderr, "usage")
		os.Exit(2)
	}
	cache, err := filecache.NewCache(*dir)
	if err != nil {
		fmt.Fprintln(os.Stderr, "cache:", err)
		os.Exit(1)
	}
	cache.SetCapacity(*capacity)
	window := func() (int64, bool) { return *capacity * 3 / 4, true }
	cfg := torrent.NewDefaultClientConfig()
	cfg.DataDir = *dir
	cfg.DefaultStorage = storage.NewResourcePiecesOpts(cache.AsResourceProvider(), storage.ResourcePiecesOpts{Capacity: &window})
	cfg.ListenPort = 0
	cfg.NoDefaultPortForwarding = true
	cfg.Seed = false
	cfg.UploadRateLimiter = rate.NewLimiter(rate.Limit(*upload), *upload)
	cfg.EstablishedConnsPerTorrent = *conns
	cfg.HalfOpenConnsPerTorrent = 10
	cfg.TotalHalfOpenConns = 20
	cfg.DisableWebtorrent = true
	cfg.LocalServiceDiscovery = nil
	cfg.MaxUnverifiedBytes = 16 << 20
	client, err := torrent.NewClient(cfg)
	if err != nil {
		fmt.Fprintln(os.Stderr, "client:", err)
		os.Exit(1)
	}
	var ih metainfo.Hash
	if err := ih.FromHexString(*hash); err != nil {
		fmt.Fprintln(os.Stderr, "hash:", err)
		os.Exit(2)
	}
	spec := &torrent.TorrentSpec{InfoHash: ih}
	if *trackers != "" {
		spec.Trackers = [][]string{strings.Split(*trackers, ",")}
	}
	t, _, err := client.AddTorrentSpec(spec)
	if err != nil {
		fmt.Fprintln(os.Stderr, "add:", err)
		os.Exit(1)
	}

	var readAt atomic.Int64
	var lastBytes atomic.Int64
	var lastAt atomic.Int64
	var rateNow atomic.Int64
	go func() {
		for range time.Tick(time.Second) {
			c := t.Stats().BytesReadUsefulData
			b := c.Int64()
			now := time.Now().UnixMilli()
			if prev := lastAt.Load(); prev != 0 && now > prev {
				rateNow.Store((b - lastBytes.Load()) * 1000 / (now - prev))
			}
			lastBytes.Store(b)
			lastAt.Store(now)
		}
	}()

	auth := func(h http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			if r.Header.Get("X-Token") != *token && r.URL.Query().Get("t") != *token {
				http.Error(w, "forbidden", http.StatusForbidden)
				return
			}
			h(w, r)
		}
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/status", auth(func(w http.ResponseWriter, r *http.Request) {
		s := status{Window: *capacity}
		st := t.Stats()
		s.Peers = st.ActivePeers
		s.Seeds = st.ConnectedSeeders
		s.Rate = rateNow.Load()
		if t.Info() != nil {
			s.HasInfo = true
			s.PieceLen = t.Info().PieceLength
			for i, f := range t.Files() {
				s.Files = append(s.Files, fileInfo{Idx: i, Name: f.DisplayPath(), Size: f.Length()})
			}
			s.Cached = t.BytesCompleted()
			s.Ahead = ahead(t, readAt.Load())
		}
		s.CacheSize = cache.Info().Filled
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(s)
	}))
	mux.HandleFunc("/stream", auth(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-t.GotInfo():
		case <-r.Context().Done():
			return
		}
		var idx int
		fmt.Sscanf(r.URL.Query().Get("idx"), "%d", &idx)
		files := t.Files()
		if idx < 0 || idx >= len(files) {
			http.Error(w, "no such file", http.StatusNotFound)
			return
		}
		f := files[idx]
		rd := f.NewReader()
		defer rd.Close()
		rd.SetResponsive()
		rd.SetReadahead(*capacity / 2)
		rd.SetContext(r.Context())
		start := int64(0)
		if rg := r.Header.Get("Range"); strings.HasPrefix(rg, "bytes=") {
			fmt.Sscanf(strings.TrimPrefix(rg, "bytes="), "%d", &start)
		}
		readAt.Store(f.Offset() + start)
		w.Header().Set("Content-Type", "application/octet-stream")
		http.ServeContent(w, r, f.DisplayPath(), time.Time{}, &tracked{rd: rd, base: f.Offset(), at: &readAt})
	}))
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		fmt.Fprintln(os.Stderr, "listen:", err)
		os.Exit(1)
	}
	go http.Serve(ln, mux)
	fmt.Printf("PORT %d\n", ln.Addr().(*net.TCPAddr).Port)
	os.Stdout.Sync()
	_, _ = bufio.NewReader(os.Stdin).WriteTo(discard{})
	client.Close()
	os.RemoveAll(*dir)
}

type discard struct{}

func (discard) Write(p []byte) (int, error) { return len(p), nil }

type tracked struct {
	rd   torrent.Reader
	base int64
	at   *atomic.Int64
}

func (t *tracked) Read(p []byte) (int, error) {
	n, err := t.rd.Read(p)
	if n > 0 {
		pos, _ := t.rd.Seek(0, 1)
		t.at.Store(t.base + pos)
	}
	return n, err
}

func (t *tracked) Seek(off int64, whence int) (int64, error) { return t.rd.Seek(off, whence) }

func ahead(t *torrent.Torrent, pos int64) int64 {
	pl := t.Info().PieceLength
	p := int(pos / pl)
	n := int64(0)
	for ; p < t.NumPieces(); p++ {
		if !t.Piece(p).State().Complete {
			break
		}
		n += pl
	}
	n -= pos % pl
	if n < 0 {
		return 0
	}
	return n
}
