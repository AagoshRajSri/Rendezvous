import { GlobeMap } from './components/GlobeMap';
import { Header } from './components/Header';
import { useMapReady } from './hooks/useMapReady';

/**
 * Root application component.
 *
 * Composes the Header and GlobeMap. The shared mapState from useMapReady
 * flows down to both: Header renders the status chip, GlobeMap controls
 * loading/error overlays and notifies state changes via the hook callbacks.
 */
export default function App() {
  const { state: mapState, onLoad, onError } = useMapReady();

  return (
    <>
      <Header mapState={mapState} />
      <GlobeMap mapState={mapState} onLoad={onLoad} onError={onError} />
    </>
  );
}
