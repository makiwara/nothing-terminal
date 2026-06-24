// Command termshare is the development stand-in server for the nothing-terminal
// cockpit: a test harness that implements specs/protocol.md (the per-session
// WebSocket data plane) plus the control plane the app needs — a script catalog,
// a session ring, and a mock voice propose/send flow. It is not production; the
// real backend is the HOME-only Python `terminals/` service in nothing-serious.
//
// Sessions are demo-backed (a generated ANSI stream, no PTY) so the harness runs
// anywhere, including sandboxes that can't allocate PTYs. A command passed on the
// CLI is added as a real PTY-backed script and needs a genuine terminal.
//
// Endpoints (all optionally gated by -token):
//
//	GET    /scripts                       list the catalog
//	GET    /sessions                      list the ring
//	POST   /sessions {script_id}          open a session
//	DELETE /sessions/{id}                 close a session
//	GET    /sessions/{id}/attach          WebSocket stream (specs/protocol.md)
//	POST   /sessions/{id}/voice           mock propose -> {transcript, action}
//	POST   /sessions/{id}/send {action}   inject the confirmed action
package main

import (
	"flag"
	"log"
	"net/http"
	"strings"
)

func main() {
	addr := flag.String("addr", ":8080", "HTTP listen address")
	token := flag.String("token", "", "if set, require ?token= or Authorization: Bearer on all endpoints")
	flag.Parse()

	scripts := []Script{
		{ID: "monitor", Label: "Monitor", demo: &demoSpec{title: "monitor", color: 6}},
		{ID: "logs", Label: "Logs", demo: &demoSpec{title: "logs", color: 2}},
		{ID: "build", Label: "Build", demo: &demoSpec{title: "build", color: 3}},
	}
	// A command passed on the CLI becomes a real PTY-backed script (needs a TTY).
	if args := flag.Args(); len(args) > 0 {
		scripts = append(scripts, Script{ID: "cmd", Label: strings.Join(args, " "), command: args})
	}

	reg := newRegistry(scripts)

	mux := http.NewServeMux()
	mux.HandleFunc("GET /scripts", authed(*token, reg.handleScripts))
	mux.HandleFunc("GET /sessions", authed(*token, reg.handleListSessions))
	mux.HandleFunc("POST /sessions", authed(*token, reg.handleOpenSession))
	mux.HandleFunc("DELETE /sessions/{id}", authed(*token, reg.handleCloseSession))
	mux.HandleFunc("GET /sessions/{id}/attach", reg.serveAttach(*token))
	mux.HandleFunc("POST /sessions/{id}/voice", authed(*token, reg.handleVoice))
	mux.HandleFunc("POST /sessions/{id}/send", authed(*token, reg.handleSend))

	log.Printf("nothing-terminal stand-in on %s (scripts: %s)", *addr, scriptIDs(scripts))
	log.Fatal(http.ListenAndServe(*addr, mux))
}

func scriptIDs(s []Script) string {
	ids := make([]string, len(s))
	for i := range s {
		ids[i] = s[i].ID
	}
	return strings.Join(ids, ", ")
}
