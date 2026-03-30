package api

import (
	"encoding/json"
	"errors"
	"net/http"

	"github.com/net-perspective/home-peer/internal/homepeer"
	"github.com/net-perspective/home-peer/internal/model"
)

// Server is the HTTP API layer wrapping a HomePeer.
type Server struct {
	hp *homepeer.HomePeer
}

// NewServer creates a new API Server for the given HomePeer.
func NewServer(hp *homepeer.HomePeer) *Server {
	return &Server{hp: hp}
}

// Handler returns an http.Handler with all API routes registered.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /users/{userid}/publish", s.handlePublish)
	mux.HandleFunc("GET /users/{userid}/feed", s.handleFeed)
	mux.HandleFunc("GET /users/{userid}/deps", s.handleDeps)
	mux.HandleFunc("GET /direct-relations/{userid}", s.handleGetDR)
	mux.HandleFunc("GET /dep/{addr}", s.handleGetDep)
	mux.HandleFunc("GET /health", s.handleHealth)
	return mux
}

func (s *Server) handlePublish(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userid")

	var dr model.DirectRelations
	if err := json.NewDecoder(r.Body).Decode(&dr); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON: "+err.Error())
		return
	}
	if dr.UserID != userID {
		writeError(w, http.StatusBadRequest, "user_id mismatch")
		return
	}

	if err := s.hp.Publish(&dr); err != nil {
		if errors.Is(err, homepeer.ErrNotHomed) {
			writeError(w, http.StatusNotFound, "user not homed")
			return
		}
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "published", "user_id": userID})
}

func (s *Server) handleFeed(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userid")

	feed, err := s.hp.Feed(r.Context(), userID)
	if err != nil {
		if errors.Is(err, homepeer.ErrNotHomed) {
			writeError(w, http.StatusNotFound, "user not homed")
			return
		}
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, feed)
}

func (s *Server) handleDeps(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userid")

	drd, err := s.hp.Deps(r.Context(), userID)
	if err != nil {
		if errors.Is(err, homepeer.ErrNotHomed) {
			writeError(w, http.StatusNotFound, "user not homed")
			return
		}
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if drd == nil {
		writeJSON(w, http.StatusOK, nil)
		return
	}
	writeJSON(w, http.StatusOK, drd)
}

func (s *Server) handleGetDR(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userid")

	dr, err := s.hp.CachedDR(r.Context(), userID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if dr == nil {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	writeJSON(w, http.StatusOK, dr)
}

func (s *Server) handleGetDep(w http.ResponseWriter, r *http.Request) {
	addr := r.PathValue("addr")
	data, err := s.hp.GetDep(addr)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if data == nil {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Encoding", "gzip")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.hp.Health())
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}
