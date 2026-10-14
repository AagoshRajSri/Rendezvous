/**
 * Phase 7B — SearchBar component.
 *
 * Implements the WAI-ARIA 1.2 Combobox pattern (without select-only mode):
 * - role="combobox" on the input
 * - role="listbox" on the dropdown ul
 * - role="option" with aria-selected on each li
 * - aria-activedescendant tracks keyboard-highlighted option
 * - aria-expanded reflects open/closed state
 *
 * Keyboard bindings:
 *   ArrowDown / ArrowUp — navigate suggestions
 *   Enter               — select active suggestion (or first if none active)
 *   Escape              — first: dismiss dropdown; second: clear query
 *   Tab                 — dismiss without selecting
 */

import { useCallback, useEffect, useId, useRef, useState } from 'react';
import type { PlaceSuggestion } from '../types/place';
import type { SearchState } from '../hooks/usePlaceSearch';
import './SearchBar.css';

interface SearchBarProps {
  query: string;
  state: SearchState;
  onQueryChange: (q: string) => void;
  onClear: () => void;
  onRetry: () => void;
  onSelect: (place: PlaceSuggestion) => void;
  /** Whether the map is ready — hides the bar until the globe is interactive. */
  mapReady: boolean;
}

// Maps a place type/osm_value to a readable icon character
function placeIcon(type: string): string {
  switch (type.toLowerCase()) {
    case 'city':
    case 'town':
    case 'village':
      return '🏙';
    case 'country':
      return '🌍';
    case 'airport':
      return '✈';
    case 'peak':
    case 'mountain':
      return '⛰';
    case 'station':
      return '🚉';
    case 'house':
    case 'street':
      return '📍';
    default:
      return '📌';
  }
}

