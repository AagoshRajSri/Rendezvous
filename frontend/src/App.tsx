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
 *   - dispatchState    : real backend dispatch lifecycle with full trip progression (Phase 11.3)
 *   - driverApproach   : road route from assigned driver origin to pickup (Phase 11.2)
 *   - approachAnimation: vehicle animation along approach route to pickup (Phase 11.2)
 *   - tripAnimation    : vehicle animation along road route to destination (Phase 11.3)
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
    startRide,
    isStarting,
    completeRide,
    isCompleting,
    markArrived,
    reset: resetDispatch,
  } = useDispatch(selectedPlace, drivers);
  const { state: routeState, setSelectedTier, clearRoute } = useRoute(selectedPlace, destinationPlace);

  // Phase 11.2: Assigned driver road approach route
  const driverApproach = useDriverApproach(
    dispatchState.matchedDriverLocation,
    selectedPlace,
    dispatchState.phase === 'matched' || dispatchState.phase === 'en_route',
  );

  // Approach animation: vehicle travels from matched driver origin to pickup pin
  const approachAnimation = useVehicleAnimation({
    routeCoordinates: driverApproach.route?.coordinates ?? null,
    nominalDurationSeconds: driverApproach.route?.durationSeconds ?? 60,
    speedMultiplier: 8,
    isActive:
      (dispatchState.phase === 'matched' || dispatchState.phase === 'en_route') &&
      driverApproach.status === 'ready',
    onArrival: markArrived,
  });

  // Trip animation: vehicle travels from pickup to destination along road route
  const tripAnimation = useVehicleAnimation({
    routeCoordinates: routeState.route?.coordinates ?? null,
    nominalDurationSeconds: routeState.route?.durationSeconds ?? 120,
    speedMultiplier: 8,
    isActive: dispatchState.phase === 'in_progress' && !!routeState.route,
  });

  // Destination validation for starting the trip
  const canStartTrip = Boolean(
    destinationPlace &&
    routeState.route &&
    routeState.route.coordinates.length >= 2 &&
    routeState.status === 'ready'
  );

  const missingDestinationMessage = !destinationPlace
    ? 'Please select a destination using the search bar before starting your trip.'
    : routeState.status === 'error'
      ? (routeState.errorMessage || 'Unable to calculate road route to destination.')
      : routeState.status === 'loading'
        ? 'Calculating road route to destination…'
        : null;

  async function handleStartTrip() {
    if (!canStartTrip) {
      if (!destinationPlace) {
        setActiveTarget('destination');
      }
      return;
    }
    await startRide();
  }

  function handleSelect(place: PlaceSuggestion, target: SearchTarget = activeTarget) {
    if (target === 'pickup') {
      setSelectedPlace(place);
      search.setQuery(place.name);
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

  function handleBookAnotherRide() {
    resetDispatch();
    setDestinationPlace(null);
    clearRoute();
    setActiveTarget('pickup');
  }

  // Active vehicle marker position across lifecycle stages
  const animatedVehiclePosition: [number, number] | null =
    dispatchState.phase === 'in_progress'
      ? (tripAnimation.currentPosition ?? (selectedPlace ? [selectedPlace.lon, selectedPlace.lat] : null))
      : dispatchState.phase === 'arrived'
        ? (selectedPlace ? [selectedPlace.lon, selectedPlace.lat] : null)
        : dispatchState.phase === 'completed'
          ? (destinationPlace ? [destinationPlace.lon, destinationPlace.lat] : null)
          : (dispatchState.phase === 'matched' || dispatchState.phase === 'en_route')
            ? approachAnimation.currentPosition
            : null;

  const animatedVehicleBearing: number =
    dispatchState.phase === 'in_progress'
      ? tripAnimation.bearing
      : approachAnimation.bearing;

  const remainingDistanceMeters =
    dispatchState.phase === 'in_progress'
      ? tripAnimation.remainingDistanceMeters
      : approachAnimation.remainingDistanceMeters;

  const remainingDurationSeconds =
    dispatchState.phase === 'in_progress'
      ? tripAnimation.remainingDurationSeconds
      : approachAnimation.remainingDurationSeconds;

  // Obsolete approach route and origin marker are hidden once trip starts or completes
  const activeApproachRoute =
    (dispatchState.phase === 'matched' || dispatchState.phase === 'en_route')
      ? (driverApproach.route?.coordinates ?? null)
      : null;

  const activeMatchedDriverLocation =
    (dispatchState.phase === 'matched' || dispatchState.phase === 'en_route')
      ? dispatchState.matchedDriverLocation
      : null;

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
        matchedDriverLocation={activeMatchedDriverLocation}
        driverApproachRoute={activeApproachRoute}
        animatedVehiclePosition={animatedVehiclePosition}
        animatedVehicleBearing={animatedVehicleBearing}
        activePhase={dispatchState.phase}
      />

      {/* ── Phase 11.1: Road route preview & fare card (only while idle) ──────── */}
      {selectedPlace && destinationPlace && routeState.route && mapState.status === 'ready' && dispatchState.phase === 'idle' && (
        <RoutePreviewCard
          pickup={selectedPlace}
          destination={destinationPlace}
          route={routeState.route}
          selectedTier={routeState.selectedTier}
          onSelectTier={setSelectedTier}
          onRequestDispatch={requestDispatch}
          onClearDestination={handleClearDestination}
          isLoading={false}
        />
      )}

      {/* ── Phase 11.2 & 11.3: Real dispatch & vehicle tracking panel ────────── */}
      {selectedPlace && mapState.status === 'ready' && (!destinationPlace || dispatchState.phase !== 'idle') && (
        <DispatchPanel
          placeName={selectedPlace.name}
          dispatchState={dispatchState}
          onRequestDispatch={requestDispatch}
          onCancelRide={cancelRide}
          isCancelling={isCancelling}
          onStartTrip={handleStartTrip}
          isStarting={isStarting}
          canStartTrip={canStartTrip}
          missingDestinationMessage={missingDestinationMessage}
          onCompleteTrip={completeRide}
          isCompleting={isCompleting}
          destinationPlace={destinationPlace}
          route={routeState.route}
          selectedTier={routeState.selectedTier}
          onReset={handleBookAnotherRide}
          approachStatus={driverApproach.status}
          remainingDistanceMeters={remainingDistanceMeters}
          remainingDurationSeconds={remainingDurationSeconds}
          isVehicleArrived={dispatchState.phase === 'arrived'}
        />
      )}
    </>
  );
}
