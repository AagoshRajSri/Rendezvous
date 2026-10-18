import { useState } from 'react';
import { GlobeMap } from './components/GlobeMap';
import { Header } from './components/Header';
import { SearchBar, type SearchTarget } from './components/SearchBar';
import { DispatchPanel } from './components/DispatchPanel';
import { RoutePreviewCard } from './components/RoutePreviewCard';
import { useMapReady } from './hooks/useMapReady';
import { usePlaceSearch } from './hooks/usePlaceSearch';
import { useSimulatedDrivers } from './hooks/useSimulatedDrivers';
import { useDispatch } from './hooks/useDispatch';
import { useRoute } from './hooks/useRoute';
import { useDriverApproach } from './hooks/useDriverApproach';
import { useVehicleAnimation } from './hooks/useVehicleAnimation';
import type { PlaceSuggestion } from './types/place';

/**
 * Root application component.
 *
 * State topology:
 *   - mapState         : map load lifecycle (loading → ready | error)
 *   - search           : query, suggestions, and search status from usePlaceSearch
 *   - activeTarget     : 'pickup' | 'destination' active search mode (Phase 11.1)
 *   - selectedPlace    : pickup place; drives globe camera, pin marker, and simulated fleet
 *   - destinationPlace : destination place; drives route calculation and preview (Phase 11.1)
 *   - routeState       : road-network route, distance, duration, and fares (Phase 11.1)
 *   - drivers          : deterministic simulated drivers generated for selectedPlace (Phase 7C)
 *   - dispatchState    : real backend dispatch lifecycle with fleet sync (Phase 11.2)
 *   - driverApproach   : road route from assigned driver origin to pickup (Phase 11.2)
 *   - vehicleAnimation : road-following vehicle animation with live ETA and distance (Phase 11.2)
 */
export default function App() {
  const { state: mapState, onLoad, onError } = useMapReady();
  const search = usePlaceSearch();
  const [activeTarget, setActiveTarget] = useState<SearchTarget>('pickup');
  const [selectedPlace, setSelectedPlace] = useState<PlaceSuggestion | null>(null);
  const [destinationPlace, setDestinationPlace] = useState<PlaceSuggestion | null>(null);

  const drivers = useSimulatedDrivers(selectedPlace);
  const {
    dispatchState,
    requestDispatch,
    cancelRide,
    isCancelling,
    reset: resetDispatch,
  } = useDispatch(selectedPlace, drivers);
  const { state: routeState, setSelectedTier, clearRoute } = useRoute(selectedPlace, destinationPlace);

  // Phase 11.2: Assigned driver road approach route & road-following vehicle animation
  const driverApproach = useDriverApproach(
    dispatchState.matchedDriverLocation,
    selectedPlace,
    dispatchState.phase === 'matched',
  );

  const vehicleAnimation = useVehicleAnimation({
    routeCoordinates: driverApproach.route?.coordinates ?? null,
    nominalDurationSeconds: driverApproach.route?.durationSeconds ?? 60,
    speedMultiplier: 8,
    isActive: driverApproach.status === 'ready' && dispatchState.phase === 'matched',
  });

  function handleSelect(place: PlaceSuggestion, target: SearchTarget = activeTarget) {
    if (target === 'pickup') {
      setSelectedPlace(place);
      search.setQuery(place.name);
      // Auto-advance to destination search if not yet chosen
      if (!destinationPlace) {
        setActiveTarget('destination');
        search.clearQuery();
      }
    } else {
      setDestinationPlace(place);
      search.setQuery(place.name);
    }
  }

  function handleSwap() {
    if (!selectedPlace || !destinationPlace) return;
    const prevPickup = selectedPlace;
    const prevDest = destinationPlace;
    setSelectedPlace(prevDest);
    setDestinationPlace(prevPickup);
    search.setQuery(activeTarget === 'pickup' ? prevDest.name : prevPickup.name);
  }

  function handleClearDestination() {
    setDestinationPlace(null);
    clearRoute();
    if (activeTarget === 'destination') {
      search.clearQuery();
      setActiveTarget('pickup');
    }
  }

  function handleClear() {
    if (activeTarget === 'pickup') {
      search.clearQuery();
      setSelectedPlace(null);
      setDestinationPlace(null);
      clearRoute();
      resetDispatch();
    } else {
      handleClearDestination();
    }
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
        activeTarget={activeTarget}
        onTargetChange={setActiveTarget}
        pickupPlace={selectedPlace}
        destinationPlace={destinationPlace}
        onSwap={handleSwap}
        onClearDestination={handleClearDestination}
      />

      <GlobeMap
        mapState={mapState}
        onLoad={onLoad}
        onError={onError}
        selectedPlace={selectedPlace}
        destinationPlace={destinationPlace}
        routeCoordinates={routeState.route?.coordinates ?? null}
        drivers={drivers}
        matchedDriverLocation={dispatchState.matchedDriverLocation}
        driverApproachRoute={driverApproach.route?.coordinates ?? null}
        animatedVehiclePosition={vehicleAnimation.currentPosition}
        animatedVehicleBearing={vehicleAnimation.bearing}
      />

      {/* ── Phase 11.1: Road route preview & fare card ────────────────────────── */}
      {selectedPlace && destinationPlace && routeState.route && mapState.status === 'ready' && (
        <RoutePreviewCard
          pickup={selectedPlace}
          destination={destinationPlace}
          route={routeState.route}
          selectedTier={routeState.selectedTier}
          onSelectTier={setSelectedTier}
          onRequestDispatch={requestDispatch}
          onClearDestination={handleClearDestination}
          isLoading={dispatchState.phase === 'registering' || dispatchState.phase === 'submitting'}
        />
      )}

      {/* ── Phase 11.2: Real dispatch & vehicle tracking panel ─────────────── */}
      {selectedPlace && mapState.status === 'ready' && (!destinationPlace || dispatchState.phase !== 'idle') && (
        <DispatchPanel
          placeName={selectedPlace.name}
          dispatchState={dispatchState}
          onRequestDispatch={requestDispatch}
          onCancelRide={cancelRide}
          isCancelling={isCancelling}
          onReset={resetDispatch}
          approachStatus={driverApproach.status}
          remainingDistanceMeters={vehicleAnimation.remainingDistanceMeters}
          remainingDurationSeconds={vehicleAnimation.remainingDurationSeconds}
          isVehicleArrived={vehicleAnimation.isArrived}
        />
      )}
    </>
  );
}
