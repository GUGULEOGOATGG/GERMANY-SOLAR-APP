// Germany Solar: app de PC. Abre uma janela própria (sem abas nem
// barra de endereço) e guarda os dados em dados-equipe.json, ao lado do executável.
package main

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sync"
	"sync/atomic"
	"time"
)

//go:embed index.html
var pagina []byte

//go:embed icone.png
var icone []byte

const endereco = "127.0.0.1:47831"

var ativos, jaConectou int32

// motor procura o Edge (vem em todo Windows 10 e 11) ou o Chrome para desenhar a janela.
func motor() string {
	if runtime.GOOS != "windows" {
		return ""
	}
	var c []string
	for _, v := range []string{"ProgramFiles(x86)", "ProgramFiles", "LocalAppData"} {
		if p := os.Getenv(v); p != "" {
			c = append(c, filepath.Join(p, "Microsoft", "Edge", "Application", "msedge.exe"))
		}
	}
	for _, v := range []string{"ProgramFiles", "ProgramFiles(x86)", "LocalAppData"} {
		if p := os.Getenv(v); p != "" {
			c = append(c, filepath.Join(p, "Google", "Chrome", "Application", "chrome.exe"))
		}
	}
	for _, p := range c {
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return ""
}

// janela abre o app em janela própria e só retorna quando ela é fechada.
func janela(url string) bool {
	m := motor()
	if m == "" {
		return false
	}
	perfil := filepath.Join(os.TempDir(), "EquipeEletricaSolar")
	if d, err := os.UserCacheDir(); err == nil {
		perfil = filepath.Join(d, "EquipeEletricaSolar", "janela")
	}
	os.MkdirAll(perfil, 0o755)
	cmd := exec.Command(m, "--app="+url, "--user-data-dir="+perfil, "--no-first-run",
		"--no-default-browser-check", "--window-size=1150,820")
	return cmd.Run() == nil
}

func navegadorPadrao(url string) {
	switch runtime.GOOS {
	case "windows":
		exec.Command("rundll32", "url.dll,FileProtocolHandler", url).Start()
	case "darwin":
		exec.Command("open", url).Start()
	default:
		exec.Command("xdg-open", url).Start()
	}
}

// vigia encerra o programa quando nenhuma tela do app está mais aberta.
func vigia() {
	inicio, vazio := time.Now(), 0
	for {
		time.Sleep(time.Second)
		switch {
		case atomic.LoadInt32(&ativos) > 0:
			vazio = 0
		case atomic.LoadInt32(&jaConectou) == 1:
			if vazio++; vazio >= 15 {
				return
			}
		case time.Since(inicio) > 2*time.Minute:
			return
		}
	}
}

func main() {
	url := "http://" + endereco + "/"
	ln, err := net.Listen("tcp", endereco)
	if err != nil { // já está aberto: só traz outra janela
		if motor() == "" {
			navegadorPadrao(url)
		} else {
			go janela(url)
			time.Sleep(3 * time.Second)
		}
		return
	}
	pasta := "."
	if exe, err := os.Executable(); err == nil {
		pasta = filepath.Dir(exe)
	}
	arq := filepath.Join(pasta, "dados-equipe.json")
	if b, err := os.ReadFile(arq); err == nil && len(b) > 2 {
		os.WriteFile(filepath.Join(pasta, "dados-equipe.copia-anterior.json"), b, 0o644)
	}
	var mu sync.Mutex

	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Write(pagina)
	})
	http.HandleFunc("/icone.png", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/png")
		w.Write(icone)
	})
	http.HandleFunc("/api/vivo", func(w http.ResponseWriter, r *http.Request) {
		f, ok := w.(http.Flusher)
		if !ok {
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.Header().Set("Cache-Control", "no-store")
		atomic.AddInt32(&ativos, 1)
		atomic.StoreInt32(&jaConectou, 1)
		defer atomic.AddInt32(&ativos, -1)
		fmt.Fprint(w, ": ok\n\n")
		f.Flush()
		t := time.NewTicker(20 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-r.Context().Done():
				return
			case <-t.C:
				fmt.Fprint(w, ": ping\n\n")
				f.Flush()
			}
		}
	})
	http.HandleFunc("/api/dados", func(w http.ResponseWriter, r *http.Request) {
		if r.Host != endereco {
			http.Error(w, "host", http.StatusForbidden)
			return
		}
		mu.Lock()
		defer mu.Unlock()
		switch r.Method {
		case http.MethodGet:
			b, err := os.ReadFile(arq)
			if err != nil || !json.Valid(b) {
				b = []byte("{}")
			}
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Cache-Control", "no-store")
			w.Write(b)
		case http.MethodPut:
			if o := r.Header.Get("Origin"); r.Header.Get("X-App") != "1" || (o != "" && o != "http://"+endereco) {
				http.Error(w, "origem", http.StatusForbidden)
				return
			}
			b, err := io.ReadAll(io.LimitReader(r.Body, 512<<20))
			var obj map[string]any
			if err != nil || json.Unmarshal(b, &obj) != nil {
				http.Error(w, "json", http.StatusBadRequest)
				return
			}
			tmp := arq + ".tmp"
			if os.WriteFile(tmp, b, 0o644) != nil || os.Rename(tmp, arq) != nil {
				http.Error(w, "disco", http.StatusInternalServerError)
				return
			}
			w.WriteHeader(http.StatusNoContent)
		default:
			http.Error(w, "metodo", http.StatusMethodNotAllowed)
		}
	})
	go http.Serve(ln, nil)

	aberto := time.Now()
	if !janela(url) {
		navegadorPadrao(url)
		vigia()
	} else if time.Since(aberto) < 5*time.Second {
		vigia() // a janela foi entregue a outro processo: espera a tela fechar
	}
	mu.Lock() // deixa terminar uma gravação em andamento
	os.Exit(0)
}
