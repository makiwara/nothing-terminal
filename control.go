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
		ID      string `json:"id"`
		Label   string `json:"label"`
		Command string `json:"command"`
	}
	out := []item{}
	for _, s := range r.Scripts() {
		out = append(out, item{s.ID, s.Label, s.commandHint()})
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
// canned proposal. The audio part is discarded; an Adjust re-record's optional
// `context` part is echoed into the proposal so the refine round-trip is visible
// without a real backend. ?intent=stop returns a signal action.
func (r *Registry) handleVoice(w http.ResponseWriter, req *http.Request) {
	if r.Get(req.PathValue("id")) == nil {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	context := readVoiceContext(req)
	if req.URL.Query().Get("intent") == "stop" {
		writeJSON(w, http.StatusOK, map[string]any{
			"transcript": "stop",
			"action":     map[string]string{"kind": "signal", "signal": "INT"},
		})
		return
	}
	transcript := "git status"
	if context != "" {
		transcript = context + " (refined)" // prove the Adjust context arrived over the wire
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"transcript": transcript,
		"action":     map[string]string{"kind": "text", "text": transcript},
	})
}

// readVoiceContext drains the multipart upload and returns the prior transcript from
// the optional JSON `context` part (specs/control_plane.md). A non-multipart body
// (older client, manual curl) yields no context.
func readVoiceContext(req *http.Request) string {
	mr, err := req.MultipartReader()
	if err != nil {
		_, _ = io.Copy(io.Discard, req.Body)
		return ""
	}
	context := ""
	for {
		part, err := mr.NextPart()
		if err != nil {
			break
		}
		if part.FormName() == "context" {
			b, _ := io.ReadAll(io.LimitReader(part, 1<<16))
			var c struct {
				Transcript string `json:"transcript"`
			}
			_ = json.Unmarshal(b, &c)
			context = c.Transcript
		} else {
			_, _ = io.Copy(io.Discard, part)
		}
		part.Close()
	}
	return context
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
