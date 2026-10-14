import { useState } from 'react';
import { GlobeMap } from './components/GlobeMap';
import { Header } from './components/Header';
import { SearchBar } from './components/SearchBar';
import { useMapReady } from './hooks/useMapReady';
import { usePlaceSearch } from './hooks/usePlaceSearch';
import type { PlaceSuggestion } from './types/place';

/**
 * Root application component.
 *
 * State topology:
 *   - mapState   : map load lifecycle (loading → ready | error)
 *   - search     : query, suggestions, and search status from usePlaceSearch
 *   - selectedPlace : the place the user most recently selected; drives
 *                     the globe camera animation and pin marker
 *
 * Lift selectedPlace here so it can flow to both Header (future use) and
 * GlobeMap without a context or prop-drilling workaround.
 */
export default function App() {
  const { state: mapState, onLoad, onError } = useMapReady();
  const search = usePlaceSearch();
  const [selectedPlace, setSelectedPlace] = useState<PlaceSuggestion | null>(null);

  function handleSelect(place: PlaceSuggestion) {
    setSelectedPlace(place);
    // Update the query text to the selected place name for clarity
    search.setQuery(place.name);
  }

  function handleClear() {
    search.clearQuery();
    setSelectedPlace(null);
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
      />
    </>
  );
}