export function SearchBar({
  query,
  state,
  onQueryChange,
  onClear,
  onRetry,
  onSelect,
  mapReady,
}: SearchBarProps) {
  const uid = useId();
  const listboxId = `${uid}-listbox`;
  const inputRef = useRef<HTMLInputElement>(null);

  const [activeIndex, setActiveIndex] = useState<number>(-1);
  const [isOpen, setIsOpen] = useState(false);

  const suggestions =
    state.status === 'ok' ? state.suggestions : [];
  const showDropdown =
    isOpen &&
    query.trim().length >= 2 &&
    (state.status === 'ok' ||
      state.status === 'empty' ||
      state.status === 'error');

  // Reset active index when suggestions list changes
  useEffect(() => {
    setActiveIndex(-1);
  }, [suggestions]);

  // Open dropdown whenever state changes to a displayable result, but only if the user is focused on the input
  useEffect(() => {
    if (
      state.status === 'ok' ||
      state.status === 'empty' ||
      state.status === 'error'
    ) {
      if (document.activeElement === inputRef.current) {
        setIsOpen(true);
      }
    } else if (state.status === 'idle') {
      setIsOpen(false);
    }
  }, [state.status]);

  const selectSuggestion = useCallback(
    (place: PlaceSuggestion) => {
      setIsOpen(false);
      setActiveIndex(-1);
      inputRef.current?.blur();
      onSelect(place);
    },
    [onSelect],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLInputElement>) => {
      switch (e.key) {
        case 'ArrowDown':
          e.preventDefault();
          if (!isOpen) { setIsOpen(true); return; }
          setActiveIndex((prev) =>
            prev < suggestions.length - 1 ? prev + 1 : 0,
          );
          break;
        case 'ArrowUp':
          e.preventDefault();
          if (!isOpen) { setIsOpen(true); return; }
          setActiveIndex((prev) =>
            prev > 0 ? prev - 1 : suggestions.length - 1,
          );
          break;
        case 'Enter':
          e.preventDefault();
          if (activeIndex >= 0 && suggestions[activeIndex]) {
            selectSuggestion(suggestions[activeIndex]);
          } else if (suggestions.length > 0) {
            selectSuggestion(suggestions[0]);
          }
          break;
        case 'Escape':
          e.preventDefault();
          if (isOpen) {
            setIsOpen(false);
            setActiveIndex(-1);
          } else {
            onClear();
          }
          break;
        case 'Tab':
          setIsOpen(false);
          setActiveIndex(-1);
          break;
      }
    },
    [isOpen, suggestions, activeIndex, selectSuggestion, onClear],
  );

  const handleInputChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      setIsOpen(true);
      onQueryChange(e.target.value);
    },
    [onQueryChange],
  );

  const handleClear = useCallback(() => {
    onClear();
    setIsOpen(false);
    setActiveIndex(-1);
    inputRef.current?.focus();
  }, [onClear]);

  // Close dropdown on outside click
  const containerRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    function handleOutsideClick(e: MouseEvent) {
      if (
        containerRef.current &&
        !containerRef.current.contains(e.target as Node)
      ) {
        setIsOpen(false);
        setActiveIndex(-1);
      }
    }
    document.addEventListener('mousedown', handleOutsideClick);
    return () => document.removeEventListener('mousedown', handleOutsideClick);
  }, []);

  const activeOptionId =
    activeIndex >= 0 ? `${uid}-option-${activeIndex}` : undefined;

  if (!mapReady) return null;

  return (
    <div className="search-bar" ref={containerRef}>
      {/* ── Input row ─────────────────────────────────────────────────────── */}
      <div className="search-bar__row">
        {/* Search icon */}
        <svg
          className="search-bar__icon"
          viewBox="0 0 20 20"
          fill="none"
          aria-hidden="true"
        >
          <circle cx="8.5" cy="8.5" r="5.5" stroke="currentColor" strokeWidth="1.8" />
          <path d="M13 13l3.5 3.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        </svg>

        {/* Combobox input */}
        <input
          id={`${uid}-input`}
          ref={inputRef}
          className="search-bar__input"
          type="text"
          autoComplete="off"
          autoCorrect="off"
          autoCapitalize="off"
          spellCheck={false}
          placeholder="Search cities, countries, landmarks…"
          value={query}
          onChange={handleInputChange}
          onKeyDown={handleKeyDown}
          onFocus={() => query.trim().length >= 2 && setIsOpen(true)}
          role="combobox"
          aria-label="Search for a place"
          aria-expanded={showDropdown}
          aria-autocomplete="list"
          aria-controls={listboxId}
          aria-activedescendant={activeOptionId}
        />

        {/* Loading spinner */}
        {state.status === 'loading' && (
          <div className="search-bar__spinner" aria-hidden="true" />
        )}

        {/* Clear button */}
        {query.length > 0 && (
          <button
            id={`${uid}-clear`}
            className="search-bar__clear"
            type="button"
            aria-label="Clear search"
            onClick={handleClear}
            tabIndex={0}
          >
            ×
          </button>
        )}
      </div>

      {/* ── Dropdown ──────────────────────────────────────────────────────── */}
      {showDropdown && (
        <div className="search-bar__dropdown">
          {state.status === 'ok' && (
            <ul
              id={listboxId}
              role="listbox"
              aria-label="Place suggestions"
            >
              {suggestions.map((place, i) => (
                <li
                  key={place.id}
                  id={`${uid}-option-${i}`}
                  className={`search-bar__option${
                    i === activeIndex ? ' search-bar__option--active' : ''
                  }`}
                  role="option"
                  aria-selected={i === activeIndex}
                  onMouseDown={(e) => {
                    // Prevent input blur before click fires
                    e.preventDefault();
                    selectSuggestion(place);
                  }}
                  onMouseEnter={() => setActiveIndex(i)}
                >
                  <span className="search-bar__option-icon" aria-hidden="true">
                    {placeIcon(place.type)}
                  </span>
                  <span className="search-bar__option-text">
                    <span className="search-bar__option-name">{place.name}</span>
                    {place.subtitle && (
                      <span className="search-bar__option-subtitle">
                        {place.subtitle}
                      </span>
                    )}
                  </span>
                </li>
              ))}
            </ul>
          )}

          {state.status === 'empty' && (
            <div
              className="search-bar__status search-bar__status--empty"
              role="status"
              aria-live="polite"
            >
              No places found for &ldquo;{query}&rdquo;
            </div>
          )}

          {state.status === 'error' && (
            <div
              className="search-bar__status search-bar__status--error"
              role="alert"
            >
              {state.message}
              {state.retryable && (
                <div>
                  <button
                    className="search-bar__retry"
                    type="button"
                    onClick={onRetry}
                  >
                    ↺ Try again
                  </button>
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
