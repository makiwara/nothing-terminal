package main

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
)

// The control plane: REST endpoints the cockpit uses to list scripts, manage the
// session ring, and run the mock voice propose/send flow. Shapes mirror the
// nothing-serious backend brief so the app codes against one contract.

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func reqToken(r *http.Request) string {
	if t := r.URL.Query().Get("token"); t != "" {
		return t
	}
	if a := r.Header.Get("Authorization"); strings.HasPrefix(a, "Bearer ") {
		return a[len("Bearer "):]
	}
	return ""
}

// authed wraps a handler with an optional token check (?token= or Bearer).
func authed(token string, h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if token != "" && reqToken(r) != token {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		h(w, r)
	}
}

func (r *Registry) handleScripts(w http.ResponseWriter, _ *http.Request) {
	type item struct {
		ID    string `json:"id"`
		Label string `json:"label"`
	}
	out := []item{}
	for _, s := range r.Scripts() {
		out = append(out, item{s.ID, s.Label})
	}
	writeJSON(w, http.StatusOK, out)
}

func (r *Registry) handleListSessions(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, r.List())
}

func (r *Registry) handleOpenSession(w http.ResponseWriter, req *http.Request) {
	var body struct {
		ScriptID string `json:"script_id"`
	}
	_ = json.NewDecoder(req.Body).Decode(&body)
	sess, err := r.Open(body.ScriptID)
	if err != nil {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusCreated, sess)
}

func (r *Registry) handleCloseSession(w http.ResponseWriter, req *http.Request) {
	if r.Close(req.PathValue("id")) {
		w.WriteHeader(http.StatusNoContent)
	} else {
		w.WriteHeader(http.StatusNotFound)
	}
}

// handleVoice is the mock propose step: there is no STT here, so it returns a
// canned proposal. ?intent=stop returns a signal action; otherwise a text action.
func (r *Registry) handleVoice(w http.ResponseWriter, req *http.Request) {
	if r.Get(req.PathValue("id")) == nil {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	_, _ = io.Copy(io.Discard, req.Body) // ignore the uploaded audio
	if req.URL.Query().Get("intent") == "stop" {
		writeJSON(w, http.StatusOK, map[string]any{
			"transcript": "stop",
			"action":     map[string]string{"kind": "signal", "signal": "INT"},
		})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"transcript": "git status",
		"action":     map[string]string{"kind": "text", "text": "git status"},
	})
}

// handleSend injects the confirmed action into the session PTY.
func (r *Registry) handleSend(w http.ResponseWriter, req *http.Request) {
	sess := r.Get(req.PathValue("id"))
	if sess == nil {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	var body struct {
		Action struct {
			Kind   string `json:"kind"`
			Text   string `json:"text"`
			Signal string `json:"signal"`
		} `json:"action"`
	}
	_ = json.NewDecoder(req.Body).Decode(&body)
	switch body.Action.Kind {
	case "text":
		sess.Inject([]byte(body.Action.Text + "\r"))
	case "signal":
		if body.Action.Signal == "INT" {
			sess.Inject([]byte{0x03})
		}
	}
	w.WriteHeader(http.StatusNoContent)
}
