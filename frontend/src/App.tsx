import { useState } from 'react';
import { GlobeMap } from './components/GlobeMap';
import { Header } from './components/Header';
import { SearchBar } from './components/SearchBar';
import { DispatchPanel } from './components/DispatchPanel';
import { useMapReady } from './hooks/useMapReady';
import { usePlaceSearch } from './hooks/usePlaceSearch';
import { useSimulatedDrivers } from './hooks/useSimulatedDrivers';
import { useDispatch } from './hooks/useDispatch';
import type { PlaceSuggestion } from './types/place';

/**
 * Root application component.
 *
 * State topology:
 *   - mapState       : map load lifecycle (loading → ready | error)
 *   - search         : query, suggestions, and search status from usePlaceSearch
 *   - selectedPlace  : the place the user most recently selected; drives
 *                      the globe camera animation, pin marker, and simulated fleet
 *   - drivers        : deterministic simulated drivers generated for selectedPlace (Phase 7C)
 *   - dispatchState  : real backend dispatch lifecycle (Phase 7D)
 *
 * Simulated drivers (Phase 7C) and real dispatch results (Phase 7D) are
 * deliberately kept separate and are never mixed in the matching pipeline.
 */
export default function App() {
  const { state: mapState, onLoad, onError } = useMapReady();
  const search = usePlaceSearch();
  const [selectedPlace, setSelectedPlace] = useState<PlaceSuggestion | null>(null);
  const drivers = useSimulatedDrivers(selectedPlace);
  const { dispatchState, requestDispatch, reset: resetDispatch } = useDispatch(selectedPlace);

  function handleSelect(place: PlaceSuggestion) {
    setSelectedPlace(place);
    // Update the query text to the selected place name for clarity
    search.setQuery(place.name);
  }

  function handleClear() {
    search.clearQuery();
    setSelectedPlace(null);
    resetDispatch();
  }

  return (
    <>
      <Header mapState={mapState} />

      <SearchBar
        query={search.query}
        state={search.state}
        onQueryChange={search.setQuery}
        onClear={handleClear}
        onRetry={search.retry}
        onSelect={handleSelect}
        mapReady={mapState.status === 'ready'}
      />

      <GlobeMap
        mapState={mapState}
        onLoad={onLoad}
        onError={onError}
        selectedPlace={selectedPlace}
        drivers={drivers}
        matchedDriverLocation={dispatchState.matchedDriverLocation}
      />

      {/* ── Phase 7D: Real dispatch panel ─────────────────────────────────── */}
      {selectedPlace && mapState.status === 'ready' && (
        <DispatchPanel
          placeName={selectedPlace.name}
          dispatchState={dispatchState}
          onRequestDispatch={requestDispatch}
          onReset={resetDispatch}
        />
      )}
    </>
  );
}
